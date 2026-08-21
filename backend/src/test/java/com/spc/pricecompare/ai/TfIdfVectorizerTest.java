package com.spc.pricecompare.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the TF-IDF arithmetic against values worked out by hand, so a
 * regression in the weighting shows up here rather than as mysteriously worse
 * matching further downstream.
 */
class TfIdfVectorizerTest {

    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("IDF matches the smoothed formula computed by hand")
    void idfMatchesHandComputedValues() {
        // Three documents. "apple" appears in all three, "dell" in one.
        List<List<String>> corpus = List.of(
                List.of("apple", "iphone"),
                List.of("apple", "ipad"),
                List.of("apple", "dell"));

        TfIdfVectorizer.Model model = TfIdfVectorizer.fit(corpus);
        Map<String, Double> idf = model.idf();

        // idf = ln((1 + N) / (1 + df)) + 1, with N = 3
        double expectedApple = Math.log(4.0 / 4.0) + 1.0;   // df = 3 -> exactly 1.0
        double expectedDell = Math.log(4.0 / 2.0) + 1.0;    // df = 1 -> ln(2) + 1

        assertEquals(expectedApple, idf.get("apple"), EPSILON);
        assertEquals(1.0, idf.get("apple"), EPSILON);
        assertEquals(expectedDell, idf.get("dell"), EPSILON);
        assertTrue(idf.get("dell") > idf.get("apple"),
                "A term in one document must outweigh a term in every document");
    }

    @Test
    @DisplayName("Vectors are L2-normalised, so cosine reduces to a dot product")
    void vectorsAreL2Normalised() {
        List<List<String>> corpus = List.of(
                List.of("apple", "iphone", "15", "pro"),
                List.of("samsung", "galaxy", "s24", "ultra"));

        TfIdfVectorizer.Model model = TfIdfVectorizer.fit(corpus);
        Map<String, Double> vector = model.vectorize(corpus.get(0));

        double sumOfSquares = vector.values().stream().mapToDouble(v -> v * v).sum();
        assertEquals(1.0, sumOfSquares, 1e-9, "An L2-normalised vector has unit length");
    }

    @Test
    @DisplayName("A document is perfectly similar to itself and orthogonal to a disjoint one")
    void cosineEndpointsBehave() {
        List<List<String>> corpus = List.of(
                List.of("apple", "iphone", "15", "pro"),
                List.of("samsung", "galaxy", "s24", "ultra"));

        TfIdfVectorizer.Model model = TfIdfVectorizer.fit(corpus);
        Map<String, Double> a = model.vectorize(corpus.get(0));
        Map<String, Double> b = model.vectorize(corpus.get(1));

        assertEquals(1.0, Similarity.cosine(a, a), 1e-9, "Identical documents score 1");
        assertEquals(0.0, Similarity.cosine(a, b), 1e-9, "Documents sharing no terms score 0");
    }

    @Test
    @DisplayName("Repeated terms raise term frequency")
    void repeatedTermsWeighMore() {
        List<List<String>> corpus = List.of(
                List.of("apple", "apple", "case"),
                List.of("samsung", "case"));

        TfIdfVectorizer.Model model = TfIdfVectorizer.fit(corpus);
        Map<String, Double> vector = model.vectorize(corpus.get(0));

        assertTrue(vector.get("apple") > vector.get("case"),
                "A term used twice should outweigh one used once at equal IDF");
    }

    @Test
    @DisplayName("An unseen term is treated as highly informative, not discarded")
    void unseenTermsGetMaximumIdf() {
        TfIdfVectorizer.Model model = TfIdfVectorizer.fit(List.of(
                List.of("apple", "iphone"),
                List.of("apple", "ipad")));

        Map<String, Double> vector = model.vectorize(List.of("apple", "quantumcomputer"));

        assertTrue(vector.containsKey("quantumcomputer"), "An unseen term must not be dropped");
        assertTrue(vector.get("quantumcomputer") > vector.get("apple"),
                "A term seen in no indexed title is more informative, not less");
    }

    @Test
    @DisplayName("Empty input yields an empty vector rather than throwing")
    void handlesEmptyInput() {
        TfIdfVectorizer.Model model = TfIdfVectorizer.fit(List.of(List.of("apple")));
        assertTrue(model.vectorize(List.of()).isEmpty());
        assertTrue(model.vectorize(null).isEmpty());
    }
}
