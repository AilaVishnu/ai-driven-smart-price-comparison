package com.spc.pricecompare.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "comparison_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComparisonHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** JSON array of the compared product ids, as TEXT for H2/MySQL portability. */
    @Column(name = "product_ids_json", nullable = false, columnDefinition = "TEXT")
    private String productIdsJson;

    /** Whichever product TOPSIS ranked first at the time of the comparison. */
    @Column(name = "winner_product_id")
    private Long winnerProductId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
