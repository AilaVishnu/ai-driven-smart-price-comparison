package com.spc.pricecompare.repository;

import com.spc.pricecompare.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Broad candidate fetch by normalized text.
     *
     * <p>Deliberately loose: price, rating and platform filtering happen in the
     * service layer, because TOPSIS has to score the whole candidate set in
     * memory regardless, and pushing those predicates into SQL would only
     * duplicate the logic.
     */
    /**
     * Products matching a single search token.
     *
     * <p>Matched one token at a time, deliberately. Matching the whole phrase
     * meant "smart phone" looked for the literal string "smart phone", which
     * appears in no product title, so the search returned nothing while
     * "%phone%" would have matched every iPhone. The caller queries each token
     * and combines the results.
     */
    @Query("""
           SELECT DISTINCT p FROM Product p
           LEFT JOIN FETCH p.offers o
           WHERE LOWER(p.normalizedTitle) LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(p.canonicalTitle)  LIKE LOWER(CONCAT('%', :q, '%'))
              OR LOWER(COALESCE(p.brand, '')) LIKE LOWER(CONCAT('%', :q, '%'))
           """)
    List<Product> searchByText(@Param("q") String q);

    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.offers WHERE p.category.slug = :slug")
    List<Product> findByCategorySlug(@Param("slug") String slug);

    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.offers WHERE p.id IN :ids")
    List<Product> findAllByIdWithOffers(@Param("ids") List<Long> ids);

    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.offers")
    List<Product> findAllWithOffers();

    /**
     * Products carried by more than one platform.
     *
     * <p>Asked of the database rather than found by filtering a page of results.
     * The home page used to pull the first hundred products and filter them,
     * which surfaced one cross-platform product out of six purely because the
     * other five sat on later pages - hiding the feature the whole application
     * exists to show.
     */
    @Query("""
           SELECT o.product.id FROM Offer o
           GROUP BY o.product.id
           HAVING COUNT(DISTINCT o.platform.id) > 1
           """)
    List<Long> findCrossPlatformIds();

    Optional<Product> findByNormalizedTitleAndBrand(String normalizedTitle, String brand);

    List<Product> findByBrandIgnoreCase(String brand);

    @Query("SELECT DISTINCT p.brand FROM Product p WHERE p.brand IS NOT NULL ORDER BY p.brand")
    List<String> findDistinctBrands();
}
