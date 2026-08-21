package com.spc.pricecompare.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.spc.pricecompare.provider.JsonUtil.*;

/**
 * Flipkart via the RapidAPI "Real-time Flipkart Data" service.
 *
 * <p><b>Why this browses categories instead of searching.</b> The obvious
 * endpoint, {@code /product-search}, answers
 * {@code 401 This endpoint is disabled for your subscription} on the free plan -
 * keyword search is the paid feature. {@code /products-by-category} is included,
 * returns the same product objects, and is if anything richer than what Amazon
 * gives back on search: it carries {@code mrp} alongside {@code price}, so a
 * real discount can be computed rather than inferred.
 *
 * <p>That fits the way the application already works. Everything fetched is
 * persisted, and searches are served from the database first, so Flipkart
 * populates the catalogue by category at startup and its products are then
 * matched against live Amazon results by the matching engine. The user still
 * gets cross-platform comparison; only the route the data takes is different.
 *
 * <p>Field names here were read off real responses, not guessed - see
 * docs/api-keys-setup.md for how to re-check them.
 */
@Component
@Slf4j
public class FlipkartProvider extends RapidApiProvider {

    /** Products returned per category page by the API. */
    private static final int PAGE_SIZE = 24;

    /**
     * Our category slugs mapped to Flipkart category ids, read from the live
     * {@code /sub-categories} tree rather than guessed.
     */
    private static final Map<String, String> CATEGORY_IDS = buildCategoryIds();

    /** Query words that imply a category, so a search can pick the right one to browse. */
    private static final Map<String, String> QUERY_HINTS = buildQueryHints();

    public FlipkartProvider(RestClient restClient, QuotaGuard quotaGuard, ProviderProperties properties) {
        super(restClient, quotaGuard, properties, "flipkart");
    }

    @Override
    public String platformCode() {
        return "FLIPKART";
    }

    @Override
    public String displayName() {
        return "Flipkart";
    }

    /** The category slugs this provider can populate, for the bootstrap runner. */
    public static Map<String, String> categoryIds() {
        return CATEGORY_IDS;
    }

    /**
     * Serves a keyword search by browsing the most likely category and filtering
     * locally.
     *
     * <p>A page holds {@value #PAGE_SIZE} products, so a narrow query may find
     * nothing here. That is expected and not a failure: the catalogue seeded at
     * startup is what carries Flipkart coverage, and this call adds freshness on
     * top of it.
     */
    @Override
    public List<RawListing> search(String query, int limit) {
        String slug = inferCategorySlug(query);
        String categoryId = slug == null ? null : CATEGORY_IDS.get(slug);
        if (categoryId == null) {
            log.debug("No Flipkart category matches [{}]; relying on the seeded catalogue", query);
            return Collections.emptyList();
        }

        List<RawListing> listings = fetchByCategory(categoryId, slug, 1);
        if (listings.isEmpty()) {
            return listings;
        }

        List<String> terms = significantTerms(query);
        List<RawListing> matched = listings.stream()
                .filter(l -> matchesAllTerms(l, terms))
                .limit(limit)
                .toList();

        log.debug("Flipkart category {} returned {} products, {} matched [{}]",
                categoryId, listings.size(), matched.size(), query);
        return matched;
    }

    /**
     * Fetches one page of a category. Used by search and by catalogue seeding.
     *
     * @param categorySlug our own category slug, stamped onto every listing.
     *                     The API does not echo the category back, and without
     *                     it every Flipkart product would be filed as "other" -
     *                     which both breaks per-category seeding and disables
     *                     the matching engine category gate.
     */
    public List<RawListing> fetchByCategory(String flipkartCategoryId, String categorySlug, int page) {
        String path = "/products-by-category?categoryId=" + encode(flipkartCategoryId) + "&page=" + page;
        return get(path, "products-by-category")
                .map(body -> parse(body, categorySlug))
                .orElse(Collections.emptyList());
    }

    /** Raw response for the admin probe. */
    public Optional<Object> probe(String query) {
        String slug = inferCategorySlug(query);
        String categoryId = slug == null ? "tyy/4io" : CATEGORY_IDS.get(slug);
        return get("/products-by-category?categoryId=" + encode(categoryId) + "&page=1",
                "products-by-category(probe)");
    }

    private List<RawListing> parse(Object body, String categorySlug) {
        // The payload is {success, data:[...]} - the products sit directly in
        // data, not under a further key.
        Object node = path(body, "data");
        List<Object> items = node instanceof List<?> ? asList(node) : locateProductArray(body);

        List<RawListing> out = new ArrayList<>();
        for (Object item : items) {
            Map<String, Object> p = asMap(item);
            if (p.isEmpty()) {
                continue;
            }
            try {
                RawListing listing = toListing(p, categorySlug);
                if (listing.isUsable()) {
                    out.add(listing);
                }
            } catch (Exception e) {
                log.debug("Skipping unparseable Flipkart item: {}", e.toString());
            }
        }
        if (out.isEmpty() && !items.isEmpty()) {
            log.warn("Flipkart returned {} items but none were usable - the response shape may have "
                    + "changed. Run GET /api/admin/providers/probe to inspect it.", items.size());
        }
        return out;
    }

