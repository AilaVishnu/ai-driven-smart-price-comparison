package com.spc.pricecompare.ai;

import com.spc.pricecompare.domain.SentimentLabel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentimentAnalyzerTest {

    private SentimentAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SentimentAnalyzer();
        // Stands in for the @PostConstruct callback Spring would make.
        analyzer.loadLexicon();
    }

    @Test
    @DisplayName("Clearly positive and clearly negative reviews are classified correctly")
    void classifiesObviousReviews() {
        assertEquals(SentimentLabel.POSITIVE,
                analyzer.analyze("Absolutely excellent phone, the camera is amazing").label());
        assertEquals(SentimentLabel.NEGATIVE,
                analyzer.analyze("Terrible product, arrived broken and completely useless").label());
    }

    @Test
    @DisplayName("Negation flips polarity rather than being ignored")
    void handlesNegation() {
        double positive = analyzer.analyze("the battery is good").score();
        double negated = analyzer.analyze("the battery is not good").score();

        assertTrue(positive > 0, "good should read positive");
        assertTrue(negated < 0, "not good must not read positive");
    }

    @Test
    @DisplayName("Negation is damped, so not good is milder than terrible")
    void negationIsDamped() {
        double notGood = analyzer.analyze("this is not good").score();
        double terrible = analyzer.analyze("this is terrible").score();

        assertTrue(notGood > terrible,
                "not good is a mild complaint; terrible is an emphatic one");
        assertTrue(notGood < 0);
    }

    @Test
    @DisplayName("Intensifiers scale the magnitude in both directions")
    void handlesIntensifiers() {
        double plain = Math.abs(analyzer.analyze("the screen is poor").score());
        double amplified = Math.abs(analyzer.analyze("the screen is very poor").score());
        double softened = Math.abs(analyzer.analyze("the screen is slightly poor").score());

        assertTrue(amplified > plain, "very should strengthen the term");
        assertTrue(softened < plain, "slightly should weaken it");
    }

    @Test
    @DisplayName("A review with no sentiment words is neutral, not positive by default")
    void neutralWhenNoSentimentWords() {
        SentimentAnalyzer.Result result = analyzer.analyze("Received the item on Tuesday in a box");
        assertEquals(SentimentLabel.NEUTRAL, result.label());
        assertEquals(0, result.matchedTerms());
    }

    @Test
    @DisplayName("Sentiment is attributed to the aspect it was written about")
    void attributesSentimentToAspects() {
        SentimentAnalyzer.Result result =
                analyzer.analyze("The battery is excellent but the delivery was terrible and late");

        assertTrue(result.aspectScores().containsKey("battery"), "battery should be identified");
        assertTrue(result.aspectScores().containsKey("delivery"), "delivery should be identified");
        assertTrue(result.aspectScores().get("battery") > 0, "battery was praised");
        assertTrue(result.aspectScores().get("delivery") < 0, "delivery was criticised");
    }

    @Test
    @DisplayName("A summary aggregates counts and per-aspect verdicts across reviews")
    void summarisesAcrossReviews() {
        SentimentAnalyzer.Summary summary = analyzer.summarize(List.of(
                "Excellent phone, battery is great",
                "Superb camera and a very nice display",
                "Terrible experience, the product arrived damaged",
                "Received it on Wednesday"));

        assertEquals(4, summary.reviewCount());
        assertEquals(2, summary.positiveCount());
        assertEquals(1, summary.negativeCount());
        assertEquals(1, summary.neutralCount());
        assertEquals(SentimentLabel.POSITIVE, summary.overallLabel(),
                "Two strong positives against one negative should read positive overall");
    }

    @Test
    @DisplayName("Score length-normalises, so padding a review does not inflate it")
    void scoreIsLengthNormalised() {
        double shortReview = analyzer.analyze("excellent").score();
        double paddedReview = analyzer.analyze(
                "excellent and the box was received on tuesday by the courier at the address").score();

        assertTrue(Math.abs(shortReview - paddedReview) < 0.01,
                "Neutral padding should not change the strength of the verdict");
    }

    @Test
    @DisplayName("Null and empty input are handled without throwing")
    void handlesEmptyInput() {
        assertEquals(SentimentLabel.NEUTRAL, analyzer.analyze(null).label());
        assertEquals(SentimentLabel.NEUTRAL, analyzer.analyze("   ").label());
        assertEquals(0, analyzer.summarize(List.of()).reviewCount());
    }
}
