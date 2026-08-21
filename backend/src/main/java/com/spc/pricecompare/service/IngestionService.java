package com.spc.pricecompare.service;

import com.spc.pricecompare.ai.ProductMatchingService;
import com.spc.pricecompare.ai.SentimentAnalyzer;
import com.spc.pricecompare.ai.TextNormalizer;
import com.spc.pricecompare.domain.*;
import com.spc.pricecompare.provider.RawListing;
import com.spc.pricecompare.provider.RawReview;
import com.spc.pricecompare.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns provider listings into persisted products, offers, reviews and price
 * history.
 *
 * <p>Everything fetched is written down. That is not incidental - the free
 * marketplace tiers allow only a few hundred calls a month, so a listing
 * retrieved once must serve every later request from the database rather than
 * costing quota again. Persistence here is what makes the quota ceiling
 * survivable.
 *
 * <p>The harder part is matching a fresh batch against what is already stored.
 * A search for "iphone 15" today must attach its Flipkart offer to the same
 * product row that yesterday search created from Amazon, or the catalogue
 * quietly fills with duplicates and comparison stops working.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private final ProductRepository productRepository;
    private final OfferRepository offerRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final PlatformRepository platformRepository;
    private final CategoryRepository categoryRepository;

    private final ProductMatchingService matchingService;
    private final TextNormalizer normalizer;
    private final SentimentAnalyzer sentimentAnalyzer;
    private final CurrencyService currencyService;

    /**
     * Ingests a batch of listings and returns the products they belong to.
     *
     * @return ids of every product created or updated by this batch
     */
    @Transactional
    public List<Long> ingest(List<RawListing> listings) {
        if (listings == null || listings.isEmpty()) {
            return List.of();
        }

        // Convert to INR first. The matcher compares prices directly in its
        // gating step, which would be meaningless across mixed currencies.
        List<RawListing> inr = listings.stream()
                .filter(l -> l != null && l.isUsable())
                .map(this::toInr)
                .toList();

        List<ProductMatchingService.Cluster> clusters = matchingService.cluster(inr);

        Set<Long> touched = new LinkedHashSet<>();
        for (ProductMatchingService.Cluster cluster : clusters) {
            try {
                Product product = resolveProduct(cluster);
                for (RawListing listing : cluster.getListings()) {
                    upsertOffer(product, listing);
                }
                touched.add(product.getId());
            } catch (Exception e) {
                // One bad cluster must not abandon the rest of the batch.
                log.warn("Failed to ingest cluster [{}]: {}", cluster.getCanonicalTitle(), e.toString());
            }
        }

        log.info("Ingested {} listings into {} products", inr.size(), touched.size());
        return new ArrayList<>(touched);
    }

    private RawListing toInr(RawListing listing) {
        String currency = listing.currency();
        if (currency == null || "INR".equalsIgnoreCase(currency)) {
            return listing;
        }
        return RawListing.builder()
                .platformCode(listing.platformCode())
                .externalId(listing.externalId())
                .title(listing.title())
                .description(listing.description())
                .brand(listing.brand())
                .categoryHint(listing.categoryHint())
                .url(listing.url())
                .imageUrl(listing.imageUrl())
                .price(currencyService.toInr(listing.price(), currency))
                .currency("INR")
                .originalPrice(currencyService.toInr(listing.originalPrice(), currency))
                .discountPct(listing.discountPct())
                .rating(listing.rating())
                .ratingCount(listing.ratingCount())
                .inStock(listing.inStock())
                .deliveryDays(listing.deliveryDays())
                .warranty(listing.warranty())
                .returnPolicy(listing.returnPolicy())
                .seller(listing.seller())
                .reviews(listing.reviews())
                .build();
    }

    /**
     * Finds the product this cluster belongs to, or creates it.
     *
     * <p>Two routes are tried before creating anything. An offer already stored
     * under the same platform and external id is conclusive - that is the same
     * listing, so it is the same product. Otherwise the cluster is matched
     * against stored products of the same brand, reusing the same engine that
     * grouped the batch, so the in-batch and cross-batch decisions cannot drift
     * apart.
     */
    private Product resolveProduct(ProductMatchingService.Cluster cluster) {
        for (RawListing listing : cluster.getListings()) {
            Optional<Platform> platform = platformRepository.findByCode(listing.platformCode());
            if (platform.isEmpty()) {
                continue;
            }
            Optional<Offer> existing =
                    offerRepository.findByPlatformIdAndExternalId(platform.get().getId(), listing.externalId());
            if (existing.isPresent()) {
                return existing.get().getProduct();
            }
        }

        Optional<Product> matched = findMatchingStoredProduct(cluster);
        if (matched.isPresent()) {
            return matched.get();
        }

        RawListing representative = cluster.getListings().get(0);
        Product product = Product.builder()
                .canonicalTitle(truncate(cluster.getCanonicalTitle(), 500))
                .normalizedTitle(truncate(cluster.getNormalizedTitle(), 500))
                .modelKey(truncate(cluster.getModelKey(), 200))
                .brand(truncate(cluster.getBrand(), 120))
                .category(resolveCategory(cluster.getCategoryHint(), cluster.getCanonicalTitle()))
                .description(representative.description())
                .imageUrl(truncate(firstImage(cluster), 1000))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return productRepository.save(product);
    }

    private Optional<Product> findMatchingStoredProduct(ProductMatchingService.Cluster cluster) {
        String brand = cluster.getBrand();
        List<Product> candidates = brand != null
                ? productRepository.findByBrandIgnoreCase(brand)
                : List.of();

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        RawListing probe = cluster.getListings().get(0);
        for (Product candidate : candidates) {
            // Represent the stored product as a listing so the same matcher can
            // judge the pair. Reusing it here is deliberate: a separate rule for
            // cross-batch matching would be a second definition of "same
            // product" and the two would eventually disagree.
            RawListing asListing = RawListing.builder()
                    .platformCode("STORED")
                    .externalId("product-" + candidate.getId())
                    .title(candidate.getCanonicalTitle())
                    .brand(candidate.getBrand())
                    .categoryHint(candidate.getCategory() == null ? null : candidate.getCategory().getSlug())
                    .price(lowestOfferPrice(candidate).orElse(probe.price()))
                    .currency("INR")
                    .ratingCount(0)
                    .inStock(true)
                    .reviews(List.of())
                    .build();

            if (matchingService.cluster(List.of(asListing, probe)).size() == 1) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<BigDecimal> lowestOfferPrice(Product product) {
        return offerRepository.findByProductId(product.getId()).stream()
                .map(Offer::getPriceInr)
                .filter(java.util.Objects::nonNull)
                .min(BigDecimal::compareTo);
    }

    /**
     * Creates or updates the offer, and records a price point whenever the price
     * actually moved. Writing a row on every fetch instead would bury real
     * movement under duplicates and make the forecast meaningless.
     */
    private void upsertOffer(Product product, RawListing listing) {
        Platform platform = platformRepository.findByCode(listing.platformCode()).orElse(null);
        if (platform == null) {
            log.warn("Unknown platform code {} - listing skipped", listing.platformCode());
            return;
        }

        Offer offer = offerRepository
                .findByPlatformIdAndExternalId(platform.getId(), listing.externalId())
                .orElseGet(() -> Offer.builder()
                        .product(product)
                        .platform(platform)
                        .externalId(truncate(listing.externalId(), 200))
                        .build());

        BigDecimal previousPrice = offer.getPriceInr();

        offer.setProduct(product);
        offer.setTitle(truncate(listing.title(), 500));
        offer.setUrl(truncate(listing.url(), 1000));
        offer.setImageUrl(truncate(listing.imageUrl(), 1000));
        offer.setPriceInr(listing.price());
        offer.setOriginalPrice(listing.originalPrice());
        offer.setDiscountPct(clampDiscount(listing.discountPct()));
        offer.setRating(clampRating(listing.rating()));
        offer.setRatingCount(listing.ratingCount() == null ? 0 : listing.ratingCount());
        offer.setInStock(listing.inStock() == null || listing.inStock());
        offer.setDeliveryDays(listing.deliveryDays());
        offer.setWarranty(truncate(listing.warranty(), 200));
        offer.setReturnPolicy(truncate(listing.returnPolicy(), 200));
        offer.setSeller(truncate(listing.seller(), 200));
        offer.setFetchedAt(Instant.now());

        Offer saved = offerRepository.save(offer);

        // Keep both sides of the association in step.
        //
        // Without this the offer is persisted but the in-memory Product still
        // holds the empty collection it was built with. A search that ingests
        // and then re-queries inside the same transaction gets that same managed
        // instance back from the persistence context, sees no offers, and
        // filters the product out - so the first search for any new term
        // returned nothing and only worked on a second attempt.
        boolean alreadyLinked = product.getOffers().stream()
                .anyMatch(existing -> existing.getId() != null
                        && existing.getId().equals(saved.getId()));
        if (!alreadyLinked) {
            product.getOffers().add(saved);
        }

        boolean priceChanged = previousPrice == null || previousPrice.compareTo(saved.getPriceInr()) != 0;
        if (priceChanged) {
            priceHistoryRepository.save(PriceHistory.builder()
                    .offer(saved)
                    .price(saved.getPriceInr())
                    .recordedAt(Instant.now())
                    .source(PriceSource.OBSERVED)
                    .build());
        }

        persistReviews(product, saved, listing.reviews());

        product.setUpdatedAt(Instant.now());
        if (product.getImageUrl() == null && saved.getImageUrl() != null) {
            product.setImageUrl(saved.getImageUrl());
        }

        // Let a later provider correct a placeholder category. Platforms differ
        // in how much they say about a listing - one may report no category at
        // all while another names it precisely - and a product stuck on "other"
        // is invisible to category filters and skipped by the matching gate.
        if (listing.categoryHint() != null) {
            Category better = resolveCategory(listing.categoryHint(), listing.title());
            boolean currentIsPlaceholder = product.getCategory() == null
                    || "other".equals(product.getCategory().getSlug());
            if (better != null && currentIsPlaceholder && !"other".equals(better.getSlug())) {
                product.setCategory(better);
            }
        }

        productRepository.save(product);
    }

    /**
     * Stores reviews with their sentiment already scored.
     *
     * <p>Scoring at write time rather than on every read keeps product pages
     * cheap, and means the stored score is stable rather than shifting if the
     * lexicon is later tuned.
     */
    private void persistReviews(Product product, Offer offer, List<RawReview> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return;
        }
        long alreadyStored = reviewRepository.countByProductId(product.getId());
        if (alreadyStored > 0) {
            // Providers return the same top reviews every call; re-persisting
            // them would inflate the sentiment sample with duplicates.
            return;
        }
        List<Review> toSave = new ArrayList<>();
        for (RawReview raw : reviews) {
            if (raw.body() == null || raw.body().isBlank()) {
                continue;
            }
            SentimentAnalyzer.Result sentiment = sentimentAnalyzer.analyze(raw.body());
            toSave.add(Review.builder()
                    .product(product)
                    .offer(offer)
                    .author(truncate(raw.author(), 200))
                    .rating(clampRating(raw.rating()))
                    .body(raw.body())
                    .reviewDate(raw.date())
                    .sentimentScore(BigDecimal.valueOf(sentiment.score()))
                    .sentimentLabel(sentiment.label())
                    .build());
        }
        if (!toSave.isEmpty()) {
            reviewRepository.saveAll(toSave);
        }
    }

    /**
     * Resolves the category, falling back to the title when the platform did not
     * state one. Amazon returns no category field on search, so without the
     * title fallback every Amazon product would be filed as "other".
     */
    private Category resolveCategory(String hint, String title) {
        String slug = ProductMatchingService.normalizeCategory(hint);
        if (slug == null || "other".equals(slug)) {
            String inferred = ProductMatchingService.inferCategoryFromTitle(title);
            if (inferred != null) {
                slug = inferred;
            }
        }
        if (slug == null) {
            return categoryRepository.findBySlug("other").orElse(null);
        }
        return categoryRepository.findBySlug(slug)
                .or(() -> categoryRepository.findBySlug("other"))
                .orElse(null);
    }

    private static String firstImage(ProductMatchingService.Cluster cluster) {
        return cluster.getListings().stream()
                .map(RawListing::imageUrl)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** The column allows 5 digits with 2 decimals; a bad provider value must not break the write. */
    private static BigDecimal clampDiscount(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value.min(BigDecimal.valueOf(100));
    }

    private static BigDecimal clampRating(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value.min(BigDecimal.valueOf(5));
    }

    /**
     * Trims to the column width, decoding HTML entities on the way.
     *
     * <p>Applied to every stored string rather than per adapter, so a platform
     * that starts emitting entities does not need its own handling.
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = TextNormalizer.decodeEntities(value).trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** Exposed for the bootstrap runner, which reports what a cold start produced. */
    public Map<String, Long> catalogueStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("products", productRepository.count());
        stats.put("offers", offerRepository.count());
        stats.put("reviews", reviewRepository.count());
        return stats;
    }
}
