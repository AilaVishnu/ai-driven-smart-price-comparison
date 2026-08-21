package com.spc.pricecompare.service;

import com.spc.pricecompare.ai.TopsisScoringService;
import com.spc.pricecompare.ai.TopsisScoringService.Criterion;
import com.spc.pricecompare.domain.Offer;
import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.domain.Review;
import com.spc.pricecompare.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assembles the criterion values TOPSIS ranks on.
 *
 * <p>Two details matter for the ranking to behave. Every value is non-negative,
 * because vector normalisation over a column containing negatives produces
 * distances that no longer mean what TOPSIS assumes - so sentiment, which is
 * naturally [-1, 1], is rescaled to [0, 1]. And missing values get a neutral
 * stand-in rather than zero: a platform that simply did not state a delivery
 * estimate should not be scored as though it delivers instantly.
 */
@Component
@RequiredArgsConstructor
public class ScoringInputBuilder {

    /** Assumed when a platform states no delivery estimate. */
    private static final double DEFAULT_DELIVERY_DAYS = 7.0;

    /** Neutral sentiment on the rescaled [0, 1] axis. */
    private static final double NEUTRAL_SENTIMENT = 0.5;

    private final ReviewRepository reviewRepository;

    public TopsisScoringService.Alternative toAlternative(Product product, List<Offer> offers) {
        Map<Criterion, Double> values = new EnumMap<>(Criterion.class);

        List<Offer> priced = offers.stream()
                .filter(o -> o.getPriceInr() != null && o.getPriceInr().signum() > 0)
                .toList();

        double bestPrice = priced.stream()
                .map(Offer::getPriceInr)
                .mapToDouble(BigDecimal::doubleValue)
                .min()
                .orElse(0.0);

        double rating = priced.stream()
                .map(Offer::getRating)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .max()
                .orElse(0.0);

        double ratingCount = priced.stream()
                .map(Offer::getRatingCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        double discount = priced.stream()
                .map(Offer::getDiscountPct)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .max()
                .orElse(0.0);

        double delivery = priced.stream()
                .map(Offer::getDeliveryDays)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .min()
                .orElse((int) DEFAULT_DELIVERY_DAYS);

        boolean anyInStock = priced.stream().anyMatch(o -> Boolean.TRUE.equals(o.getInStock()));

        values.put(Criterion.PRICE, bestPrice);
        values.put(Criterion.RATING, rating);
        values.put(Criterion.RATING_COUNT, ratingCount);
        values.put(Criterion.DISCOUNT, discount);
        values.put(Criterion.SENTIMENT, averageSentiment(product.getId()));
        values.put(Criterion.DELIVERY, delivery);
        values.put(Criterion.AVAILABILITY, anyInStock ? 1.0 : 0.0);

        return TopsisScoringService.Alternative.builder()
                .productId(product.getId())
                .label(product.getCanonicalTitle())
                .values(values)
                .build();
    }

    /** Mean review sentiment, rescaled from [-1, 1] onto [0, 1]. */
    private double averageSentiment(Long productId) {
        List<Review> reviews = reviewRepository.findByProductId(productId);
        if (reviews.isEmpty()) {
            return NEUTRAL_SENTIMENT;
        }
        double total = 0.0;
        int counted = 0;
        for (Review review : reviews) {
            if (review.getSentimentScore() != null) {
                total += review.getSentimentScore().doubleValue();
                counted++;
            }
        }
        if (counted == 0) {
            return NEUTRAL_SENTIMENT;
        }
        return ((total / counted) + 1.0) / 2.0;
    }
}
