package com.spc.pricecompare.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A customer review, with the sentiment scored at ingest time by
 * SentimentAnalyzer rather than on every read.
 */
@Entity
@Table(name = "reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id")
    private Offer offer;

    @Column(length = 200)
    private String author;

    @Column(precision = 3, scale = 2)
    private BigDecimal rating;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(name = "review_date")
    private Instant reviewDate;

    /** Normalized to [-1, 1]. */
    @Column(name = "sentiment_score", precision = 5, scale = 4)
    private BigDecimal sentimentScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "sentiment_label", length = 20)
    private SentimentLabel sentimentLabel;
}
