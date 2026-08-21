package com.spc.pricecompare.provider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns free-text shipping copy into a number of days, so delivery speed can be
 * scored as a TOPSIS criterion.
 *
 * <p>Platforms phrase this every way imaginable ("Ships in 3-5 business days",
 * "Delivery by Tomorrow", "1 month"). Where a range is given the slower end is
 * taken, since that is what a buyer should actually plan around.
 */
public final class DeliveryParser {

    private static final Pattern RANGE = Pattern.compile("([0-9]+)\\s*(?:-|to)\\s*([0-9]+)");
    private static final Pattern SINGLE = Pattern.compile("([0-9]+)");

    private DeliveryParser() {
    }

    public static Integer parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.toLowerCase();

        if (s.contains("today") || s.contains("same day")) {
            return 0;
        }
        if (s.contains("tomorrow") || s.contains("next day")) {
            return 1;
        }

        int multiplier = s.contains("month") ? 30 : s.contains("week") ? 7 : 1;

        Matcher range = RANGE.matcher(s);
        if (range.find()) {
            // Slower end of the range: what the buyer should plan around.
            return Integer.parseInt(range.group(2)) * multiplier;
        }
        Matcher single = SINGLE.matcher(s);
        if (single.find()) {
            return Integer.parseInt(single.group(1)) * multiplier;
        }
        return null;
    }
}
