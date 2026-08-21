package com.spc.pricecompare.provider;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/** A review exactly as a provider returned it, before sentiment scoring. */
@Builder
public record RawReview(
        String author,
        BigDecimal rating,
        String body,
        Instant date
) {
}
