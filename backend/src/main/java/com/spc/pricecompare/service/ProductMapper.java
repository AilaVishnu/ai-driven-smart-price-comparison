package com.spc.pricecompare.service;

import com.spc.pricecompare.domain.Offer;
import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.domain.Review;
import com.spc.pricecompare.dto.Dtos;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds API shapes from entities.
 *
 * <p>The interesting work is deciding what "the" price and rating of a product
 * are when several platforms disagree. The cheapest in-stock offer is the
 * headline price, since an out-of-stock bargain is not a real option, and the
 * rating is weighted by how many people left it rather than averaged flat - a
 * 5.0 from three buyers should not outrank a 4.4 from nine thousand.
 */
@Component
public class ProductMapper {

    public Dtos.ProductSummaryDto toSummary(Product product, List<Offer> offers, Double valueScore) {
        List<Offer> usable = offers == null ? List.of() : offers.stream()
                .filter(o -> o.getPriceInr() != null && o.getPriceInr().signum() > 0)
                .toList();

        // An out-of-stock listing is not something a buyer can act on, so it does
        // not get to be the headline price. If nothing is in stock, fall back to
        // the whole set rather than showing no price at all.
        List<Offer> purchasable = usable.stream()
                .filter(o -> Boolean.TRUE.equals(o.getInStock()))
                .toList();
        List<Offer> pricingBasis = purchasable.isEmpty() ? usable : purchasable;

        Optional<Offer> cheapest = pricingBasis.stream()
                .min(Comparator.comparing(Offer::getPriceInr));
        Optional<Offer> dearest = pricingBasis.stream()
                .max(Comparator.comparing(Offer::getPriceInr));

        BigDecimal bestPrice = cheapest.map(Offer::getPriceInr).orElse(null);
        BigDecimal highestPrice = dearest.map(Offer::getPriceInr).orElse(null);
        BigDecimal saving = (bestPrice != null && highestPrice != null)
                ? highestPrice.subtract(bestPrice)
                : BigDecimal.ZERO;

        BigDecimal maxDiscount = usable.stream()
                .map(Offer::getDiscountPct)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);

        int totalRatingCount = usable.stream()
                .map(Offer::getRatingCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        return Dtos.ProductSummaryDto.builder()
                .id(product.getId())
                .title(product.getCanonicalTitle())
                .brand(product.getBrand())
                .category(product.getCategory() == null ? null : product.getCategory().getSlug())
                .imageUrl(product.getImageUrl())
                .bestPrice(bestPrice)
                .highestPrice(highestPrice)
                .potentialSaving(saving)
                .bestPlatformCode(cheapest.map(o -> o.getPlatform().getCode()).orElse(null))
                .bestPlatformName(cheapest.map(o -> o.getPlatform().getDisplayName()).orElse(null))
                .offerCount(usable.size())
                .platformCount(usable.stream().map(o -> o.getPlatform().getCode()).distinct().count())
                .rating(weightedRating(usable))
                .ratingCount(totalRatingCount)
                .maxDiscountPct(maxDiscount)
                .inStock(!purchasable.isEmpty())
                .valueScore(valueScore)
                .build();
    }

    public Dtos.OfferDto toOfferDto(Offer offer, BigDecimal bestPrice) {
        return Dtos.OfferDto.builder()
                .id(offer.getId())
                .platformCode(offer.getPlatform().getCode())
                .platformName(offer.getPlatform().getDisplayName())
                .title(offer.getTitle())
                .url(offer.getUrl())
                .imageUrl(offer.getImageUrl())
                .price(offer.getPriceInr())
                .originalPrice(offer.getOriginalPrice())
                .discountPct(offer.getDiscountPct())
                .rating(offer.getRating())
                .ratingCount(offer.getRatingCount())
                .inStock(offer.getInStock())
                .deliveryDays(offer.getDeliveryDays())
                .warranty(offer.getWarranty())
                .returnPolicy(offer.getReturnPolicy())
                .seller(offer.getSeller())
                .fetchedAt(offer.getFetchedAt())
                .bestPrice(bestPrice != null
                        && offer.getPriceInr() != null
                        && offer.getPriceInr().compareTo(bestPrice) == 0
                        && Boolean.TRUE.equals(offer.getInStock()))
                .build();
    }

    public Dtos.ReviewDto toReviewDto(Review review) {
        return Dtos.ReviewDto.builder()
                .id(review.getId())
                .author(review.getAuthor())
                .rating(review.getRating())
                .body(review.getBody())
                .reviewDate(review.getReviewDate())
                .sentimentScore(review.getSentimentScore())
                .sentimentLabel(review.getSentimentLabel() == null
                        ? null : review.getSentimentLabel().name())
                .build();
    }

    /**
     * Rating averaged across platforms, weighted by how many people rated on
     * each. A flat mean would let a 5.0 from three buyers cancel out a 4.4 from
     * nine thousand, which is not what a shopper means by "the rating".
     */
    private BigDecimal weightedRating(List<Offer> offers) {
        double weightedTotal = 0.0;
        long totalWeight = 0;
        boolean sawAnyRating = false;

        for (Offer offer : offers) {
            if (offer.getRating() == null) {
                continue;
            }
            sawAnyRating = true;
            // A platform reporting a rating but no count still carries one
            // opinion worth of information.
            int count = offer.getRatingCount() == null || offer.getRatingCount() <= 0
                    ? 1 : offer.getRatingCount();
            weightedTotal += offer.getRating().doubleValue() * count;
            totalWeight += count;
        }

        if (!sawAnyRating || totalWeight == 0) {
            return null;
        }
        return BigDecimal.valueOf(weightedTotal / totalWeight)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
