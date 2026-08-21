package com.spc.pricecompare.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.spc.pricecompare.ai.QueryIntentParser;
import com.spc.pricecompare.ai.TopsisScoringService;
import com.spc.pricecompare.domain.Offer;
import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.provider.ProviderProperties;
import com.spc.pricecompare.provider.ProviderRegistry;
import com.spc.pricecompare.provider.RawListing;
import com.spc.pricecompare.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Search, read-through.
 *
 * <p>The database is the primary source and the marketplaces are consulted only
 * when it cannot answer. That ordering is forced by the free API tiers: a few
 * hundred calls a month does not survive a provider round trip per keystroke.
 * So a query is served from stored products where possible, providers are
 * called only when stored coverage is thin, and everything they return is
 * persisted so the same query never costs quota twice.
 *
 * <p>A short-lived cache of already-fetched query strings sits in front of that,
 * which stops a user refreshing or paginating from spending quota at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchService {

    /** Below this many stored matches, the marketplaces are worth asking. */
    private static final int THIN_COVERAGE_THRESHOLD = 5;

    private final ProductRepository productRepository;
    private final ProviderRegistry providerRegistry;
    private final ProviderProperties providerProperties;
    private final IngestionService ingestionService;
    private final QueryIntentParser intentParser;
    private final TopsisScoringService topsisService;
    private final ProductMapper mapper;
    private final ScoringInputBuilder scoringInputBuilder;

    /**
     * Remembers which queries were recently sent to the providers. Purely a
     * quota guard: pagination and refreshes must not each trigger a fresh fetch.
     */
    private final Cache<String, Boolean> recentlyFetched = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(500)
            .build();

    @Transactional
    public Dtos.SearchResponse search(String rawQuery,
                                      int page,
                                      int size,
                                      Map<String, String> filters,
                                      String sort) {

        QueryIntentParser.ParsedQuery parsed = intentParser.parse(rawQuery);
        String terms = parsed.searchTerms() == null || parsed.searchTerms().isBlank()
                ? (rawQuery == null ? "" : rawQuery.trim())
                : parsed.searchTerms();

        List<Product> stored = findCandidates(terms, parsed);

        boolean fetchedLive = false;
        if (!terms.isBlank() && stored.size() < THIN_COVERAGE_THRESHOLD
                && recentlyFetched.getIfPresent(cacheKey(terms)) == null) {
            fetchedLive = fetchFromProviders(terms);
            if (fetchedLive) {
                stored = findCandidates(terms, parsed);
            }
        }

        List<ProductWithOffers> candidates = stored.stream()
                .distinct()
                .map(p -> new ProductWithOffers(p, new ArrayList<>(p.getOffers())))
                .filter(pw -> !pw.offers().isEmpty())
                .filter(pw -> matchesFilters(pw, parsed, filters))
                .toList();

        Map<Long, Double> scores = scoreAll(candidates);

        List<Dtos.ProductSummaryDto> summaries = candidates.stream()
                .map(pw -> mapper.toSummary(pw.product(), pw.offers(), scores.get(pw.product().getId())))
                .sorted(comparatorFor(sort))
                .toList();

        int totalResults = summaries.size();
        int safeSize = Math.max(1, Math.min(size, 100));
        int totalPages = (int) Math.ceil(totalResults / (double) safeSize);
        int safePage = Math.max(0, page);
        int from = Math.min(safePage * safeSize, totalResults);
        int to = Math.min(from + safeSize, totalResults);

        return Dtos.SearchResponse.builder()
                .query(rawQuery)
                .interpretedAs(parsed.interpretedAs())
                .products(summaries.subList(from, to))
                .page(safePage)
                .size(safeSize)
                .totalResults(totalResults)
                .totalPages(totalPages)
                .fetchedLive(fetchedLive)
                .sourcesUsed(providerRegistry.statuses().stream()
                        .filter(s -> s.configured())
                        .map(s -> s.displayName())
                        .toList())
                .build();
    }

    /**
     * Finds candidate products for a query.
     *
     * <p>Three things this gets right that a single phrase match did not.
     *
     * <p>It matches <b>per token</b>: "smart phone" as one string appears in no
     * product title, so the phrase match returned nothing, while the token
     * "phone" matches every iPhone.
     *
     * <p>It <b>ranks by how many tokens hit</b>, so a product matching every
     * word outranks one matching a single common word.
     *
     * <p>And when the words match nothing but the phrase did resolve to a
     * category or brand, it <b>falls back to that filter</b>. A search for
     * "smart phone" whose only useful content is the category should return
     * smartphones, not an empty page - especially since the interface has
     * already told the user it read the query as that category.
     */
    private List<Product> findCandidates(String terms, QueryIntentParser.ParsedQuery parsed) {
        if (terms == null || terms.isBlank()) {
            return productRepository.findAllWithOffers();
        }

        List<String> tokens = searchTokens(terms);
        if (tokens.isEmpty()) {
            return filterOnlyCandidates(parsed);
        }

        // Count how many tokens each product matched, so relevance can be ordered.
        Map<Long, Integer> hits = new LinkedHashMap<>();
        Map<Long, Product> byId = new LinkedHashMap<>();
        for (String token : tokens) {
            for (Product product : productRepository.searchByText(token)) {
                byId.putIfAbsent(product.getId(), product);
                hits.merge(product.getId(), 1, Integer::sum);
            }
        }

        if (byId.isEmpty()) {
            return filterOnlyCandidates(parsed);
        }

        int best = hits.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        // Keep only the products matching the most tokens. Mixing in single-word
        // matches would bury an exact hit under incidental ones.
        List<Product> ranked = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : hits.entrySet()) {
            if (entry.getValue() == best) {
                ranked.add(byId.get(entry.getKey()));
            }
        }
        return ranked;
    }

    /** Everything in the parsed category or brand, when the words themselves matched nothing. */
    private List<Product> filterOnlyCandidates(QueryIntentParser.ParsedQuery parsed) {
        if (parsed.category() != null) {
            return productRepository.findByCategorySlug(parsed.category());
        }
        if (parsed.brand() != null) {
            return productRepository.findByBrandIgnoreCase(parsed.brand());
        }
        return List.of();
    }

    /** Query words worth matching on, with very short and purely noise words dropped. */
    private static List<String> searchTokens(String terms) {
        List<String> tokens = new ArrayList<>();
        for (String raw : terms.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            // Two characters or fewer matches almost anything via LIKE.
            if (raw.length() < 3 || NOISE_TOKENS.contains(raw)) {
                continue;
            }
            if (!tokens.contains(raw)) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    private static final Set<String> NOISE_TOKENS = Set.of(
            "the", "and", "for", "with", "new", "best", "buy", "top", "all",
            "any", "get", "under", "over", "from", "smart", "good", "cheap"
    );

    private boolean fetchFromProviders(String terms) {
        try {
            List<RawListing> listings =
                    providerRegistry.searchAll(terms, providerProperties.getSearchLimit());
            recentlyFetched.put(cacheKey(terms), Boolean.TRUE);
            if (listings.isEmpty()) {
                return false;
            }
            ingestionService.ingest(listings);
            return true;
        } catch (Exception e) {
            // Stored results are still worth returning; a provider failure should
            // degrade the answer, not replace it with an error.
            log.warn("Live provider fetch failed for [{}]: {}", terms, e.toString());
            return false;
        }
    }

    /**
     * Scores the whole result set with TOPSIS.
     *
     * <p>Ranking is done across the full candidate set before paging, so page 2
     * is genuinely worse than page 1 rather than being a separately ranked
     * island. Below two candidates there is no ideal to measure against, so no
     * score is claimed at all.
     */
    private Map<Long, Double> scoreAll(List<ProductWithOffers> candidates) {
        if (candidates.size() < 2) {
            return Map.of();
        }
        List<TopsisScoringService.Alternative> alternatives = candidates.stream()
                .map(pw -> scoringInputBuilder.toAlternative(pw.product(), pw.offers()))
                .toList();

        Map<Long, Double> scores = new LinkedHashMap<>();
        for (TopsisScoringService.Scored scored : topsisService.rank(alternatives).ranked()) {
            scores.put(scored.productId(), scored.score());
        }
        return scores;
    }

    private boolean matchesFilters(ProductWithOffers pw,
                                   QueryIntentParser.ParsedQuery parsed,
                                   Map<String, String> filters) {
        List<Offer> offers = pw.offers();
        BigDecimal best = offers.stream()
                .map(Offer::getPriceInr)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(null);
        if (best == null) {
            return false;
        }

        // Filters from the parsed phrase and from explicit query parameters are
        // both applied; an explicit parameter wins where they disagree, since
        // the user set it deliberately.
        BigDecimal minPrice = decimalFilter(filters, "minPrice", parsed.minPrice());
        BigDecimal maxPrice = decimalFilter(filters, "maxPrice", parsed.maxPrice());
        if (minPrice != null && best.compareTo(minPrice) < 0) {
            return false;
        }
        if (maxPrice != null && best.compareTo(maxPrice) > 0) {
            return false;
        }

        String brand = stringFilter(filters, "brand", parsed.brand());
        if (brand != null && (pw.product().getBrand() == null
                || !pw.product().getBrand().equalsIgnoreCase(brand))) {
            return false;
        }

        String category = stringFilter(filters, "category", parsed.category());
        if (category != null) {
            String slug = pw.product().getCategory() == null ? null : pw.product().getCategory().getSlug();
            if (slug == null || !slug.equalsIgnoreCase(category)) {
                return false;
            }
        }

        BigDecimal minRating = decimalFilter(filters, "minRating", parsed.minRating());
        if (minRating != null) {
            BigDecimal rating = offers.stream()
                    .map(Offer::getRating)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);
            if (rating == null || rating.compareTo(minRating) < 0) {
                return false;
            }
        }

        String platform = filters == null ? null : filters.get("platform");
        if (platform != null && !platform.isBlank()
                && offers.stream().noneMatch(o -> o.getPlatform().getCode().equalsIgnoreCase(platform))) {
            return false;
        }

        boolean inStockOnly = Boolean.TRUE.equals(parsed.inStockOnly())
                || "true".equalsIgnoreCase(filters == null ? null : filters.get("inStock"));
        if (inStockOnly && offers.stream().noneMatch(o -> Boolean.TRUE.equals(o.getInStock()))) {
            return false;
        }

        boolean discountedOnly = Boolean.TRUE.equals(parsed.discountedOnly())
                || "true".equalsIgnoreCase(filters == null ? null : filters.get("discounted"));
        if (discountedOnly && offers.stream().noneMatch(o -> o.getDiscountPct() != null
                && o.getDiscountPct().signum() > 0)) {
            return false;
        }

        return true;
    }

    private Comparator<Dtos.ProductSummaryDto> comparatorFor(String sort) {
        String key = sort == null ? "relevance" : sort.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "price_asc" -> Comparator.comparing(Dtos.ProductSummaryDto::bestPrice,
                    Comparator.nullsLast(BigDecimal::compareTo));
            case "price_desc" -> Comparator.comparing(Dtos.ProductSummaryDto::bestPrice,
                    Comparator.nullsLast(BigDecimal::compareTo)).reversed();
            case "rating_desc" -> Comparator.comparing(Dtos.ProductSummaryDto::rating,
                    Comparator.nullsLast(BigDecimal::compareTo)).reversed();
            case "discount_desc" -> Comparator.comparing(Dtos.ProductSummaryDto::maxDiscountPct,
                    Comparator.nullsLast(BigDecimal::compareTo)).reversed();
            case "savings_desc" -> Comparator.comparing(Dtos.ProductSummaryDto::potentialSaving,
                    Comparator.nullsLast(BigDecimal::compareTo)).reversed();
            // Default: the TOPSIS value score, which is the point of the system.
            default -> Comparator.comparing(Dtos.ProductSummaryDto::valueScore,
                    Comparator.nullsLast(Double::compareTo)).reversed();
        };
    }

    private static BigDecimal decimalFilter(Map<String, String> filters, String key, BigDecimal fallback) {
        String raw = filters == null ? null : filters.get(key);
        if (raw != null && !raw.isBlank()) {
            try {
                return new BigDecimal(raw.trim());
            } catch (NumberFormatException e) {
                // Malformed parameter: ignore it rather than failing the search.
                return fallback;
            }
        }
        return fallback;
    }

    private static String stringFilter(Map<String, String> filters, String key, String fallback) {
        String raw = filters == null ? null : filters.get(key);
        return (raw != null && !raw.isBlank()) ? raw.trim() : fallback;
    }

    private static String cacheKey(String terms) {
        return terms.toLowerCase(Locale.ROOT).trim();
    }

    private record ProductWithOffers(Product product, List<Offer> offers) {
    }
}
