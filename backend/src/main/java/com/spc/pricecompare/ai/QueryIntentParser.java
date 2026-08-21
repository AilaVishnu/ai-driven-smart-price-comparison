package com.spc.pricecompare.ai;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a typed phrase into structured search filters.
 *
 * <p>"gaming laptop under 60k from dell" becomes a budget ceiling of 60,000, a
 * brand of Dell, a category of laptops and a residual search term of "gaming" -
 * without an LLM, and therefore without a network call, an API key, or any
 * chance of the feature failing during a demo. Every rule here can be pointed
 * at and explained, which matters more for this project than raw flexibility.
 *
 * <p>Indian money vocabulary is handled directly, since that is how the target
 * users write: "60k", "1.5 lakh", "2 cr" all parse.
 */
@Service
@RequiredArgsConstructor
public class QueryIntentParser {

    private final TextNormalizer normalizer;

    private static final Pattern MAX_PRICE = Pattern.compile(
            "(?:under|below|less\\s+than|upto|up\\s+to|within|max|maximum|cheaper\\s+than|budget\\s+of)"
                    + "\\s*(?:rs[.]?|inr|₹)?\\s*([0-9][0-9,.]*)\\s*(k|thousand|lakh|lac|l|crore|cr)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MIN_PRICE = Pattern.compile(
            "(?:above|over|more\\s+than|at\\s+least|minimum|min|starting\\s+(?:at|from))"
                    + "\\s*(?:rs[.]?|inr|₹)?\\s*([0-9][0-9,.]*)\\s*(k|thousand|lakh|lac|l|crore|cr)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PRICE_RANGE = Pattern.compile(
            "(?:between|from)\\s*(?:rs[.]?|inr|₹)?\\s*([0-9][0-9,.]*)\\s*(k|thousand|lakh|lac|l|crore|cr)?"
                    + "\\s*(?:and|to|-)\\s*(?:rs[.]?|inr|₹)?\\s*([0-9][0-9,.]*)\\s*(k|thousand|lakh|lac|l|crore|cr)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MIN_RATING = Pattern.compile(
            "([0-9](?:[.][0-9])?)\\s*(?:\\+|star|stars)\\s*(?:and\\s+above|or\\s+above|plus|and\\s+up)?"
                    + "|([0-9](?:[.][0-9])?)\\s*(?:\\+)\\s*rating",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern BRAND_PREFIX = Pattern.compile(
            "(?:from|by|made\\s+by|brand)\\s+([a-z]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IN_STOCK = Pattern.compile(
            "\\b(?:in\\s*stock|available)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DISCOUNTED = Pattern.compile(
            "\\b(?:on\\s+(?:sale|offer|discount)|discounted|deals?)\\b", Pattern.CASE_INSENSITIVE);

    /** Category cue words folded onto the taxonomy in the categories table. */
    private static final Map<String, String> CATEGORY_CUES = buildCategoryCues();

    @Builder
    public record ParsedQuery(
            String originalQuery,
            /** What remains once recognised filters are removed; used for text search. */
            String searchTerms,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String brand,
            String category,
            BigDecimal minRating,
            Boolean inStockOnly,
            Boolean discountedOnly,
            /** Human-readable chips the interface shows, so the parse is visible and correctable. */
            List<String> interpretedAs
    ) {
    }

    public ParsedQuery parse(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return ParsedQuery.builder()
                    .originalQuery(rawQuery)
                    .searchTerms("")
                    .interpretedAs(List.of())
                    .build();
        }

        String working = rawQuery.toLowerCase(Locale.ROOT).trim();
        List<String> chips = new ArrayList<>();

        BigDecimal minPrice = null;
        BigDecimal maxPrice = null;

        // A range is checked first: "between 20k and 40k" also matches the
        // narrower minimum pattern, and the range reading is the right one.
        Matcher range = PRICE_RANGE.matcher(working);
        if (range.find()) {
            minPrice = toRupees(range.group(1), range.group(2));
            maxPrice = toRupees(range.group(3), range.group(4));
            if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
                BigDecimal swap = minPrice;
                minPrice = maxPrice;
                maxPrice = swap;
            }
            chips.add("Price " + format(minPrice) + " to " + format(maxPrice));
            working = remove(working, range.group());
        } else {
            Matcher max = MAX_PRICE.matcher(working);
            if (max.find()) {
                maxPrice = toRupees(max.group(1), max.group(2));
                if (maxPrice != null) {
                    chips.add("Budget up to " + format(maxPrice));
                    working = remove(working, max.group());
                }
            }
            Matcher min = MIN_PRICE.matcher(working);
            if (min.find()) {
                minPrice = toRupees(min.group(1), min.group(2));
                if (minPrice != null) {
                    chips.add("At least " + format(minPrice));
                    working = remove(working, min.group());
                }
            }
        }

        BigDecimal minRating = null;
        Matcher rating = MIN_RATING.matcher(working);
        if (rating.find()) {
            String value = rating.group(1) != null ? rating.group(1) : rating.group(2);
            try {
                BigDecimal parsed = new BigDecimal(value);
                if (parsed.compareTo(BigDecimal.ZERO) > 0 && parsed.compareTo(new BigDecimal("5")) <= 0) {
                    minRating = parsed;
                    chips.add(parsed + " stars and above");
                    working = remove(working, rating.group());
                }
            } catch (NumberFormatException ignored) {
                // Not a usable rating; leave the words in the search terms.
            }
        }

