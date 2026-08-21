package com.spc.pricecompare.provider;

import java.util.List;

/**
 * One shopping platform, behind a single interface.
 *
 * <p>Adding or swapping a marketplace is meant to be a matter of writing one of
 * these and nothing else - no service or controller knows which platforms exist.
 */
public interface ProductProvider {

    /** Matches the {@code platforms.code} column, e.g. "AMAZON_IN". */
    String platformCode();

    /** Human-readable name, used in logs and in the provider probe output. */
    String displayName();

    /** True for a real marketplace that needs an API key. */
    boolean isPrimary();

    /**
     * Whether this provider could run right now - enabled in config, and holding
     * whatever credentials it needs. Says nothing about remaining quota, which
     * is QuotaGuard's concern.
     */
    boolean isConfigured();

    /**
     * Searches the platform.
     *
     * <p>Implementations must not throw: a provider that fails returns an empty
     * list so that one bad marketplace degrades the result set instead of
     * failing the whole search.
     */
    List<RawListing> search(String query, int limit);
}
