package com.spc.pricecompare.repository;

import com.spc.pricecompare.domain.Offer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OfferRepository extends JpaRepository<Offer, Long> {

    Optional<Offer> findByPlatformIdAndExternalId(Long platformId, String externalId);

    List<Offer> findByProductId(Long productId);

    /** Backs GET /api/deals - biggest live discounts first. */
    @Query("""
           SELECT o FROM Offer o
           WHERE o.discountPct IS NOT NULL AND o.inStock = TRUE
           ORDER BY o.discountPct DESC
           """)
    List<Offer> findTopDeals(org.springframework.data.domain.Pageable pageable);

    @Query("SELECT o FROM Offer o WHERE o.product.id IN :productIds")
    List<Offer> findByProductIds(@Param("productIds") List<Long> productIds);

    /**
     * How many offers a platform has contributed.
     *
     * <p>Lets seeding be decided per platform rather than by whether the
     * catalogue is empty overall: a marketplace that only becomes available
     * later still needs populating, even though other sources already filled the
     * catalogue.
     */
    @Query("SELECT COUNT(o) FROM Offer o WHERE o.platform.code = :code")
    long countByPlatformCode(@Param("code") String code);

    /**
     * Offers a platform has contributed within one category.
     *
     * <p>Lets seeding resume per category. A whole-platform check would treat a
     * partial seed as done, so categories that timed out on the first run would
     * never be retried.
     */
    @Query("""
           SELECT COUNT(o) FROM Offer o
           WHERE o.platform.code = :code AND o.product.category.slug = :slug
           """)
    long countByPlatformCodeAndCategorySlug(@Param("code") String code, @Param("slug") String slug);
}
