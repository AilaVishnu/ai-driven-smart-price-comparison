package com.spc.pricecompare.repository;

import com.spc.pricecompare.domain.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    List<PriceHistory> findByOfferIdOrderByRecordedAtAsc(Long offerId);

    @Query("""
           SELECT ph FROM PriceHistory ph
           WHERE ph.offer.product.id = :productId AND ph.recordedAt >= :since
           ORDER BY ph.recordedAt ASC
           """)
    List<PriceHistory> findForProductSince(@Param("productId") Long productId,
                                           @Param("since") Instant since);

    boolean existsByOfferId(Long offerId);
}
