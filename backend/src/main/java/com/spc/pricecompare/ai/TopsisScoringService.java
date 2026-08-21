package com.spc.pricecompare.ai;

import lombok.Builder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks products by TOPSIS - Technique for Order of Preference by Similarity to
 * Ideal Solution.
 *
 * <p>Chosen over an ad-hoc weighted average because the abstract promises a
 * decision support system, and TOPSIS is an actual published multi-criteria
 * decision method rather than an invented formula. It also behaves better: a
 * naive weighted sum lets one criterion on a large numeric scale (review count,
 * which runs to five figures) swamp everything else, whereas TOPSIS normalises
 * each column first so price, rating and delivery speed are genuinely
 * commensurable.
 *
 * <p>The procedure:
 * <ol>
 *   <li>Build the decision matrix, alternatives by criteria.</li>
 *   <li>Vector-normalise each column: r = x / sqrt(sum of squares).</li>
 *   <li>Apply weights: v = w * r.</li>
 *   <li>Identify the ideal best and ideal worst per column, respecting whether
 *       the criterion is a benefit (more is better) or a cost (less is better).</li>
 *   <li>Measure Euclidean distance from each alternative to both ideals.</li>
 *   <li>Closeness C = S- / (S+ + S-), reported as 0-100.</li>
 * </ol>
 *
 * <p>Every score comes with a per-criterion breakdown, so the interface can
 * show why one product beat another rather than asking to be trusted.
 */
@Service
public class TopsisScoringService {

    /**
     * The criteria a shopper actually weighs, and whether more is better.
     *
     * <p>Default weights lean towards price and rating because that is what
     * dominates real purchasing decisions, but the whole point of exposing them
     * is that a user can disagree and re-rank live.
     */
    public enum Criterion {
        PRICE("price", "Price", false, 0.30),
        RATING("rating", "Customer rating", true, 0.20),
        RATING_COUNT("ratingCount", "Number of ratings", true, 0.10),
        DISCOUNT("discount", "Discount", true, 0.15),
        SENTIMENT("sentiment", "Review sentiment", true, 0.10),
        DELIVERY("delivery", "Delivery speed", false, 0.10),
        AVAILABILITY("availability", "In stock", true, 0.05);

        private final String key;
        private final String label;
        private final boolean benefit;
        private final double defaultWeight;

        Criterion(String key, String label, boolean benefit, double defaultWeight) {
            this.key = key;
            this.label = label;
            this.benefit = benefit;
            this.defaultWeight = defaultWeight;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        /** True when a higher raw value is better. */
        public boolean benefit() {
            return benefit;
        }

        public double defaultWeight() {
            return defaultWeight;
        }

        public static Criterion fromKey(String key) {
            for (Criterion c : values()) {
                if (c.key.equalsIgnoreCase(key) || c.name().equalsIgnoreCase(key)) {
                    return c;
                }
            }
            return null;
        }
    }

    /** One product entering the ranking, with its raw criterion values. */
    @Builder
    public record Alternative(Long productId, String label, Map<Criterion, Double> values) {
    }

    @Builder
    public record CriterionDetail(
            String criterion,
            String label,
            boolean benefit,
            double rawValue,
            double weight,
            double weightedNormalized,
            /** 0-100: how close this alternative is to the best on this criterion alone. */
            double criterionScore,
            boolean isBest
    ) {
    }

    @Builder
    public record Scored(
            Long productId,
            String label,
            double score,
            int rank,
            Map<String, CriterionDetail> breakdown
    ) {
    }

    @Builder
    public record Result(List<Scored> ranked, Map<String, Double> weightsUsed, String note) {
    }

    public Map<Criterion, Double> defaultWeights() {
        Map<Criterion, Double> weights = new EnumMap<>(Criterion.class);
        for (Criterion c : Criterion.values()) {
            weights.put(c, c.defaultWeight());
        }
        return weights;
    }

    public Result rank(List<Alternative> alternatives) {
        return rank(alternatives, defaultWeights());
    }

    /**
     * Ranks the alternatives.
     *
     * @param requestedWeights per-criterion weights; normalised to sum to 1, so
     *                         callers may pass slider positions directly
     */
    public Result rank(List<Alternative> alternatives, Map<Criterion, Double> requestedWeights) {
        if (alternatives == null || alternatives.isEmpty()) {
            return Result.builder()
                    .ranked(List.of())
                    .weightsUsed(Map.of())
                    .note("No alternatives to rank")
                    .build();
        }

        Map<Criterion, Double> weights = normalizeWeights(requestedWeights);

        // With one alternative there is no ideal to compare against; TOPSIS is
        // undefined rather than merely uninformative, so say so plainly.
        if (alternatives.size() == 1) {
            Alternative only = alternatives.get(0);
            Map<String, CriterionDetail> breakdown = new LinkedHashMap<>();
            for (Criterion c : Criterion.values()) {
                double raw = value(only, c);
                breakdown.put(c.key(), CriterionDetail.builder()
                        .criterion(c.key())
                        .label(c.label())
                        .benefit(c.benefit())
                        .rawValue(raw)
                        .weight(weights.getOrDefault(c, 0.0))
                        .weightedNormalized(0.0)
                        .criterionScore(100.0)
                        .isBest(true)
                        .build());
            }
            return Result.builder()
                    .ranked(List.of(Scored.builder()
                            .productId(only.productId())
                            .label(only.label())
                            .score(100.0)
                            .rank(1)
                            .breakdown(breakdown)
                            .build()))
                    .weightsUsed(keyedWeights(weights))
                    .note("Only one alternative: comparison against an ideal is not meaningful")
                    .build();
        }

        List<Criterion> criteria = List.of(Criterion.values());
        int m = alternatives.size();
        int n = criteria.size();

        double[][] matrix = new double[m][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                matrix[i][j] = value(alternatives.get(i), criteria.get(j));
            }
        }