        String brand = null;
        Matcher brandPrefix = BRAND_PREFIX.matcher(working);
        if (brandPrefix.find()) {
            String candidate = resolveBrand(brandPrefix.group(1));
            if (candidate != null) {
                brand = candidate;
                chips.add("Brand: " + candidate);
                working = remove(working, brandPrefix.group());
            }
        }
        if (brand == null) {
            for (String token : working.split("\\s+")) {
                String candidate = resolveBrand(token);
                if (candidate != null) {
                    brand = candidate;
                    chips.add("Brand: " + candidate);
                    working = remove(working, token);
                    break;
                }
            }
        }

        String category = null;
        for (Map.Entry<String, String> cue : CATEGORY_CUES.entrySet()) {
            if (containsWord(working, cue.getKey())) {
                category = cue.getValue();
                chips.add("Category: " + category);
                // The cue word is left in the search terms on purpose: "laptop"
                // is still a useful text match, not only a filter.
                break;
            }
        }

        Boolean inStockOnly = null;
        Matcher stock = IN_STOCK.matcher(working);
        if (stock.find()) {
            inStockOnly = true;
            chips.add("In stock only");
            working = remove(working, stock.group());
        }

        Boolean discountedOnly = null;
        Matcher discount = DISCOUNTED.matcher(working);
        if (discount.find()) {
            discountedOnly = true;
            chips.add("Discounted only");
            working = remove(working, discount.group());
        }

        String searchTerms = working
                .replaceAll("\\b(?:rs[.]?|inr|₹)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return ParsedQuery.builder()
                .originalQuery(rawQuery)
                .searchTerms(searchTerms)
                .minPrice(minPrice)
                .maxPrice(maxPrice)
                .brand(brand)
                .category(category)
                .minRating(minRating)
                .inStockOnly(inStockOnly)
                .discountedOnly(discountedOnly)
                .interpretedAs(chips)
                .build();
    }

    /**
     * Resolves a token to a known brand, tolerating a typo or two.
     *
     * <p>Only words of four characters or more are fuzzy-matched: at three
     * characters an edit distance of one matches almost anything, which would
     * turn "the" into a brand.
     */
    private String resolveBrand(String token) {
        if (token == null || token.length() < 2) {
            return null;
        }
        String candidate = token.toLowerCase(Locale.ROOT).trim();
        for (String brand : normalizer.knownBrands()) {
            if (brand.equals(candidate)) {
                return normalizer.displayBrand(brand);
            }
        }
        if (candidate.length() < 4) {
            return null;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int allowed = candidate.length() >= 7 ? 2 : 1;
        for (String brand : normalizer.knownBrands()) {
            if (brand.contains(" ")) {
                continue;
            }
            int distance = Similarity.levenshtein(candidate, brand);
            if (distance <= allowed && distance < bestDistance) {
                bestDistance = distance;
                best = brand;
            }
        }
        return best == null ? null : normalizer.displayBrand(best);
    }

    /**
     * Converts an Indian-style money phrase to rupees. "60k" is 60,000;
     * "1.5 lakh" is 150,000; "2 cr" is 20,000,000.
     */
    private static BigDecimal toRupees(String number, String magnitude) {
        if (number == null) {
            return null;
        }
        String cleaned = number.replace(",", "");
        BigDecimal value;
        try {
            value = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
        if (magnitude == null || magnitude.isBlank()) {
            return value;
        }
        return switch (magnitude.toLowerCase(Locale.ROOT)) {
            case "k", "thousand" -> value.multiply(BigDecimal.valueOf(1_000));
            case "lakh", "lac", "l" -> value.multiply(BigDecimal.valueOf(100_000));
            case "crore", "cr" -> value.multiply(BigDecimal.valueOf(10_000_000));
            default -> value;
        };
    }

    private static boolean containsWord(String haystack, String word) {
        return (" " + haystack + " ").contains(" " + word + " ")
                || (" " + haystack + " ").contains(" " + word + "s ");
    }

    private static String remove(String source, String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return source;
        }
        return source.replace(fragment, " ").replaceAll("\\s+", " ").trim();
    }

    private static String format(BigDecimal value) {
        return value == null ? "any" : "Rs " + value.stripTrailingZeros().toPlainString();
    }

    private static Map<String, String> buildCategoryCues() {
        // LinkedHashMap: more specific cues are checked before broader ones.
        Map<String, String> cues = new LinkedHashMap<>();
        cues.put("smartphone", "smartphones");
        cues.put("mobile", "smartphones");
        cues.put("phone", "smartphones");
        cues.put("laptop", "laptops");
        cues.put("notebook", "laptops");
        cues.put("tablet", "tablets");
        cues.put("ipad", "tablets");
        cues.put("headphone", "headphones");
        cues.put("earphone", "headphones");
        cues.put("earbud", "headphones");
        cues.put("headset", "headphones");
        cues.put("smartwatch", "smartwatches");
        cues.put("watch", "smartwatches");
        cues.put("camera", "cameras");
        cues.put("television", "televisions");
        cues.put("tv", "televisions");
        cues.put("monitor", "monitors");
        cues.put("ssd", "storage");
        cues.put("hard", "storage");
        cues.put("pendrive", "storage");
        cues.put("shoe", "footwear");
        cues.put("sneaker", "footwear");
        return cues;
    }
}
