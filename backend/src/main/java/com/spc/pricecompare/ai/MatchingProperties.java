package com.spc.pricecompare.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning knobs for the matching engine.
 *
 * <p>Externalised rather than hardcoded because the right threshold depends on
 * how the live marketplaces actually title things, which cannot be known until
 * real responses are in hand. GET /api/admin/matching/preview shows the effect
 * of a change without a rebuild.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {

    /** Minimum combined similarity for two listings to be called the same product. */
    private double threshold = 0.72;

    private Weight weight = new Weight();

    /**
     * Listings whose prices differ by more than this factor are never compared.
     * A phone case and the phone itself can share almost every title token; the
     * price gap is what tells them apart.
     */
    private double priceBandLow = 0.35;

    private double priceBandHigh = 3.0;

    /**
     * Weights are tilted towards the model signature rather than raw token
     * overlap.
     *
     * <p>Cosine similarity is the least reliable of the three against real
     * marketplace copy: Amazon writing "Wireless Headphones" where Flipkart
     * writes "Bluetooth Headset" drops cosine to around 0.44 for what is
     * plainly the same product. Model agreement stays at 1.0 throughout. Since
     * sibling variants and brand mismatches are now rejected outright by the
     * vetoes in ProductMatchingService, leaning on the model term is safe.
     */
    @Getter
    @Setter
    public static class Weight {
        /** Bag-of-words agreement across the whole title. */
        private double cosine = 0.40;
        /** Agreement on the model signature, where one character can matter. */
        private double model = 0.35;
        /** Exact brand agreement. */
        private double brand = 0.25;
    }
}
