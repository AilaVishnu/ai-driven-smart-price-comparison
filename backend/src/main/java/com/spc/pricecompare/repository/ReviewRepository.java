package com.spc.pricecompare.repository;

import com.spc.pricecompare.domain.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByProductId(Long productId);
    long countByProductId(Long productId);
}
