package com.spc.pricecompare.service;

import com.spc.pricecompare.ai.ProductMatchingService;
import com.spc.pricecompare.domain.Category;
import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.repository.CategoryRepository;
import com.spc.pricecompare.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Housekeeping over the stored catalogue that needs a transaction.
 *
 * <p>Separate from the bootstrap runner on purpose. The runner is not itself
 * transactional - it makes network calls, which have no business inside a
 * database transaction - and Spring proxies do not intercept a class calling its
 * own private method, so annotating one there would have done nothing. Touching
 * a lazily-loaded Category from the runner threw LazyInitializationException for
 * exactly that reason.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogMaintenanceService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    /**
     * Re-derives categories from product titles for anything already stored.
     *
     * <p>Repairs data instead of re-fetching it. Products ingested before the
     * title classifier existed carry whatever their platform said - which for
     * Amazon was nothing, leaving them in "other" and invisible to category
     * filters. Others were actively wrong: a platform category of
     * "mobile-accessories" was read as a smartphone, which is how an iPhone
     * charger surfaced in a smartphone search.
     *
     * <p>Only applied where the classifier is confident. A null result leaves the
     * stored category alone, so a platform that did supply something sensible is
     * never overruled by a guess. Costs no API quota and is idempotent.
     *
     * @return how many products were re-filed
     */
    @Transactional
    public int recategorizeFromTitles() {
        List<Product> products = productRepository.findAll();
        int changed = 0;

        for (Product product : products) {
            String inferred = ProductMatchingService.inferCategoryFromTitle(product.getCanonicalTitle());
            if (inferred == null) {
                continue;
            }
            // Inside a transaction, so reading the lazy Category proxy is safe.
            String current = product.getCategory() == null ? null : product.getCategory().getSlug();
            if (inferred.equals(current)) {
                continue;
            }
            Category target = categoryRepository.findBySlug(inferred).orElse(null);
            if (target != null) {
                product.setCategory(target);
                productRepository.save(product);
                changed++;
            }
        }

        if (changed > 0) {
            log.info("Re-categorised {} product(s) from their titles", changed);
        }
        return changed;
    }
}
