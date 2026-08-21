package com.spc.pricecompare.provider;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * A platform listing normalized into one shape, before matching and persistence.
 *
 * <p>This is the seam that keeps the rest of the application independent of any
 * particular API. Each adapter's only real job is turning its own response into
 * one of these; everything downstream - matching, scoring, persistence - sees
 * only this type.
 *
 * <p>{@code price} is in {@code currency} as the source reported it. Conversion
 * to INR happens during ingest, not here, so an adapter never has to know about
 * exchange rates.
 */
@Builder
public record RawListing(
        String platformCode,
        String externalId,
        String title,
        String description,
        String brand,
        String categoryHint,
        String url,
        String imageUrl,
        BigDecimal price,
        String currency,
        BigDecimal originalPrice,
        BigDecimal discountPct,
        BigDecimal rating,
        Integer ratingCount,
        Boolean inStock,
        Integer deliveryDays,
        String warranty,
        String returnPolicy,
        String seller,
        List<RawReview> reviews
) {
    /** A listing without an id, title or price cannot be compared, so it is dropped on ingest. */
    public boolean isUsable() {
        return externalId != null && !externalId.isBlank()
                && title != null && !title.isBlank()
                && price != null && price.signum() > 0;
    }
}
