package com.spc.pricecompare.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per outbound provider call.
 *
 * <p>Free RapidAPI tiers are small (a few hundred calls a month), so calls are
 * accounted for rather than estimated: QuotaGuard counts rows in the current
 * month and refuses to exceed the configured budget.
 */
@Entity
@Table(name = "api_call_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "platform_code", nullable = false, length = 40)
    private String platformCode;

    @Column(nullable = false, length = 200)
    private String endpoint;

    @Column(name = "called_at", nullable = false)
    @Builder.Default
    private Instant calledAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CallStatus status;

    /** Straight from RapidAPI's x-ratelimit-requests-remaining header when present. */
    @Column(name = "quota_remaining")
    private Integer quotaRemaining;
}
