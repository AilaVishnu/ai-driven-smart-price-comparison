package com.spc.pricecompare.ai;

import java.util.Map;

/**
 * The distance measures the matching engine combines.
 *
 * <p>Cosine over TF-IDF handles the bag-of-words view: it says two titles talk
 * about the same things. Jaro-Winkler handles the part cosine is bad at -
 * short model strings like "s24 ultra" against "s23 ultra", where a single
 * character carries the whole distinction and token overlap looks nearly
 * identical. Using both is what stops sibling products being merged.
 */
public final class Similarity {

    private Similarity() {
    }

    /**
     * Cosine similarity between two TF-IDF vectors.
     *
     * <p>{@link TfIdfVectorizer.Model#vectorize} returns L2-normalised vectors,
     * so this is a dot product; the smaller map is iterated to keep it cheap.
     */
    public static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Map<String, Double> smaller = a.size() <= b.size() ? a : b;
        Map<String, Double> larger = smaller == a ? b : a;

        double dot = 0.0;
        for (Map.Entry<String, Double> e : smaller.entrySet()) {
            Double other = larger.get(e.getKey());
            if (other != null) {
                dot += e.getValue() * other;
            }
        }
        // Guard against floating point drift pushing the result just past 1.
        return Math.max(0.0, Math.min(1.0, dot));
    }

    /**
     * Jaro-Winkler similarity in [0, 1].
     *
     * <p>Winkler boosts pairs sharing a prefix, which suits model numbers:
     * "xps 13 9300" and "xps 13 9310" agree from the front and differ at the
     * end, exactly the shape this measure is tuned for.
     */
    public static double jaroWinkler(String s1, String s2) {
        double jaro = jaro(s1, s2);
        if (jaro < 0.7) {
            // Winkler only boosts already-similar strings; below this it would
            // flatter genuinely different ones.
            return jaro;
        }
        int prefix = 0;
        int max = Math.min(4, Math.min(s1.length(), s2.length()));
        while (prefix < max && s1.charAt(prefix) == s2.charAt(prefix)) {
            prefix++;
        }
        return jaro + (0.1 * prefix * (1.0 - jaro));
    }

    public static double jaro(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        if (s1.equals(s2)) {
            return 1.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }

        int window = Math.max(len1, len2) / 2 - 1;
        if (window < 0) {
            window = 0;
        }

        boolean[] matched1 = new boolean[len1];
        boolean[] matched2 = new boolean[len2];

        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - window);
            int end = Math.min(i + window + 1, len2);
            for (int j = start; j < end; j++) {
                if (matched2[j] || s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                matched1[i] = true;
                matched2[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }

        // Count transpositions: matched characters that appear out of order.
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!matched1[i]) {
                continue;
            }
            while (!matched2[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        double half = transpositions / 2.0;
        double m = matches;
        return ((m / len1) + (m / len2) + ((m - half) / m)) / 3.0;
    }

    /** Levenshtein edit distance, used for typo correction on search queries. */
    public static int levenshtein(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return Integer.MAX_VALUE;
        }
        if (s1.equals(s2)) {
            return 0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0) {
            return len2;
        }
        if (len2 == 0) {
            return len1;
        }

        // Two rows are enough; the full matrix is never needed for the distance.
        int[] previous = new int[len2 + 1];
        int[] current = new int[len2 + 1];
        for (int j = 0; j <= len2; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= len1; i++) {
            current[0] = i;
            for (int j = 1; j <= len2; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[len2];
    }
}