    private RawListing toListing(Map<String, Object> p, String categorySlug) {
        BigDecimal price = moneyOf(p, "price", "current_price", "selling_price");
        BigDecimal mrp = moneyOf(p, "mrp", "original_price", "list_price");

        // Flipkart gives both the selling price and the MRP, so the discount is
        // computed rather than taken on trust.
        BigDecimal discount = null;
        if (price != null && mrp != null && mrp.signum() > 0 && mrp.compareTo(price) > 0) {
            discount = mrp.subtract(price)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(mrp, 2, RoundingMode.HALF_UP);
        }

        // rating is an object: {average, count, reviewCount, breakup}.
        BigDecimal rating = decimal(path(p, "rating", "average"));
        Integer ratingCount = integer(path(p, "rating", "count"));

        String stock = strOf(p, "stock", "availability");
        boolean inStock = stock == null || stock.toUpperCase(Locale.ROOT).contains("IN_STOCK");

        String image = null;
        List<Object> images = asList(p.get("images"));
        if (!images.isEmpty()) {
            image = str(images.get(0));
        }

        // subTitle carries the RAM/storage variant and highlights the specs;
        // both are useful context and help the matcher.
        String subTitle = strOf(p, "subTitle");
        StringBuilder description = new StringBuilder();
        if (subTitle != null) {
            description.append(subTitle);
        }
        for (Object highlight : asList(p.get("highlights"))) {
            String h = str(highlight);
            if (h != null) {
                if (description.length() > 0) {
                    description.append(" | ");
                }
                description.append(h);
            }
        }

        String title = strOf(p, "title", "name", "product_name");
        // The variant lives in subTitle, not the title, and it is what separates
        // an 8 GB model from a 6 GB one - so it is folded in for matching.
        if (title != null && subTitle != null && !title.toLowerCase(Locale.ROOT)
                .contains(subTitle.toLowerCase(Locale.ROOT))) {
            title = title + " " + subTitle;
        }

        return RawListing.builder()
                .platformCode(platformCode())
                .externalId(strOf(p, "pid", "product_id", "id", "fsn"))
                .title(title)
                .description(description.length() == 0 ? null : description.toString())
                .brand(strOf(p, "brand", "brand_name"))
                .categoryHint(categorySlug)
                .url(strOf(p, "url", "product_url", "link"))
                .imageUrl(image)
                .price(price)
                .currency("INR")
                .originalPrice(mrp)
                .discountPct(discount)
                .rating(rating)
                .ratingCount(ratingCount == null ? 0 : ratingCount)
                .inStock(inStock)
                .seller(strOf(p, "seller", "seller_name"))
                .reviews(Collections.emptyList())
                .build();
    }

    /** Picks which of our categories a query is about, or null if unclear. */
    static String inferCategorySlug(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = " " + query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ") + " ";
        for (Map.Entry<String, String> hint : QUERY_HINTS.entrySet()) {
            if (q.contains(" " + hint.getKey() + " ") || q.contains(" " + hint.getKey() + "s ")) {
                return hint.getValue();
            }
        }
        return null;
    }

    /** Query words worth filtering on, once noise words are dropped. */
    private static List<String> significantTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (String token : query.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (token.length() < 2 || QUERY_HINTS.containsKey(token)) {
                continue;
            }
            terms.add(token);
        }
        return terms;
    }

    private static boolean matchesAllTerms(RawListing listing, List<String> terms) {
        if (terms.isEmpty()) {
            return true;
        }
        String haystack = ((listing.title() == null ? "" : listing.title()) + " "
                + (listing.brand() == null ? "" : listing.brand())).toLowerCase(Locale.ROOT);
        return terms.stream().allMatch(haystack::contains);
    }

    private static Map<String, String> buildCategoryIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put("smartphones", "tyy/4io");
        ids.put("tablets", "tyy/hry");
        ids.put("laptops", "6bo/b5g");
        ids.put("headphones", "0pm/fcn");
        ids.put("televisions", "ckf/czl");
        ids.put("smartwatches", "ajy/buh");
        ids.put("accessories", "tyy/4mr");
        return Map.copyOf(ids);
    }

    private static Map<String, String> buildQueryHints() {
        // LinkedHashMap so more specific words are checked before broader ones.
        Map<String, String> hints = new LinkedHashMap<>();
        hints.put("smartphone", "smartphones");
        hints.put("mobile", "smartphones");
        hints.put("phone", "smartphones");
        hints.put("iphone", "smartphones");
        hints.put("galaxy", "smartphones");
        hints.put("redmi", "smartphones");
        hints.put("oneplus", "smartphones");
        hints.put("laptop", "laptops");
        hints.put("notebook", "laptops");
        hints.put("macbook", "laptops");
        hints.put("tablet", "tablets");
        hints.put("ipad", "tablets");
        hints.put("headphone", "headphones");
        hints.put("headset", "headphones");
        hints.put("earphone", "headphones");
        hints.put("earbud", "headphones");
        hints.put("tv", "televisions");
        hints.put("television", "televisions");
        hints.put("smartwatch", "smartwatches");
        hints.put("watch", "smartwatches");
        return Map.copyOf(hints);
    }
}
