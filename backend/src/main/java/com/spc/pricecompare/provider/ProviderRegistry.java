package com.spc.pricecompare.provider;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fans a search out across every usable provider and merges the results.
 *
 * <p>Two things matter here. Providers are queried in parallel with a hard
 * per-provider timeout, so one slow marketplace cannot hold up the response.
 * And the no-key fallback sources are brought in according to
 * {@link ProviderProperties.FallbackMode}: under AUTO they stay out of the way
 * while the marketplaces are working, and step in only when those are
 * unconfigured, out of quota, or returning nothing. The result is an app that
 * shows real Amazon and Flipkart data when it can, and still demonstrates every
 * feature when it cannot.
 */
@Component
@Slf4j
public class ProviderRegistry {

    private final List<ProductProvider> providers;
    private final ProviderProperties properties;
    private final QuotaGuard quotaGuard;
    private final ExecutorService executor;

    public ProviderRegistry(List<ProductProvider> providers,
                            ProviderProperties properties,
                            QuotaGuard quotaGuard) {
        this.providers = providers;
        this.properties = properties;
        this.quotaGuard = quotaGuard;
        this.executor = Executors.newFixedThreadPool(
                Math.max(2, providers.size()),
                r -> {
                    Thread t = new Thread(r, "provider-fanout");
                    t.setDaemon(true);
                    return t;
                });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public List<ProductProvider> all() {
        return providers;
    }

    public Optional<ProductProvider> byCode(String platformCode) {
        return providers.stream().filter(p -> p.platformCode().equals(platformCode)).findFirst();
    }

    /** Primary sources that are configured and still have quota left. */
    public List<ProductProvider> usablePrimaries() {
        return providers.stream()
                .filter(ProductProvider::isPrimary)
                .filter(ProductProvider::isConfigured)
                .filter(this::hasQuota)
                .toList();
    }

    public List<ProductProvider> configuredFallbacks() {
        return providers.stream()
                .filter(p -> !p.isPrimary())
                .filter(ProductProvider::isConfigured)
                .toList();
    }

    private boolean hasQuota(ProductProvider p) {
        if (p instanceof AbstractHttpProvider http) {
            return http.remainingQuota() > 0;
        }
        return true;
    }

    /**
     * Searches every applicable provider and returns the merged listings.
     *
     * <p>Under AUTO the marketplaces are tried first and the fallbacks are only
     * consulted if that produced nothing, which keeps quota spend purposeful.
     */
    public List<RawListing> searchAll(String query, int limit) {
        List<ProductProvider> primaries = usablePrimaries();
        ProviderProperties.FallbackMode mode = properties.getFallbackMode();

        List<RawListing> results = new ArrayList<>(runParallel(primaries, query, limit));

        boolean needFallback = switch (mode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> results.isEmpty();
        };

        if (needFallback) {
            List<ProductProvider> fallbacks = configuredFallbacks();
            if (!fallbacks.isEmpty()) {
                if (primaries.isEmpty()) {
                    log.info("No usable marketplace provider (missing key, disabled, or out of quota). "
                                    + "Serving from fallback sources: {}",
                            fallbacks.stream().map(ProductProvider::displayName).toList());
                } else if (mode == ProviderProperties.FallbackMode.AUTO) {
                    log.info("Marketplaces returned nothing for [{}]. Consulting fallback sources.", query);
                }
                results.addAll(runParallel(fallbacks, query, limit));
            }
        }

        return results;
    }

    private List<RawListing> runParallel(List<ProductProvider> targets, String query, int limit) {
        if (targets.isEmpty()) {
            return List.of();
        }
        long timeout = properties.getRequestTimeoutMs() + 2000L;

        List<CompletableFuture<List<RawListing>>> futures = targets.stream()
                .map(p -> CompletableFuture
                        .supplyAsync(() -> {
                            try {
                                return p.search(query, limit);
                            } catch (Exception e) {
                                // Providers are contracted not to throw, but a misbehaving
                                // one must still never fail the whole search.
                                log.warn("Provider {} threw during search: {}", p.platformCode(), e.toString());
                                return List.<RawListing>of();
                            }
                        }, executor)
                        .completeOnTimeout(List.of(), timeout, TimeUnit.MILLISECONDS))
                .toList();

        List<RawListing> merged = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                List<RawListing> got = futures.get(i).join();
                merged.addAll(got);
                log.debug("{} returned {} listings", targets.get(i).platformCode(), got.size());
            } catch (Exception e) {
                log.warn("Provider {} failed: {}", targets.get(i).platformCode(), e.toString());
            }
        }
        return merged;
    }

    /** Honest per-source status for GET /api/platforms. */
    public List<ProviderStatus> statuses() {
        List<ProviderStatus> out = new ArrayList<>();
        for (ProductProvider p : providers) {
            int quota = 0;
            int remaining = Integer.MAX_VALUE;
            String failure = null;
            if (p instanceof AbstractHttpProvider http) {
                quota = http.config().getMonthlyQuota();
                remaining = http.remainingQuota();
                failure = http.healthNote();
            }
            boolean configured = p.isConfigured();
            boolean healthy = failure == null;

            String note;
            if (!configured && p.isPrimary()) {
                note = properties.hasRapidApiKey()
                        ? "Disabled in configuration"
                        : "No RapidAPI key configured - see docs/api-keys-setup.md";
            } else if (configured && quota > 0 && remaining <= 0) {
                note = "Monthly quota exhausted; serving from cache";
            } else if (!healthy) {
                // A configured provider that fails every call is not live, and
                // reporting it as live would mislead about why results are empty.
                note = failure;
            } else if (!p.isPrimary()) {
                note = "Fallback source (mode=" + properties.getFallbackMode() + ")";
            } else {
                note = "Live";
            }

            out.add(ProviderStatus.builder()
                    .platformCode(p.platformCode())
                    .displayName(p.displayName())
                    .primary(p.isPrimary())
                    .configured(configured)
                    .healthy(healthy)
                    .quotaAvailable(remaining > 0)
                    .quotaRemaining(remaining == Integer.MAX_VALUE ? -1 : remaining)
                    .quotaUsedThisMonth(quotaGuard.used(p.platformCode()))
                    .monthlyQuota(quota)
                    .note(note)
                    .build());
        }
        out.sort(Comparator.comparing(ProviderStatus::primary).reversed()
                .thenComparing(ProviderStatus::displayName));
        return out;
    }

    /**
     * Raw provider responses, backing GET /api/admin/providers/probe.
     *
     * <p>This exists because the RapidAPI response shapes could not be confirmed
     * without a key. On the first live call it prints exactly what the
     * marketplaces return, so the field mapping can be corrected against reality
     * rather than guesswork.
     */
    public Map<String, Object> probeAll(String query) {
        Map<String, Object> out = new HashMap<>();
        for (ProductProvider p : providers) {
            if (!p.isConfigured()) {
                out.put(p.platformCode(), Map.of("skipped", "not configured"));
                continue;
            }
            try {
                // instanceof rather than a pattern switch: pattern switches are a
                // preview feature on Java 17, which is this project baseline.
                Object raw;
                if (p instanceof AmazonIndiaProvider amazon) {
                    raw = amazon.probe(query).orElse(Map.of("error", "no response"));
                } else if (p instanceof FlipkartProvider flipkart) {
                    raw = flipkart.probe(query).orElse(Map.of("error", "no response"));
                } else {
                    raw = Map.of("listings", p.search(query, 3));
                }
                out.put(p.platformCode(), raw);
            } catch (Exception e) {
                out.put(p.platformCode(), Map.of("error", e.toString()));
            }
        }
        return out;
    }
}
