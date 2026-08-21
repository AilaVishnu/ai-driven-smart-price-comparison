package com.spc.pricecompare.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.spc.pricecompare.provider.JsonUtil.*;

/**
 * Fallback source. Response shape verified against the live API.
 *
 * <p>Earns its place in the fallback tier because its catalogue uses real brand
 * and model names (iPhone 13 Pro, Dell XPS 13 9300, Galaxy S10) and carries
 * genuine review text - so it gives both the matching engine and the sentiment
 * analyser something real to work on when the marketplaces cannot serve.
 * Prices are USD and converted to INR during ingest.
 */
@Component
@Slf4j
public class DummyJsonProvider extends AbstractHttpProvider {

    public DummyJsonProvider(RestClient restClient, QuotaGuard quotaGuard, ProviderProperties properties) {
        super(restClient, quotaGuard, properties, "dummyjson");
    }

    @Override
    public String platformCode() {
        return "DUMMYJSON";
    }

    @Override
    public String displayName() {
        return "DummyJSON Store";
    }

    @Override
    public boolean isConfigured() {
        return config().isEnabled();
    }

    @Override
    public List<RawListing> search(String query, int limit) {
        String url = "https://dummyjson.com/products/search?q=" + encode(query) + "&limit=" + limit;
        return fetch(url, Collections.emptyMap(), "products/search")
                .map(body -> parseProducts(path(body, "products")))
                .orElse(Collections.emptyList());
    }

    /** Pulls the whole catalogue, used to give the app a browsable set on first start. */
    public List<RawListing> fetchAll(int limit) {
        String url = "https://dummyjson.com/products?limit=" + limit;
        return fetch(url, Collections.emptyMap(), "products")
                .map(body -> parseProducts(path(body, "products")))
                .orElse(Collections.emptyList());
    }

    private List<RawListing> parseProducts(Object productsNode) {
        List<RawListing> out = new ArrayList<>();
        for (Object item : asList(productsNode)) {
            Map<String, Object> p = asMap(item);
            if (p.isEmpty()) {
                continue;
            }
            try {
                out.add(toListing(p));
            } catch (Exception e) {
                log.debug("Skipping unparseable DummyJSON item: {}", e.toString());
            }
        }
        return out;
    }

    private RawListing toListing(Map<String, Object> p) {
        java.math.BigDecimal price = money(p.get("price"));
        java.math.BigDecimal discount = decimal(p.get("discountPercentage"));

        // DummyJSON gives the discounted price plus a percentage, so the pre-discount
        // price is derived rather than reported.
        java.math.BigDecimal original = null;
        if (price != null && discount != null && discount.signum() > 0) {
            java.math.BigDecimal factor = java.math.BigDecimal.ONE
                    .subtract(discount.divide(java.math.BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP));
            if (factor.signum() > 0) {
                original = price.divide(factor, 2, java.math.RoundingMode.HALF_UP);
            }
        }

        String availability = str(p.get("availabilityStatus"));
        Integer stock = integer(p.get("stock"));
        boolean inStock = !"Out of Stock".equalsIgnoreCase(String.valueOf(availability))
                && (stock == null || stock > 0);

        return RawListing.builder()
                .platformCode(platformCode())
                .externalId(str(p.get("id")))
                .title(str(p.get("title")))
                .description(str(p.get("description")))
                .brand(str(p.get("brand")))
                .categoryHint(str(p.get("category")))
                .url("https://dummyjson.com/products/" + str(p.get("id")))
                .imageUrl(str(p.get("thumbnail")))
                .price(price)
                .currency("USD")
                .originalPrice(original)
                .discountPct(discount)
                .rating(decimal(p.get("rating")))
                .ratingCount(asList(p.get("reviews")).size())
                .inStock(inStock)
                .deliveryDays(DeliveryParser.parse(str(p.get("shippingInformation"))))
                .warranty(str(p.get("warrantyInformation")))
                .returnPolicy(str(p.get("returnPolicy")))
                .seller("DummyJSON Store")
                .reviews(parseReviews(p.get("reviews")))
                .build();
    }

    private List<RawReview> parseReviews(Object reviewsNode) {
        List<RawReview> out = new ArrayList<>();
        for (Object item : asList(reviewsNode)) {
            Map<String, Object> r = asMap(item);
            if (r.isEmpty()) {
                continue;
            }
            out.add(RawReview.builder()
                    .author(str(r.get("reviewerName")))
                    .rating(decimal(r.get("rating")))
                    .body(str(r.get("comment")))
                    .date(parseInstant(str(r.get("date"))))
                    .build());
        }
        return out;
    }

    private static Instant parseInstant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
