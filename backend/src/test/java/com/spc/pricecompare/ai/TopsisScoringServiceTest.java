package com.spc.pricecompare.ai;

import com.spc.pricecompare.ai.TopsisScoringService.Alternative;
import com.spc.pricecompare.ai.TopsisScoringService.Criterion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopsisScoringServiceTest {

    private final TopsisScoringService service = new TopsisScoringService();

    private static Alternative alternative(long id, String label, double price, double rating,
                                           double ratingCount, double discount,
                                           double sentiment, double delivery, double availability) {
        Map<Criterion, Double> values = new EnumMap<>(Criterion.class);
        values.put(Criterion.PRICE, price);
        values.put(Criterion.RATING, rating);
        values.put(Criterion.RATING_COUNT, ratingCount);
        values.put(Criterion.DISCOUNT, discount);
        values.put(Criterion.SENTIMENT, sentiment);
        values.put(Criterion.DELIVERY, delivery);
        values.put(Criterion.AVAILABILITY, availability);
        return Alternative.builder().productId(id).label(label).values(values).build();
    }

    @Test
    @DisplayName("An alternative that is best on every criterion must rank first")
    void dominantAlternativeWins() {
        // B is cheaper, better rated, more reviewed, more discounted, better
        // regarded and faster to arrive. No weighting should rank it second.
        List<Alternative> alternatives = List.of(
                alternative(1, "A", 60000, 4.0, 500, 5, 0.2, 5, 1),
                alternative(2, "B", 50000, 4.6, 900, 15, 0.6, 2, 1),
                alternative(3, "C", 70000, 3.8, 200, 2, 0.1, 7, 1));

        TopsisScoringService.Result result = service.rank(alternatives);

        assertEquals(2L, result.ranked().get(0).productId(), "The dominant option must win");
        assertEquals(1, result.ranked().get(0).rank());
        assertEquals(3, result.ranked().size());
    }

    @Test
    @DisplayName("Weighting price alone puts the cheapest first")
    void priceOnlyWeightingPicksCheapest() {
        List<Alternative> alternatives = List.of(
                alternative(1, "Premium", 90000, 4.9, 5000, 5, 0.9, 1, 1),
                alternative(2, "Budget", 30000, 3.2, 50, 0, 0.1, 9, 1));

        Map<Criterion, Double> weights = new EnumMap<>(Criterion.class);
        weights.put(Criterion.PRICE, 1.0);

        TopsisScoringService.Result result = service.rank(alternatives, weights);

        assertEquals(2L, result.ranked().get(0).productId(),
                "With only price weighted, the cheapest option wins regardless of quality");
    }

    @Test
    @DisplayName("Shifting weight to rating reverses that ranking")
    void ratingWeightingReversesTheOutcome() {
        List<Alternative> alternatives = List.of(
                alternative(1, "Premium", 90000, 4.9, 5000, 5, 0.9, 1, 1),
                alternative(2, "Budget", 30000, 3.2, 50, 0, 0.1, 9, 1));

        Map<Criterion, Double> weights = new EnumMap<>(Criterion.class);
        weights.put(Criterion.RATING, 1.0);

        TopsisScoringService.Result result = service.rank(alternatives, weights);

        assertEquals(1L, result.ranked().get(0).productId(),
                "This is the whole point of the weight sliders: preferences change the answer");
    }

    @Test
    @DisplayName("Scores are reported on a 0 to 100 scale")
    void scoresAreBounded() {
        List<Alternative> alternatives = List.of(
                alternative(1, "A", 60000, 4.0, 500, 5, 0.2, 5, 1),
                alternative(2, "B", 50000, 4.6, 900, 15, 0.6, 2, 1));

        for (TopsisScoringService.Scored scored : service.rank(alternatives).ranked()) {
            assertTrue(scored.score() >= 0.0 && scored.score() <= 100.0,
                    "Closeness coefficient scaled to 0-100, got " + scored.score());
        }
    }

    @Test
    @DisplayName("Weights are normalised, so slider positions can be passed raw")
    void weightsAreNormalised() {
        List<Alternative> alternatives = List.of(
                alternative(1, "A", 60000, 4.0, 500, 5, 0.2, 5, 1),
                alternative(2, "B", 50000, 4.6, 900, 15, 0.6, 2, 1));

        Map<Criterion, Double> unnormalised = new EnumMap<>(Criterion.class);
        unnormalised.put(Criterion.PRICE, 60.0);
        unnormalised.put(Criterion.RATING, 40.0);

        TopsisScoringService.Result result = service.rank(alternatives, unnormalised);

        double sum = result.weightsUsed().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-6, "Weights should be rescaled to sum to one");
        assertEquals(0.6, result.weightsUsed().get("price"), 1e-6);
    }

    @Test
    @DisplayName("Every score carries a per-criterion breakdown")
    void breakdownExplainsTheScore() {
        List<Alternative> alternatives = List.of(
                alternative(1, "A", 60000, 4.0, 500, 5, 0.2, 5, 1),
                alternative(2, "B", 50000, 4.6, 900, 15, 0.6, 2, 1));

        TopsisScoringService.Scored winner = service.rank(alternatives).ranked().get(0);

        assertEquals(Criterion.values().length, winner.breakdown().size(),
                "Each criterion should be accounted for");
        TopsisScoringService.CriterionDetail price = winner.breakdown().get("price");
        assertNotNull(price);
        assertTrue(price.isBest(), "B is the cheaper option and should be flagged best on price");
        assertEquals(50000.0, price.rawValue(), 1e-6, "The raw value should be reported, not just the weighted one");
    }

    @Test
    @DisplayName("A single alternative is reported as such rather than scored against itself")
    void singleAlternativeIsHandledHonestly() {
        TopsisScoringService.Result result = service.rank(List.of(
                alternative(1, "Only", 50000, 4.5, 100, 10, 0.5, 3, 1)));

        assertEquals(1, result.ranked().size());
        assertNotNull(result.note(), "The degenerate case should be explained, not silently scored");
    }

    @Test
    @DisplayName("Identical alternatives tie rather than dividing by zero")
    void identicalAlternativesTie() {
        List<Alternative> alternatives = List.of(
                alternative(1, "A", 50000, 4.5, 100, 10, 0.5, 3, 1),
                alternative(2, "B", 50000, 4.5, 100, 10, 0.5, 3, 1));

        TopsisScoringService.Result result = service.rank(alternatives);

        assertEquals(result.ranked().get(0).score(), result.ranked().get(1).score(), 1e-9);
    }

    @Test
    @DisplayName("An empty input yields an empty ranking rather than an exception")
    void handlesEmptyInput() {
        assertTrue(service.rank(List.of()).ranked().isEmpty());
        assertTrue(service.rank(null).ranked().isEmpty());
    }
}
