package com.spc.pricecompare.service;

import com.spc.pricecompare.ai.SentimentAnalyzer;
import com.spc.pricecompare.domain.Offer;
import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.domain.Review;
import com.spc.pricecompare.provider.AmazonIndiaProvider;
import com.spc.pricecompare.provider.RawReview;
import com.spc.pricecompare.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches customer reviews for a product the first time somebody opens it.
 *
 * <p>Reviews are a separate marketplace call, so fetching them during search
 * would spend a month of quota on products nobody looks at. Doing it on the
 * detail view instead means the cost is paid only for products that are
 * actually of interest, and paid once: the reviews are stored with their
 * sentiment already scored and never fetched again.
 *
 * <p>Amazon is the only source here that exposes review text. Flipkart offers
 * it on a paid plan only, so a Flipkart-only product simply has no reviews and
 * the interface says so rather than implying otherwise.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewFetchService {

    /** Enough to characterise sentiment without spending a large response on it. */
    private static final int REVIEW_LIMIT = 20;

    private final ReviewRepository reviewRepository;
    private final SentimentAnalyzer sentimentAnalyzer;
    private final AmazonIndiaProvider amazonProvider;

    @Transactional
    public void fetchReviewsIfMissing(Product product, List<Offer> offers) {
        if (reviewRepository.countByProductId(product.getId()) > 0) {
            return;
        }
        if (!amazonProvider.isConfigured()) {
            return;
        }

        Optional<Offer> amazonOffer = offers.stream()
                .filter(o -> "AMAZON_IN".equals(o.getPlatform().getCode()))
                .findFirst();
        if (amazonOffer.isEmpty()) {
            return;
        }

        String asin = amazonOffer.get().getExternalId();
        try {
            List<RawReview> raw = amazonProvider.fetchReviews(asin, REVIEW_LIMIT);
            if (raw.isEmpty()) {
                return;
            }

            List<Review> toSave = new ArrayList<>(raw.size());
            for (RawReview review : raw) {
                if (review.body() == null || review.body().isBlank()) {
                    continue;
                }
                // Scored on the way in rather than on every read, so a product
                // page stays cheap and the stored score is stable.
                SentimentAnalyzer.Result sentiment = sentimentAnalyzer.analyze(review.body());
                toSave.add(Review.builder()
                        .product(product)
                        .offer(amazonOffer.get())
                        .author(truncate(review.author(), 200))
                        .rating(clampRating(review.rating()))
                        .body(review.body())
                        .reviewDate(review.date())
                        .sentimentScore(BigDecimal.valueOf(sentiment.score()))
                        .sentimentLabel(sentiment.label())
                        .build());
            }

            if (!toSave.isEmpty()) {
                reviewRepository.saveAll(toSave);
                log.info("Fetched {} reviews for product {} ({})", toSave.size(), product.getId(), asin);
            }
        } catch (Exception e) {
            // A product page must still render without reviews.
            log.warn("Could not fetch reviews for {}: {}", asin, e.toString());
        }
    }

    private static BigDecimal clampRating(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return value.min(BigDecimal.valueOf(5));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
