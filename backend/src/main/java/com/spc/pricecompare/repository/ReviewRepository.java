package com.spc.pricecompare.repository;

import com.spc.pricecompare.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByProductId(Long productId);
    long countByProductId(Long productId);

    /** One product's mean sentiment, paired with its id, for batch scoring. */
    interface ProductSentiment {
        Long getProductId();
        Double getMeanSentiment();
    }

    /**
     * Mean review sentiment for many products in a single query.
     *
     * <p>Scoring a result set used to fetch each product's reviews on its own -
     * one query per product - which was invisible on a keyword search of a
     * hundred hits but turned a browse-everything request into a thousand
     * round trips. Aggregating in the database collapses that to one.
     */
    @Query("""
            SELECT r.product.id AS productId, AVG(r.sentimentScore) AS meanSentiment
            FROM Review r
            WHERE r.product.id IN :productIds AND r.sentimentScore IS NOT NULL
            GROUP BY r.product.id
            """)
    List<ProductSentiment> meanSentimentByProductIds(@Param("productIds") Collection<Long> productIds);
}
