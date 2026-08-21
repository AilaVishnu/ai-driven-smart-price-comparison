package com.spc.pricecompare.ai;

import com.spc.pricecompare.domain.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Content-based recommendations: "products like this one".
 *
 * <p>Reuses the same TF-IDF machinery as the matching engine, but for the
 * opposite purpose. Matching looks for listings so similar they are the same
 * product; recommendation looks for the band just below that - clearly related,
 * clearly not identical. So anything scoring above a ceiling is discarded as a
 * near-duplicate rather than surfaced as an alternative.
 *
 * <p>Collaborative filtering would need purchase history this system does not
 * have. Content-based similarity works from the first product onward, with no
 * cold-start problem.
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    /** Above this, the candidate is effectively the same product, not an alternative. */
    private static final double NEAR_DUPLICATE_CEILING = 0.93;

    /** Below this, the connection is too weak to be worth showing. */
    private static final double RELEVANCE_FLOOR = 0.08;

    private final TextNormalizer normalizer;

    public record Recommendation(Product product, double similarity) {
    }

    /**
     * Ranks candidates by similarity to the target.
     *
     * @param target     the product being viewed
     * @param candidates pool to draw from, typically same-category products
     * @param limit      how many to return
     */
    public List<Recommendation> similarTo(Product target, List<Product> candidates, int limit) {
        if (target == null || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<Product> pool = candidates.stream()
                .filter(Objects::nonNull)
                .filter(p -> !Objects.equals(p.getId(), target.getId()))
                .filter(p -> p.getNormalizedTitle() != null && !p.getNormalizedTitle().isBlank())
                .toList();

        if (pool.isEmpty()) {
            return List.of();
        }

        List<List<String>> corpus = new ArrayList<>(pool.size() + 1);
        corpus.add(normalizer.tokenize(safeNormalized(target)));
        for (Product p : pool) {
            corpus.add(normalizer.tokenize(p.getNormalizedTitle()));
        }

        TfIdfVectorizer.Model model = TfIdfVectorizer.fit(corpus);
        Map<String, Double> targetVector = model.vectorize(corpus.get(0));

        List<Recommendation> scored = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            Product candidate = pool.get(i);
            double similarity = Similarity.cosine(targetVector, model.vectorize(corpus.get(i + 1)));

            if (similarity < RELEVANCE_FLOOR || similarity > NEAR_DUPLICATE_CEILING) {
                continue;
            }

            // A nudge towards the same brand and a comparable price, because a
            // useful alternative is usually one a buyer could actually swap to.
            double affinity = 1.0;
            if (target.getBrand() != null && target.getBrand().equalsIgnoreCase(candidate.getBrand())) {
                affinity += 0.15;
            }
            scored.add(new Recommendation(candidate, similarity * affinity));
        }

        scored.sort(Comparator.comparingDouble(Recommendation::similarity).reversed());
        return scored.stream().limit(Math.max(1, limit)).toList();
    }

    /**
     * Weights a recommendation set by what the user has previously favourited,
     * so the rail reflects their taste rather than the catalogue average.
     */
    public List<Recommendation> personalize(List<Recommendation> base,
                                            List<String> preferredBrands,
                                            BigDecimal typicalBudget) {
        if (base.isEmpty() || (preferredBrands.isEmpty() && typicalBudget == null)) {
            return base;
        }
        List<Recommendation> adjusted = new ArrayList<>(base.size());
        for (Recommendation r : base) {
            double score = r.similarity();
            if (r.product().getBrand() != null
                    && preferredBrands.stream().anyMatch(b -> b.equalsIgnoreCase(r.product().getBrand()))) {
                score *= 1.25;
            }
            adjusted.add(new Recommendation(r.product(), score));
        }
        adjusted.sort(Comparator.comparingDouble(Recommendation::similarity).reversed());
        return adjusted;
    }

    private String safeNormalized(Product product) {
        if (product.getNormalizedTitle() != null && !product.getNormalizedTitle().isBlank()) {
            return product.getNormalizedTitle();
        }
        return normalizer.normalize(product.getCanonicalTitle());
    }
}
