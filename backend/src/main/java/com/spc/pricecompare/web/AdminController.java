package com.spc.pricecompare.web;

import com.spc.pricecompare.ai.ProductMatchingService;
import com.spc.pricecompare.provider.ProviderProperties;
import com.spc.pricecompare.provider.ProviderRegistry;
import com.spc.pricecompare.provider.RawListing;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagnostics, mapped only under the dev profile.
 *
 * <p>The probe endpoint is the answer to a specific problem: the RapidAPI
 * documentation pages are JavaScript-rendered and could not be read without a
 * key, so the exact response field names were unknown when the adapters were
 * written. Rather than guessing and hoping, this prints exactly what the
 * marketplaces return on the first live call, so the mapping can be corrected
 * against reality.
 *
 * <p>The matching preview does the same job for the clustering: it shows which
 * listings were judged the same product and why, which is how the threshold
 * gets tuned against real marketplace titles instead of invented ones.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Profile("dev")
public class AdminController {

    private final ProviderRegistry providerRegistry;
    private final ProviderProperties providerProperties;
    private final ProductMatchingService matchingService;

    /** Raw, unparsed provider responses. */
    @GetMapping("/providers/probe")
    public Map<String, Object> probe(@RequestParam(defaultValue = "iphone") String q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("rapidApiKeyConfigured", providerProperties.hasRapidApiKey());
        out.put("fallbackMode", providerProperties.getFallbackMode().name());
        out.put("responses", providerRegistry.probeAll(q));
        return out;
    }

    /**
     * Runs a live search through the matcher and reports the clusters it formed,
     * without persisting anything.
     */
    @GetMapping("/matching/preview")
    public Map<String, Object> matchingPreview(@RequestParam(defaultValue = "iphone") String q) {
        List<RawListing> listings =
                providerRegistry.searchAll(q, providerProperties.getSearchLimit());

        List<ProductMatchingService.Cluster> clusters = matchingService.cluster(listings);

        List<Map<String, Object>> rendered = new ArrayList<>();
        for (ProductMatchingService.Cluster cluster : clusters) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("canonicalTitle", cluster.getCanonicalTitle());
            entry.put("normalizedTitle", cluster.getNormalizedTitle());
            entry.put("modelKey", cluster.getModelKey());
            entry.put("brand", cluster.getBrand());
            entry.put("category", cluster.getCategoryHint());
            entry.put("platformCount", cluster.platformCount());
            entry.put("listings", cluster.getListings().stream()
                    .map(l -> Map.of(
                            "platform", String.valueOf(l.platformCode()),
                            "title", String.valueOf(l.title()),
                            "price", String.valueOf(l.price())))
                    .toList());
            rendered.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("listingsFetched", listings.size());
        out.put("productsFormed", clusters.size());
        out.put("multiPlatformProducts", clusters.stream().filter(c -> c.platformCount() > 1).count());
        out.put("clusters", rendered);
        return out;
    }

    /** Component scores for one pair of titles, for tuning the threshold by hand. */
    @GetMapping("/matching/explain")
    public Map<String, Object> explain(@RequestParam String a, @RequestParam String b) {
        RawListing first = RawListing.builder()
                .platformCode("A").externalId("a").title(a)
                .price(java.math.BigDecimal.valueOf(1000)).currency("INR")
                .ratingCount(0).inStock(true).reviews(List.of()).build();
        RawListing second = RawListing.builder()
                .platformCode("B").externalId("b").title(b)
                .price(java.math.BigDecimal.valueOf(1000)).currency("INR")
                .ratingCount(0).inStock(true).reviews(List.of()).build();
        return matchingService.explain(first, second);
    }

    @GetMapping("/quota")
    public Object quota() {
        return providerRegistry.statuses();
    }
}
