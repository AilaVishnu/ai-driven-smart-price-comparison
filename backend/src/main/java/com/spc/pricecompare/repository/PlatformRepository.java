package com.spc.pricecompare.repository;

import com.spc.pricecompare.domain.Platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformRepository extends JpaRepository<Platform, Long> {
    Optional<Platform> findByCode(String code);
}
