package com.spc.pricecompare.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.spc.pricecompare.provider.JsonUtil.*;

/**
 * Amazon.in via the RapidAPI Real-Time Amazon Data service.
 *
 * <p>One of the two primary sources: real listings, real INR prices, real
 * ratings and real customer reviews.
 *
 * <p>Field names are matched against several candidates rather than exactly
 * one. The API documentation is served from a JavaScript-rendered page that
 * could not be read without a key, so the precise naming is unconfirmed until
 * the first live call. Matching loosely means a naming difference costs one
 * null field instead of throwing, and GET /api/admin/providers/probe prints the
 * real response so the mapping can be tightened straight away.
 */
@Component
@Slf4j
public class AmazonIndiaProvider extends RapidApiProvider {

    public AmazonIndiaProvider(RestClient restClient, QuotaGuard quotaGuard, ProviderProperties properties) {
        super(restClient, quotaGuard, properties, "amazon-in");
    }

    @Override
    public String platformCode() {
        return "AMAZON_IN";
    }

    @Override
    public String displayName() {
        return "Amazon.in";
    }

    private String country() {
        return Optional.ofNullable(config().getCountry()).orElse("IN");
    }

    @Override
    public List<RawListing> search(String query, int limit) {
        String q = "/search?query=" + encode(query) + "&page=1&country=" + country() + "&sort_by=RELEVANCE";
        return get(q, "search")
                .map(body -> parse(body, limit))
                .orElse(Collections.emptyList());
    }

    /** Raw response for the admin probe, so the true shape can be inspected on day one. */
    public Optional<Object> probe(String query) {
        return get("/search?query=" + encode(query) + "&page=1&country=" + country(), "search(probe)");
    }

    /**
     * Reviews cost a separate call, so this is only invoked for products a user
     * actually opens rather than for every search hit.
     */
    public List<RawReview> fetchReviews(String asin, int limit) {
        String q = "/product-reviews?asin=" + encode(asin) + "&country=" + country() + "&page=1";
        return get(q, "product-reviews")
                .map(body -> parseReviews(body, limit))
                .orElse(Collections.emptyList());
    }

    private List<RawReview> parseReviews(Object body, int limit) {
        List<RawReview> out = new ArrayList<>();
        for (Object item : locateReviewArray(body)) {
            Map<String, Object> r = asMap(item);
            if (r.isEmpty()) {
                continue;
            }
            String text = strOf(r, "review_comment", "review_text", "comment", "body", "content");
            if (text == null) {
                continue;
            }
            out.add(RawReview.builder()
                    .author(strOf(r, "review_author", "author", "reviewer_name", "name"))
                    .rating(decimalOf(r, "review_star_rating", "rating", "star_rating"))
                    .body(text)
                    .build());
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static List<Object> locateReviewArray(Object body) {
        String[][] paths = {{"data", "reviews"}, {"reviews"}, {"data", "results"}, {"results"}};
        for (String[] p : paths) {
            Object node = path(body, p);
            if (node instanceof List<?> l && !l.isEmpty()) {
                return asList(node);
            }
        }
        return List.of();
    }

    private List<RawListing> parse(Object body, int limit) {
        List<RawListing> out = new ArrayList<>();
        for (Object item : locateProductArray(body)) {
            Map<String, Object> p = asMap(item);
            if (p.isEmpty()) {
                continue;
            }
            try {
                RawListing listing = toListing(p);
                if (listing.isUsable()) {
                    out.add(listing);
                }
            } catch (Exception e) {
                log.debug("Skipping unparseable Amazon.in item: {}", e.toString());
            }
            if (out.size() >= limit) {
                break;
            }
        }
        if (out.isEmpty()) {
            log.warn("Amazon.in returned no usable listings. The response shape may differ from "
                    + "expectation - run GET /api/admin/providers/probe?q=... to inspect it.");
        }
        return out;
    }

    private RawListing toListing(Map<String, Object> p) {
        BigDecimal price = moneyOf(p, "product_price", "price", "current_price", "product_minimum_offer_price");
        BigDecimal original = moneyOf(p, "product_original_price", "original_price", "list_price", "mrp");

        // Amazon reports the struck-through price, not a percentage, so derive it.
        BigDecimal discount = null;
        if (price != null && original != null && original.signum() > 0 && original.compareTo(price) > 0) {
            discount = original.subtract(price)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(original, 2, RoundingMode.HALF_UP);
        }

        String availability = strOf(p, "product_availability", "availability");
        Boolean inStock = bool(firstOf(p, "in_stock", "product_availability", "availability"));
        if (inStock == null) {
            inStock = availability == null
                    || !(availability.toLowerCase().contains("unavailable")
                         || availability.toLowerCase().contains("out of stock"));
        }

        Integer ratingCount = integerOf(p, "product_num_ratings", "num_ratings", "rating_count", "reviews_count");

        return RawListing.builder()
                .platformCode(platformCode())
                .externalId(strOf(p, "asin", "product_id", "id"))
                .title(strOf(p, "product_title", "title", "name"))
                .description(strOf(p, "product_description", "description"))
                .brand(strOf(p, "product_brand", "brand", "manufacturer"))
                .categoryHint(strOf(p, "category", "product_category", "department"))
                .url(strOf(p, "product_url", "url", "link"))
                .imageUrl(strOf(p, "product_photo", "product_image", "image", "thumbnail"))
                .price(price)
                .currency(Optional.ofNullable(strOf(p, "currency")).orElse("INR"))
                .originalPrice(original)
                .discountPct(discount)
                .rating(decimalOf(p, "product_star_rating", "star_rating", "rating"))
                .ratingCount(Optional.ofNullable(ratingCount).orElse(0))
                .inStock(inStock)
                .deliveryDays(DeliveryParser.parse(strOf(p, "delivery", "delivery_info", "shipping")))
                .seller(strOf(p, "seller_name", "sold_by", "merchant"))
                .reviews(Collections.emptyList())
                .build();
    }
}
