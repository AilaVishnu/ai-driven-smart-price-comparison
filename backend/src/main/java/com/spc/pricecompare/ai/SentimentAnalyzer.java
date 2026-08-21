package com.spc.pricecompare.ai;

import com.spc.pricecompare.domain.SentimentLabel;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lexicon-based sentiment scoring for customer reviews.
 *
 * <p>Three things make this more than a word count.
 *
 * <p><b>Negation.</b> "not good" must not score as positive. A negator flips the
 * polarity of the next few tokens and damps the magnitude, because "not good"
 * is mildly negative rather than as negative as "terrible".
 *
 * <p><b>Intensifiers.</b> "very poor" is worse than "poor"; "slightly loose" is
 * milder than "loose".
 *
 * <p><b>Aspects.</b> A single number for a review is not very actionable. Terms
 * are attributed to whichever aspect - battery, camera, delivery, price - was
 * mentioned nearest, so a product page can say the battery is well liked while
 * delivery is not, which is what a buyer actually wants to know.
 *
 * <p>The lexicon is a project-authored file tuned to e-commerce language, where
 * general-purpose word lists mislead: "cheap" is usually a complaint about
 * quality, and "returned" is a complaint rather than a neutral verb.
 */
@Component
@Slf4j
public class SentimentAnalyzer {

    private static final String LEXICON_PATH = "nlp/product-sentiment-lexicon.txt";

    /** Highest absolute weight in the lexicon; used to normalise into [-1, 1]. */
    private static final double MAX_WEIGHT = 5.0;

    private static final Set<String> NEGATORS = Set.of(
            "not", "no", "never", "none", "cannot", "cant", "dont", "doesnt", "didnt",
            "isnt", "wasnt", "arent", "werent", "wont", "wouldnt", "shouldnt", "couldnt",
            "hardly", "barely", "rarely", "without", "neither", "nor", "lacks", "lacking"
    );

    /** How many tokens after a negator stay flipped. */
    private static final int NEGATION_WINDOW = 3;

    private static final Map<String, Double> INTENSIFIERS = Map.ofEntries(
            Map.entry("very", 1.5),
            Map.entry("extremely", 1.8),
            Map.entry("really", 1.4),
            Map.entry("absolutely", 1.7),
            Map.entry("completely", 1.6),
            Map.entry("totally", 1.5),
            Map.entry("highly", 1.5),
            Map.entry("super", 1.4),
            Map.entry("incredibly", 1.7),
            Map.entry("too", 1.3),
            Map.entry("so", 1.2),
            Map.entry("quite", 1.1),
            Map.entry("slightly", 0.5),
            Map.entry("somewhat", 0.6),
            Map.entry("bit", 0.5),
            Map.entry("little", 0.6),
            Map.entry("fairly", 0.8),
            Map.entry("mostly", 0.9)
    );

    /** Aspect cue words, folded onto the aspects a product page reports on. */
    private static final Map<String, String> ASPECT_CUES = buildAspectCues();

    private final Map<String, Double> lexicon = new HashMap<>();

