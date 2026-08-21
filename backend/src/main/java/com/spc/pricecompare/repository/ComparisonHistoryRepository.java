package com.spc.pricecompare.repository;

import com.spc.pricecompare.domain.ComparisonHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ComparisonHistoryRepository extends JpaRepository<ComparisonHistory, Long> {
    List<ComparisonHistory> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
}
