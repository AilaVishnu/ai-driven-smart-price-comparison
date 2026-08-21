package com.spc.pricecompare.ai;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Term frequency - inverse document frequency, implemented directly.
 *
 * <p>Written out rather than pulled from a library on purpose: TF-IDF is the
 * core of how this system decides two listings are the same product, and a
 * hundred lines of explicit arithmetic is worth more than an opaque dependency
 * when the behaviour has to be explained and defended.
 *
 * <p>The weighting is the smoothed variant:
 *
 * <pre>
 *   tf(t, d)  = count(t in d) / |d|
 *   idf(t)    = ln((1 + N) / (1 + df(t))) + 1
 *   w(t, d)   = tf(t, d) * idf(t),  then L2-normalised
 * </pre>
 *
 * <p>Smoothing keeps a term that appears in every document from collapsing to
 * zero weight, and L2 normalisation means cosine similarity reduces to a plain
 * dot product, which is what {@link Similarity#cosine} relies on.
 */
public final class TfIdfVectorizer {

    private TfIdfVectorizer() {
    }

    /**
     * Learns document frequencies over a corpus.
     *
     * @param documents one token list per document
     */
    public static Model fit(List<List<String>> documents) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> tokens : documents) {
            // A term counts once per document however often it appears in it.
            Set<String> unique = new HashSet<>(tokens);
            for (String term : unique) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        int total = documents.size();
        Map<String, Double> idf = new HashMap<>(documentFrequency.size());
        for (Map.Entry<String, Integer> e : documentFrequency.entrySet()) {
            idf.put(e.getKey(), Math.log((1.0 + total) / (1.0 + e.getValue())) + 1.0);
        }
        return new Model(idf, total);
    }

    /** A fitted vectorizer: the learned IDF weights, and the transform that uses them. */
    public static final class Model {

        private final Map<String, Double> idf;
        private final int corpusSize;

        Model(Map<String, Double> idf, int corpusSize) {
            this.idf = idf;
            this.corpusSize = corpusSize;
        }

        public int corpusSize() {
            return corpusSize;
        }

        public Map<String, Double> idf() {
            return Map.copyOf(idf);
        }

        /**
         * Projects a token list into L2-normalised TF-IDF space.
         *
         * <p>Terms unseen during fitting get the IDF an unseen term would have
         * had, rather than being dropped - a query word that appears in no
         * indexed title is highly informative, not meaningless.
         */
        public Map<String, Double> vectorize(List<String> tokens) {
            if (tokens == null || tokens.isEmpty()) {
                return Map.of();
            }
            Map<String, Integer> counts = new HashMap<>();
            for (String t : tokens) {
                counts.merge(t, 1, Integer::sum);
            }

            double length = tokens.size();
            double unseenIdf = Math.log((1.0 + corpusSize) / 1.0) + 1.0;

            Map<String, Double> vector = new HashMap<>(counts.size());
            double sumOfSquares = 0.0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                double tf = e.getValue() / length;
                double weight = tf * idf.getOrDefault(e.getKey(), unseenIdf);
                if (weight != 0.0) {
                    vector.put(e.getKey(), weight);
                    sumOfSquares += weight * weight;
                }
            }

            if (sumOfSquares > 0.0) {
                double norm = Math.sqrt(sumOfSquares);
                vector.replaceAll((k, v) -> v / norm);
            }
            return vector;
        }
    }
}
