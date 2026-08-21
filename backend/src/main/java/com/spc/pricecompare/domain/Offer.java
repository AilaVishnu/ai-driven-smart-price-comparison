package com.spc.pricecompare.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One platform's listing of a {@link Product}.
 *
 * <p>Prices are held in INR. The Indian marketplaces return INR natively; any
 * other currency is converted on ingest so every comparison is like-for-like.
 */
@Entity
@Table(name = "offers",
       uniqueConstraints = @UniqueConstraint(name = "uq_offers_platform_external",
                                             columnNames = {"platform_id", "external_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Offer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "platform_id", nullable = false)
    private Platform platform;

    /** The platform's own identifier - ASIN for Amazon, PID for Flipkart. */
    @Column(name = "external_id", nullable = false, length = 200)
    private String externalId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 1000)
    private String url;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "price_inr", nullable = false, precision = 12, scale = 2)
    private BigDecimal priceInr;

    @Column(name = "original_price", precision = 12, scale = 2)
    private BigDecimal originalPrice;

    @Column(name = "discount_pct", precision = 5, scale = 2)
    private BigDecimal discountPct;

    @Column(precision = 3, scale = 2)
    private BigDecimal rating;

    @Column(name = "rating_count", nullable = false)
    @Builder.Default
    private Integer ratingCount = 0;

    @Column(name = "in_stock", nullable = false)
    @Builder.Default
    private Boolean inStock = true;

    @Column(name = "delivery_days")
    private Integer deliveryDays;

    @Column(length = 200)
    private String warranty;

    @Column(name = "return_policy", length = 200)
    private String returnPolicy;

    @Column(length = 200)
    private String seller;

    @Column(name = "fetched_at", nullable = false)
    @Builder.Default
    private Instant fetchedAt = Instant.now();
}
