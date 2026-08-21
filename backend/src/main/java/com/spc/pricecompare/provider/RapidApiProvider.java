package com.spc.pricecompare.provider;

import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Base for the RapidAPI-backed Indian marketplaces.
 *
 * <p>Adds the two required headers and the key check. Both Amazon.in and
 * Flipkart are reached with the same free key, so isConfigured() reduces to
 * "a key is present and this source is enabled".
 */
public abstract class RapidApiProvider extends AbstractHttpProvider {

    protected RapidApiProvider(RestClient restClient,
                               QuotaGuard quotaGuard,
                               ProviderProperties properties,
                               String configKey) {
        super(restClient, quotaGuard, properties, configKey);
    }

    @Override
    public boolean isConfigured() {
        return config().isEnabled() && properties.hasRapidApiKey() && config().getHost() != null;
    }

    protected Map<String, String> headers() {
        // The sanitized value, not the raw one: a key pasted into a properties
        // file routinely arrives with trailing whitespace, and an untrimmed
        // header value is rejected by the gateway.
        return Map.of(
                "x-rapidapi-key", properties.sanitizedKey(),
                "x-rapidapi-host", config().getHost()
        );
    }

    protected Optional<Object> get(String pathAndQuery, String endpointLabel) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        return fetch("https://" + config().getHost() + pathAndQuery, headers(), endpointLabel);
    }

    /**
     * Finds the array of product objects without assuming where it lives.
     *
     * <p>RapidAPI listings wrap their payloads differently and the docs pages
     * could not be read without a key, so several plausible shapes are tried in
     * turn. GET /api/admin/providers/probe prints the real one on the first live
     * call, at which point this can be tightened.
     */
    protected static List<Object> locateProductArray(Object body) {
        String[][] candidatePaths = {
                {"data", "products"},
                {"data", "results"},
                {"data", "items"},
                {"data", "product_list"},
                {"products"},
                {"results"},
                {"items"},
                {"data"},
        };
        for (String[] p : candidatePaths) {
            Object node = JsonUtil.path(body, p);
            if (node instanceof List<?> list && !list.isEmpty()) {
                return JsonUtil.asList(node);
            }
        }
        if (body instanceof List<?> list && !list.isEmpty()) {
            return JsonUtil.asList(body);
        }
        return List.of();
    }
}