        // Step 2: vector normalisation, column by column.
        double[][] normalized = new double[m][n];
        for (int j = 0; j < n; j++) {
            double sumOfSquares = 0.0;
            for (int i = 0; i < m; i++) {
                sumOfSquares += matrix[i][j] * matrix[i][j];
            }
            double denominator = Math.sqrt(sumOfSquares);
            for (int i = 0; i < m; i++) {
                // A column of all zeros carries no information; leave it at zero
                // rather than dividing by zero.
                normalized[i][j] = denominator == 0.0 ? 0.0 : matrix[i][j] / denominator;
            }
        }

        // Step 3: apply weights.
        double[][] weighted = new double[m][n];
        for (int j = 0; j < n; j++) {
            double w = weights.getOrDefault(criteria.get(j), 0.0);
            for (int i = 0; i < m; i++) {
                weighted[i][j] = normalized[i][j] * w;
            }
        }

        // Step 4: ideal best and ideal worst, honouring benefit vs cost.
        double[] idealBest = new double[n];
        double[] idealWorst = new double[n];
        for (int j = 0; j < n; j++) {
            double max = Double.NEGATIVE_INFINITY;
            double min = Double.POSITIVE_INFINITY;
            for (int i = 0; i < m; i++) {
                max = Math.max(max, weighted[i][j]);
                min = Math.min(min, weighted[i][j]);
            }
            if (criteria.get(j).benefit()) {
                idealBest[j] = max;
                idealWorst[j] = min;
            } else {
                idealBest[j] = min;
                idealWorst[j] = max;
            }
        }

        // Steps 5 and 6: separations and closeness.
        List<Scored> scored = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            double separationBest = 0.0;
            double separationWorst = 0.0;
            for (int j = 0; j < n; j++) {
                separationBest += Math.pow(weighted[i][j] - idealBest[j], 2);
                separationWorst += Math.pow(weighted[i][j] - idealWorst[j], 2);
            }
            separationBest = Math.sqrt(separationBest);
            separationWorst = Math.sqrt(separationWorst);

            double denominator = separationBest + separationWorst;
            // Identical alternatives sit at the same point; call that a tie at 50
            // rather than dividing by zero.
            double closeness = denominator == 0.0 ? 0.5 : separationWorst / denominator;

            Map<String, CriterionDetail> breakdown = new LinkedHashMap<>();
            for (int j = 0; j < n; j++) {
                Criterion c = criteria.get(j);
                double range = Math.abs(idealBest[j] - idealWorst[j]);
                double criterionScore = range == 0.0
                        ? 100.0
                        : 100.0 * (1.0 - Math.abs(weighted[i][j] - idealBest[j]) / range);
                breakdown.put(c.key(), CriterionDetail.builder()
                        .criterion(c.key())
                        .label(c.label())
                        .benefit(c.benefit())
                        .rawValue(round(matrix[i][j]))
                        .weight(round(weights.getOrDefault(c, 0.0)))
                        .weightedNormalized(round(weighted[i][j]))
                        .criterionScore(round(criterionScore))
                        .isBest(Math.abs(weighted[i][j] - idealBest[j]) < 1e-12)
                        .build());
            }

            scored.add(Scored.builder()
                    .productId(alternatives.get(i).productId())
                    .label(alternatives.get(i).label())
                    .score(round(closeness * 100.0))
                    .rank(0)
                    .breakdown(breakdown)
                    .build());
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Scored> ranked = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            Scored s = scored.get(i);
            ranked.add(Scored.builder()
                    .productId(s.productId())
                    .label(s.label())
                    .score(s.score())
                    .rank(i + 1)
                    .breakdown(s.breakdown())
                    .build());
        }

        return Result.builder()
                .ranked(ranked)
                .weightsUsed(keyedWeights(weights))
                .note(null)
                .build();
    }

    private static double value(Alternative alternative, Criterion criterion) {
        if (alternative.values() == null) {
            return 0.0;
        }
        Double v = alternative.values().get(criterion);
        return v == null || v.isNaN() ? 0.0 : v;
    }

    /**
     * Scales weights to sum to 1 so callers can pass raw slider positions.
     * Negative weights are clamped away, since a negative weight would silently
     * invert a criterion rather than de-emphasise it.
     */
    private Map<Criterion, Double> normalizeWeights(Map<Criterion, Double> requested) {
        Map<Criterion, Double> weights = new EnumMap<>(Criterion.class);
        double total = 0.0;
        for (Criterion c : Criterion.values()) {
            double w = 0.0;
            if (requested != null && requested.get(c) != null) {
                w = Math.max(0.0, requested.get(c));
            } else if (requested == null || requested.isEmpty()) {
                w = c.defaultWeight();
            }
            weights.put(c, w);
            total += w;
        }
        if (total <= 0.0) {
            return defaultWeights();
        }
        final double sum = total;
        weights.replaceAll((c, w) -> w / sum);
        return weights;
    }

    private static Map<String, Double> keyedWeights(Map<Criterion, Double> weights) {
        Map<String, Double> out = new LinkedHashMap<>();
        weights.forEach((c, w) -> out.put(c.key(), round(w)));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
