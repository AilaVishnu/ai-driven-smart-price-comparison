package com.spc.pricecompare.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A canonical real-world item.
 *
 * <p>Amazon and Flipkart describe the same phone with different titles, so a
 * product is not something either API hands us - it is inferred by the TF-IDF
 * matching engine, which collapses their listings into one row here. Every
 * platform listing of this item hangs off {@link #offers}, and that one-to-many
 * is what makes side-by-side price comparison possible.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The most complete title among the matched listings. */
    @Column(name = "canonical_title", nullable = false, length = 500)
    private String canonicalTitle;

    /** Output of TextNormalizer; what the TF-IDF vectors are built from. */
    @Column(name = "normalized_title", nullable = false, length = 500)
    private String normalizedTitle;

    /** Extracted model tokens, e.g. "15 pro max". Used for exact-ish re-checks. */
    @Column(name = "model_key", length = 200)
    private String modelKey;

    @Column(length = 120)
    private String brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    /** Specs as a JSON string - TEXT rather than a JSON column, to stay portable across H2 and MySQL. */
    @Column(name = "spec_json", columnDefinition = "TEXT")
    private String specJson;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Offer> offers = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Review> reviews = new ArrayList<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
