package com.spc.pricecompare.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Defensive navigation over decoded JSON held as plain Maps and Lists.
 *
 * <p>Two deliberate choices here.
 *
 * <p>First, provider responses are decoded to {@code Map<String,Object>} rather
 * than to Jackson tree nodes, so none of the adapter code is coupled to Jackson
 * 2 vs Jackson 3 package names - a live concern on Spring Boot 4.
 *
 * <p>Second, {@link #firstOf} takes several candidate keys instead of one. The
 * RapidAPI marketplaces document their responses on JavaScript-rendered pages
 * that could not be read without a key, so the exact field names are not known
 * ahead of the first live call. Trying a handful of plausible names means a
 * naming surprise degrades one field to null instead of throwing.
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (o instanceof List<?> l) ? (List<Object>) l : Collections.emptyList();
    }

    /** Walks a nested path, returning null rather than throwing on any miss. */
    public static Object path(Object root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(k);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /** Returns the value of the first key that is present and non-null. */
    public static Object firstOf(Object root, String... keys) {
        if (!(root instanceof Map<?, ?> m)) {
            return null;
        }
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    public static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    public static String strOf(Object root, String... keys) {
        return str(firstOf(root, keys));
    }

    /**
     * Parses money from either a number or a display string.
     *
     * <p>Handles Indian digit grouping, which is the reason a plain
     * {@code new BigDecimal(s)} is not enough: Amazon.in renders prices as
     * "₹1,29,900" (lakh grouping), not "₹129,900".
     */
    public static BigDecimal money(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        // Strip currency symbols, currency words and any grouping separators.
        s = s.replaceAll("(?i)(inr|rs[.]?|usd)", "")
             .replaceAll("[^0-9.-]", "");
        if (s.isEmpty() || ".".equals(s) || "-".equals(s)) {
            return null;
        }
        // A stray trailing dot, or more than one dot, means the string was not a price.
        int firstDot = s.indexOf('.');
        if (firstDot >= 0 && s.indexOf('.', firstDot + 1) >= 0) {
            s = s.substring(0, firstDot);
        }
        try {
            return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal moneyOf(Object root, String... keys) {
        return money(firstOf(root, keys));
    }

    public static BigDecimal decimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            String s = String.valueOf(o).replaceAll("[^0-9.-]", "");
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal decimalOf(Object root, String... keys) {
        return decimal(firstOf(root, keys));
    }

    public static Integer integer(Object o) {
        BigDecimal d = decimal(o);
        return d == null ? null : d.intValue();
    }

    public static Integer integerOf(Object root, String... keys) {
        return integer(firstOf(root, keys));
    }

    public static Boolean bool(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.equals("true") || s.equals("yes") || s.equals("in stock") || s.equals("1")) {
            return Boolean.TRUE;
        }
        if (s.equals("false") || s.equals("no") || s.equals("out of stock") || s.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
