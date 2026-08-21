package com.spc.pricecompare.service;

import com.spc.pricecompare.ai.PriceForecastService;
import com.spc.pricecompare.ai.RecommendationService;
import com.spc.pricecompare.ai.SentimentAnalyzer;
import com.spc.pricecompare.domain.Offer;
import com.spc.pricecompare.domain.PriceHistory;
import com.spc.pricecompare.domain.PriceSource;
import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.domain.Review;
import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.repository.OfferRepository;
import com.spc.pricecompare.repository.PriceHistoryRepository;
import com.spc.pricecompare.repository.ProductRepository;
import com.spc.pricecompare.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assembles the product detail view: every platform offer side by side, the
 * reviews and what they add up to, the price history and what it implies.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final int DEFAULT_HISTORY_DAYS = 90;
    private static final int SIMILAR_LIMIT = 6;

    private final ProductRepository productRepository;
    private final OfferRepository offerRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    private final SentimentAnalyzer sentimentAnalyzer;
    private final PriceForecastService forecastService;
    private final RecommendationService recommendationService;
    private final ScoringInputBuilder scoringInputBuilder;
    private final ProductMapper mapper;

    @Transactional(readOnly = true)
    public Dtos.ProductDetailDto getDetail(Long productId, int historyDays) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No product with id " + productId));

        List<Offer> offers = offerRepository.findByProductId(productId);

        BigDecimal bestPrice = offers.stream()
                .filter(o -> Boolean.TRUE.equals(o.getInStock()) && o.getPriceInr() != null)
                .map(Offer::getPriceInr)
                .min(BigDecimal::compareTo)
                .orElse(null);

        List<Dtos.OfferDto> offerDtos = offers.stream()
                .sorted(Comparator.comparing(Offer::getPriceInr,
                        Comparator.nullsLast(BigDecimal::compareTo)))
                .map(o -> mapper.toOfferDto(o, bestPrice))
                .toList();

        List<Review> reviews = reviewRepository.findByProductId(productId);
        SentimentAnalyzer.Summary sentiment = sentimentAnalyzer.summarize(
                reviews.stream().map(Review::getBody).filter(Objects::nonNull).toList());

        List<PriceHistory> history = priceHistoryRepository.findForProductSince(
                productId, Instant.now().minus(Duration.ofDays(
                        historyDays <= 0 ? DEFAULT_HISTORY_DAYS : historyDays)));

        PriceForecastService.Forecast forecast = buildForecast(history);

        return Dtos.ProductDetailDto.builder()
                .summary(mapper.toSummary(product, offers, null))
                .description(product.getDescription())
                .offers(offerDtos)
                .reviews(reviews.stream().map(mapper::toReviewDto).toList())
                .sentiment(sentiment)
                .forecast(forecast)
                .priceHistory(history.stream()
                        .map(h -> Dtos.PricePointDto.builder()
                                .at(h.getRecordedAt())
                                .price(h.getPrice())
                                .platformCode(h.getOffer().getPlatform().getCode())
                                .source(h.getSource().name())
                                .build())
                        .toList())
                .similar(getSimilar(productId, SIMILAR_LIMIT))
                .build();
    }

    /**
     * Forecasts from the cheapest offer trajectory.
     *
     * <p>Series from different platforms are not pooled: two platforms at
     * different price levels would look like violent swings when nothing had
     * actually moved. Following the best available price per day is both
     * well-behaved and the number a buyer cares about.
     */
    private PriceForecastService.Forecast buildForecast(List<PriceHistory> history) {
        if (history.isEmpty()) {
            return forecastService.forecast(List.of(), false);
        }

        boolean containsSimulated = history.stream()
                .anyMatch(h -> h.getSource() == PriceSource.SIMULATED);

        Map<Long, List<PriceHistory>> byDay = new java.util.TreeMap<>();
        for (PriceHistory point : history) {
            long day = point.getRecordedAt().getEpochSecond() / 86400L;
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(point);
        }

        List<PriceForecastService.PricePoint> series = new ArrayList<>(byDay.size());
        byDay.forEach((day, points) -> points.stream()
                .min(Comparator.comparing(PriceHistory::getPrice))
                .ifPresent(cheapest -> series.add(new PriceForecastService.PricePoint(
                        cheapest.getRecordedAt(), cheapest.getPrice()))));

        return forecastService.forecast(series, containsSimulated);
    }

    @Transactional(readOnly = true)
    public List<Dtos.ProductSummaryDto> getSimilar(Long productId, int limit) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return List.of();
        }

        // Draw from the same category where there is one, since a useful
        // alternative is nearly always a like-for-like substitute.
        List<Product> pool = product.getCategory() != null
                ? productRepository.findByCategorySlug(product.getCategory().getSlug())
                : productRepository.findAllWithOffers();

        return recommendationService.similarTo(product, pool, limit).stream()
                .map(r -> mapper.toSummary(r.product(),
                        new ArrayList<>(r.product().getOffers()), null))
                .filter(s -> s.bestPrice() != null)
                .toList();
    }

    /** Biggest live discounts, backing the deals page. */
    @Transactional(readOnly = true)
    public List<Dtos.ProductSummaryDto> getDeals(int limit) {
        List<Offer> topOffers = offerRepository.findTopDeals(
                PageRequest.of(0, Math.max(1, Math.min(limit * 3, 150))));

        List<Long> productIds = topOffers.stream()
                .map(o -> o.getProduct().getId())
                .distinct()
                .limit(Math.max(1, limit))
                .toList();

        if (productIds.isEmpty()) {
            return List.of();
        }

        return productRepository.findAllByIdWithOffers(productIds).stream()
                .map(p -> mapper.toSummary(p, new ArrayList<>(p.getOffers()), null))
                .sorted(Comparator.comparing(Dtos.ProductSummaryDto::maxDiscountPct,
                        Comparator.nullsLast(BigDecimal::compareTo)).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Dtos.OfferDto> getOffers(Long productId) {
        List<Offer> offers = offerRepository.findByProductId(productId);
        BigDecimal bestPrice = offers.stream()
                .filter(o -> Boolean.TRUE.equals(o.getInStock()) && o.getPriceInr() != null)
                .map(Offer::getPriceInr)
                .min(BigDecimal::compareTo)
                .orElse(null);
        return offers.stream()
                .sorted(Comparator.comparing(Offer::getPriceInr,
                        Comparator.nullsLast(BigDecimal::compareTo)))
                .map(o -> mapper.toOfferDto(o, bestPrice))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Dtos.ReviewDto> getReviews(Long productId) {
        return reviewRepository.findByProductId(productId).stream()
                .map(mapper::toReviewDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public SentimentAnalyzer.Summary getSentiment(Long productId) {
        return sentimentAnalyzer.summarize(reviewRepository.findByProductId(productId).stream()
                .map(Review::getBody)
                .filter(Objects::nonNull)
                .toList());
    }

    @Transactional(readOnly = true)
    public PriceForecastService.Forecast getForecast(Long productId) {
        return buildForecast(priceHistoryRepository.findForProductSince(
                productId, Instant.now().minus(Duration.ofDays(DEFAULT_HISTORY_DAYS))));
    }

    @Transactional(readOnly = true)
    public List<Dtos.PricePointDto> getPriceHistory(Long productId, int days) {
        return priceHistoryRepository.findForProductSince(
                        productId, Instant.now().minus(Duration.ofDays(days <= 0 ? DEFAULT_HISTORY_DAYS : days)))
                .stream()
                .map(h -> Dtos.PricePointDto.builder()
                        .at(h.getRecordedAt())
                        .price(h.getPrice())
                        .platformCode(h.getOffer().getPlatform().getCode())
                        .source(h.getSource().name())
                        .build())
                .toList();
    }

    /** Used by the comparison endpoint, which needs summaries with value scores attached. */
    @Transactional(readOnly = true)
    public List<Product> findAllByIds(List<Long> ids) {
        return productRepository.findAllByIdWithOffers(ids);
    }

    public ScoringInputBuilder scoringInputBuilder() {
        return scoringInputBuilder;
    }
}
