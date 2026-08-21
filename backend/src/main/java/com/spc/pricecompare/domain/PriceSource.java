package com.spc.pricecompare.domain;

/**
 * Provenance of a {@link PriceHistory} point.
 *
 * <p>OBSERVED means the price was genuinely read from a platform. SIMULATED
 * means it was backfilled so the forecaster has a series to work with before
 * real history has accumulated. The two are never conflated: the API and UI
 * both surface which is which.
 */
public enum PriceSource {
    OBSERVED,
    SIMULATED
}
