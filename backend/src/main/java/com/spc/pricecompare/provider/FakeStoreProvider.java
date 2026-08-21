package com.spc.pricecompare.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.spc.pricecompare.provider.JsonUtil.*;

/**
 * Fallback source. Response shape verified against the live API.
 *
 * <p>FakeStore has no search endpoint, so the full catalogue (20 items) is
 * fetched and filtered here. That is only reasonable because the catalogue is
 * tiny and the whole response is cached; it would be the wrong approach against
 * a real marketplace.
 */
@Component
@Slf4j
public class FakeStoreProvider extends AbstractHttpProvider {

    public FakeStoreProvider(RestClient restClient, QuotaGuard quotaGuard, ProviderProperties properties) {
        super(restClient, quotaGuard, properties, "fakestore");
    }

    @Override
    public String platformCode() {
        return "FAKESTORE";
    }

    @Override
    public String displayName() {
        return "FakeStore";
    }

    @Override
    public boolean isConfigured() {
        return config().isEnabled();
    }

    @Override
    public List<RawListing> search(String query, int limit) {
        List<RawListing> all = fetchAll(limit);
        if (query == null || query.isBlank()) {
            return all.stream().limit(limit).toList();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<RawListing> matched = all.stream()
                .filter(l -> l.title() != null && l.title().toLowerCase(Locale.ROOT).contains(needle))
                .limit(limit)
                .toList();
        return matched;
    }

    public List<RawListing> fetchAll(int limit) {
        return fetch("https://fakestoreapi.com/products", Collections.emptyMap(), "products")
                .map(this::parseProducts)
                .orElse(Collections.emptyList());
    }

    private List<RawListing> parseProducts(Object body) {
        List<RawListing> out = new ArrayList<>();
        for (Object item : asList(body)) {
            Map<String, Object> p = asMap(item);
            if (p.isEmpty()) {
                continue;
            }
            try {
                out.add(RawListing.builder()
                        .platformCode(platformCode())
                        .externalId(str(p.get("id")))
                        .title(str(p.get("title")))
                        .description(str(p.get("description")))
                        .brand(null) // FakeStore has no brand field; inferred from the title later.
                        .categoryHint(str(p.get("category")))
                        .url("https://fakestoreapi.com/products/" + str(p.get("id")))
                        .imageUrl(str(p.get("image")))
                        .price(money(p.get("price")))
                        .currency("USD")
                        .rating(decimal(path(p, "rating", "rate")))
                        .ratingCount(integer(path(p, "rating", "count")))
                        .inStock(true)
                        .seller("FakeStore")
                        .reviews(Collections.emptyList())
                        .build());
            } catch (Exception e) {
                log.debug("Skipping unparseable FakeStore item: {}", e.toString());
            }
        }
        return out;
    }
}