    @PostConstruct
    void loadLexicon() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(LEXICON_PATH).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                try {
                    lexicon.put(parts[0].toLowerCase(Locale.ROOT), Double.parseDouble(parts[1]));
                } catch (NumberFormatException e) {
                    log.debug("Skipping malformed lexicon line: {}", line);
                }
            }
            log.info("Sentiment lexicon loaded: {} terms", lexicon.size());
        } catch (Exception e) {
            // Sentiment is a feature, not a prerequisite. Degrade to neutral rather
            // than preventing the application from starting.
            log.error("Could not load sentiment lexicon from {}. Sentiment will report NEUTRAL.",
                    LEXICON_PATH, e);
        }
    }

    /** Result of scoring a single review. */
    @Builder
    public record Result(
            double score,
            SentimentLabel label,
            int matchedTerms,
            Map<String, Double> aspectScores
    ) {
        public static Result neutral() {
            return Result.builder()
                    .score(0.0)
                    .label(SentimentLabel.NEUTRAL)
                    .matchedTerms(0)
                    .aspectScores(Map.of())
                    .build();
        }
    }

    /** Aggregate view across every review for a product. */
    @Builder
    public record Summary(
            double averageScore,
            SentimentLabel overallLabel,
            int positiveCount,
            int neutralCount,
            int negativeCount,
            int reviewCount,
            Map<String, AspectSentiment> aspects
    ) {
    }

    @Builder
    public record AspectSentiment(String aspect, double score, int mentions, SentimentLabel label) {
    }

    public Result analyze(String text) {
        if (text == null || text.isBlank() || lexicon.isEmpty()) {
            return Result.neutral();
        }

        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return Result.neutral();
        }

        double total = 0.0;
        int matched = 0;
        Map<String, Double> aspectTotals = new LinkedHashMap<>();
        Map<String, Integer> aspectCounts = new HashMap<>();

        int negateUntil = -1;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);

            if (NEGATORS.contains(token)) {
                negateUntil = i + NEGATION_WINDOW;
                continue;
            }

            Double weight = lexicon.get(token);
            if (weight == null) {
                continue;
            }

            // An intensifier immediately before the term scales it.
            double multiplier = 1.0;
            if (i > 0) {
                Double intensity = INTENSIFIERS.get(tokens.get(i - 1));
                if (intensity != null) {
                    multiplier = intensity;
                }
            }

            double value = weight * multiplier;

            if (i <= negateUntil) {
                // Flipped and damped: "not good" is mildly negative, not as
                // negative as "terrible" is.
                value = -value * 0.75;
            }

            total += value;
            matched++;

            String aspect = nearestAspect(tokens, i);
            if (aspect != null) {
                aspectTotals.merge(aspect, value, Double::sum);
                aspectCounts.merge(aspect, 1, Integer::sum);
            }
        }

        if (matched == 0) {
            return Result.neutral();
        }

        // Mean weight of the sentiment-bearing terms, scaled into [-1, 1]. Using
        // the mean rather than the sum keeps a long review from dominating a
        // short one purely by length.
        double score = clamp((total / matched) / MAX_WEIGHT);

        Map<String, Double> aspectScores = new LinkedHashMap<>();
        aspectTotals.forEach((aspect, sum) ->
                aspectScores.put(aspect, clamp((sum / aspectCounts.get(aspect)) / MAX_WEIGHT)));

        return Result.builder()
                .score(score)
                .label(label(score))
                .matchedTerms(matched)
                .aspectScores(aspectScores)
                .build();
    }

    /** Aggregates many reviews into the view a product page shows. */
    public Summary summarize(List<String> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return Summary.builder()
                    .averageScore(0.0)
                    .overallLabel(SentimentLabel.NEUTRAL)
                    .positiveCount(0).neutralCount(0).negativeCount(0).reviewCount(0)
                    .aspects(Map.of())
                    .build();
        }

        double total = 0.0;
        int positive = 0;
        int neutral = 0;
        int negative = 0;
        Map<String, Double> aspectTotals = new LinkedHashMap<>();
        Map<String, Integer> aspectCounts = new HashMap<>();

        for (String review : reviews) {
            Result result = analyze(review);
            total += result.score();
            switch (result.label()) {
                case POSITIVE -> positive++;
                case NEGATIVE -> negative++;
                case NEUTRAL -> neutral++;
            }
            result.aspectScores().forEach((aspect, score) -> {
                aspectTotals.merge(aspect, score, Double::sum);
                aspectCounts.merge(aspect, 1, Integer::sum);
            });
        }

        double average = total / reviews.size();

        Map<String, AspectSentiment> aspects = new LinkedHashMap<>();
        aspectTotals.forEach((aspect, sum) -> {
            int mentions = aspectCounts.get(aspect);
            double score = sum / mentions;
            aspects.put(aspect, AspectSentiment.builder()
                    .aspect(aspect)
                    .score(round(score))
                    .mentions(mentions)
                    .label(label(score))
                    .build());
        });

        return Summary.builder()
                .averageScore(round(average))
                .overallLabel(label(average))
                .positiveCount(positive)
                .neutralCount(neutral)
                .negativeCount(negative)
                .reviewCount(reviews.size())
                .aspects(aspects)
                .build();
    }

    public SentimentLabel label(double score) {
        if (score > 0.15) {
            return SentimentLabel.POSITIVE;
        }
        if (score < -0.15) {
            return SentimentLabel.NEGATIVE;
        }
        return SentimentLabel.NEUTRAL;
    }

    /**
     * Attributes a sentiment term to the aspect mentioned closest to it, within
     * a small window. Beyond that the association is guesswork, so the term
     * counts only towards the overall score.
     */
    private String nearestAspect(List<String> tokens, int index) {
        int window = 5;
        int start = Math.max(0, index - window);
        int end = Math.min(tokens.size(), index + window + 1);

        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = start; i < end; i++) {
            String aspect = ASPECT_CUES.get(tokens.get(i));
            if (aspect == null) {
                continue;
            }
            int distance = Math.abs(i - index);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = aspect;
            }
        }
        return best;
    }

    private static List<String> tokenize(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
        List<String> tokens = new ArrayList<>();
        for (String t : cleaned.split("\\s+")) {
            if (!t.isBlank()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private static double clamp(double v) {
        return round(Math.max(-1.0, Math.min(1.0, v)));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static Map<String, String> buildAspectCues() {
        Map<String, String> cues = new HashMap<>();
        addCues(cues, "battery", "battery", "charge", "charging", "backup", "mah", "drain", "drains");
        addCues(cues, "camera", "camera", "photo", "photos", "picture", "pictures", "lens", "selfie", "zoom");
        addCues(cues, "display", "screen", "display", "resolution", "brightness", "panel", "touch", "colours", "colors");
        addCues(cues, "performance", "performance", "processor", "ram", "speed", "lag", "gaming", "multitasking", "hangs");
        addCues(cues, "sound", "sound", "audio", "speaker", "speakers", "bass", "volume", "mic", "microphone");
        addCues(cues, "build", "build", "material", "plastic", "metal", "finish", "design", "look", "looks", "body");
        addCues(cues, "price", "price", "value", "cost", "money", "worth", "expensive", "cheap", "budget", "priced");
        addCues(cues, "delivery", "delivery", "shipping", "packaging", "package", "packed", "arrived", "courier", "delivered");
        addCues(cues, "service", "service", "support", "warranty", "replacement", "seller", "customer", "refund");
        addCues(cues, "storage", "storage", "memory", "space", "capacity");
        return Map.copyOf(cues);
    }

    private static void addCues(Map<String, String> map, String aspect, String... cues) {
        for (String cue : cues) {
            map.put(cue, aspect);
        }
    }
}
