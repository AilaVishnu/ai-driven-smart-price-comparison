package com.spc.pricecompare.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "platforms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Platform {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40, unique = true)
    private String code;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** True for the RapidAPI-backed marketplaces, which need a key to run. */
    @Column(name = "requires_key", nullable = false)
    @Builder.Default
    private Boolean requiresKey = false;

    /** True for the no-key sources that only activate when primaries can't serve. */
    @Column(name = "is_fallback", nullable = false)
    @Builder.Default
    private Boolean isFallback = false;
}
