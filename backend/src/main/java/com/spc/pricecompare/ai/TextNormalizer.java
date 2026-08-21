package com.spc.pricecompare.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a marketplace product title into something comparable.
 *
 * <p>This is the unglamorous foundation the whole matching engine rests on.
 * Amazon and Flipkart describe the same phone very differently:
 *
 * <pre>
 *   Amazon.in : "Apple iPhone 15 Pro (128 GB) - Blue Titanium | 5G"
 *   Flipkart  : "APPLE iPhone 15 Pro (Blue Titanium, 128GB Storage)"
 * </pre>
 *
 * <p>Both must reduce to the same token bag before TF-IDF can see them as the
 * same product. That means stripping marketing noise, folding punctuation,
 * standardising units so "128GB" and "128 GB" agree, and dropping the filler
 * words that appear in every listing and therefore carry no signal.
 */
@Component
public class TextNormalizer {

    /**
     * Marketing and logistics copy that says nothing about which product this
     * is. Removed before tokenising so it cannot inflate similarity between two
     * unrelated listings that happen to share boilerplate.
     */
    private static final List<Pattern> NOISE = List.of(
            Pattern.compile("\\((?:renewed|refurbished|pre-owned|open box)\\)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwith\\s+(?:offers?|exchange|no\\s+cost\\s+emi)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfree\\s+(?:delivery|shipping)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:buy|shop)\\s+online\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\blowest\\s+price\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b\\d+\\s*(?:year|yr|month)s?\\s+warranty\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bpack\\s+of\\s+\\d+\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bcombo\\s+offer\\b", Pattern.CASE_INSENSITIVE)
    );

    /**
     * Words so common across listings that they carry no discriminating signal.
     * Deliberately short: over-aggressive stopwording destroys model names.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "for", "with", "of", "in", "on", "at", "to",
            "by", "from", "new", "latest", "original", "genuine", "official", "best",
            "top", "premium", "quality", "brand", "sale", "offer", "deal", "online",
            "buy", "get", "set", "item", "product", "color", "colour", "size"
    );

    /**
     * Brand lexicon skewed to what actually sells on Indian marketplaces.
     * Longest match wins, so "western digital" beats a stray "digital".
     */
    private static final List<String> BRANDS = List.of(
            "western digital", "fire-boltt", "fire boltt", "steelseries", "sennheiser",
            "apple", "samsung", "oneplus", "xiaomi", "redmi", "poco", "realme", "vivo",
            "oppo", "motorola", "nokia", "asus", "dell", "lenovo", "acer", "msi",
            "sony", "bose", "jbl", "boat", "noise", "canon", "nikon", "gopro",
            "infinix", "tecno", "iqoo", "nothing", "google", "honor", "huawei",
            "micromax", "lava", "panasonic", "philips", "whirlpool", "godrej", "haier",
            "tcl", "hisense", "seagate", "sandisk", "kingston", "crucial", "corsair",
            "logitech", "razer", "titan", "fastrack", "casio", "fossil", "puma",
            "nike", "adidas", "reebok", "microsoft", "intel", "amd", "nvidia",
            "toshiba", "lg", "hp", "mi"
    );

    /**
     * Units whose spacing varies between platforms and must be regularised.
     *
     * <p>Bare "g" and "k" are deliberately excluded. Including them made the
     * rule split "5G" into "5" + "g" and "4K" into "4" + "k", which the
     * signature logic then read as storage capacities - so a listing saying
     * "OnePlus 12 5G" looked like a different capacity variant from one saying
     * "OnePlus 12". Grams and thousands are not a signal worth that.
     */
    private static final Pattern UNIT_SPACING =
            Pattern.compile("(\\d+)\\s*(gb|tb|mb|kb|mah|mp|ghz|mhz|hz|inch|cm|mm|kg|w)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern TB_CAPACITY =
            Pattern.compile("(\\d+)\\s*tb\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern MULTISPACE = Pattern.compile("\\s+");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*[0-9].*");

    /** Model qualifiers that distinguish variants and must survive normalisation. */
    private static final Set<String> MODEL_WORDS = Set.of(
            "pro", "max", "plus", "ultra", "mini", "air", "lite", "se", "prime",
            "note", "fold", "flip", "neo", "turbo", "gt", "xl"
    );

    /** Unit suffixes, used when deciding which numeric tokens are specs. */
    private static final Set<String> UNITS = Set.of(
            "gb", "tb", "mb", "kb", "mah", "mp", "hz", "ghz", "mhz",
            "inch", "cm", "mm", "kg", "w"
    );

    /**
     * Digit-bearing tokens that describe connectivity rather than which model
     * this is. Excluded from the generation signature, since one platform
     * writing "iPhone 15 Pro 5G" and another writing "iPhone 15 Pro" must not
     * be read as two different phones.
     */
    private static final Set<String> NON_MODEL_SPEC_TOKENS = Set.of(
            "5g", "4g", "3g", "2g", "lte", "wifi", "4k", "8k", "2k", "1080p", "720p", "60hz", "120hz"
    );

    /**
     * Full normalisation pipeline: strip noise, fold case and punctuation,
     * regularise units, drop stopwords.
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = decodeEntities(raw).toLowerCase(Locale.ROOT);

        for (Pattern p : NOISE) {
            s = p.matcher(s).replaceAll(" ");
        }

        // Marketplace titles often carry a tail after a pipe or bullet that is
        // description rather than identity.
        int cut = s.indexOf('|');
        if (cut > 20) {
            s = s.substring(0, cut);
        }

        // Express terabytes in gigabytes so "1tb" and "1024gb" agree.
        Matcher tb = TB_CAPACITY.matcher(s);
        StringBuilder tbOut = new StringBuilder();
        while (tb.find()) {
            int value = Integer.parseInt(tb.group(1));
            tb.appendReplacement(tbOut, (value * 1024) + " gb");
        }
        tb.appendTail(tbOut);
        s = tbOut.toString();

        // "128GB" and "128 GB" must produce identical tokens.
        s = UNIT_SPACING.matcher(s).replaceAll("$1 $2");

        s = NON_ALNUM.matcher(s).replaceAll(" ");
        s = MULTISPACE.matcher(s).replaceAll(" ").trim();

        List<String> kept = new ArrayList<>();
        for (String token : s.split(" ")) {
            if (token.isBlank()) {
                continue;
            }
            if (STOPWORDS.contains(token)) {
                continue;
            }
            // Single characters are noise unless they are a meaningful unit or digit.
            if (token.length() == 1 && !Character.isDigit(token.charAt(0)) && !UNITS.contains(token)) {
                continue;
            }
            kept.add(token);
        }
        return String.join(" ", kept);
    }

    private static final Pattern NUMERIC_ENTITY =
            Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("&amp;", "&"), Map.entry("&lt;", "<"), Map.entry("&gt;", ">"),
            Map.entry("&quot;", "\""), Map.entry("&apos;", "'"), Map.entry("&nbsp;", " "),
            Map.entry("&rsquo;", "'"), Map.entry("&lsquo;", "'"), Map.entry("&ldquo;", "\""),
            Map.entry("&rdquo;", "\""), Map.entry("&ndash;", "-"), Map.entry("&mdash;", "-"),
            Map.entry("&hellip;", "..."), Map.entry("&trade;", "TM"), Map.entry("&reg;", "(R)"),
            Map.entry("&copy;", "(C)"), Map.entry("&deg;", " degrees")
    );

    /**
     * Decodes HTML entities in marketplace copy.
     *
     * <p>Amazon returns titles carrying raw entities - "India&amp;#x27;s First
     * Snapdragon" - because its own pages are HTML. Left alone they show up
     * verbatim in the interface and add junk tokens to the matcher, so they are
     * decoded once on the way in rather than papered over at every read.
     */
    public static String decodeEntities(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        String result = text;
        for (Map.Entry<String, String> entity : NAMED_ENTITIES.entrySet()) {
            if (result.contains(entity.getKey())) {
                result = result.replace(entity.getKey(), entity.getValue());
            }
        }
        Matcher matcher = NUMERIC_ENTITY.matcher(result);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            try {
                int codePoint = Integer.parseInt(matcher.group(2), matcher.group(1).isEmpty() ? 10 : 16);
                replacement = Character.isValidCodePoint(codePoint)
                        ? new String(Character.toChars(codePoint))
                        : matcher.group();
            } catch (NumberFormatException e) {
                replacement = matcher.group();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The brand vocabulary, shared with query parsing for typo correction. */
    public List<String> knownBrands() {
        return BRANDS;
    }

    /** Canonical display form of a lexicon brand, e.g. "oneplus" to "OnePlus". */
    public String displayBrand(String lexiconBrand) {
        return canonicalBrand(lexiconBrand);
    }

    public List<String> tokenize(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }
        return Arrays.asList(normalized.split(" "));
    }

    /**
     * Identifies the brand, preferring what the platform reported and falling
     * back to a lexicon scan of the title.
     */
    public String extractBrand(String rawTitle, String reportedBrand) {
        if (reportedBrand != null && !reportedBrand.isBlank()) {
            String cleaned = reportedBrand.toLowerCase(Locale.ROOT).trim();
            for (String brand : BRANDS) {
                if (cleaned.equals(brand) || cleaned.startsWith(brand + " ")) {
                    return canonicalBrand(brand);
                }
            }
            return capitalise(cleaned);
        }
        if (rawTitle == null) {
            return null;
        }
        String haystack = " " + rawTitle.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ") + " ";
        // BRANDS is ordered longest-phrase-first so multi-word brands win.
        for (String brand : BRANDS) {
            if (haystack.contains(" " + brand + " ")) {
                return canonicalBrand(brand);
            }
        }
        return null;
    }

    /**
     * Builds the model signature: the tokens that actually distinguish this
     * variant from its siblings.
     *
     * <p>Storage capacity is kept deliberately. A 128 GB and a 256 GB phone are
     * different products at different prices, and folding them together would
     * produce a comparison that looks authoritative while being wrong.
     */
    public String extractModelKey(String normalizedTitle) {
        ModelSignature signature = modelSignature(normalizedTitle);
        Set<String> parts = new LinkedHashSet<>();
        parts.addAll(signature.generations());
        parts.addAll(signature.qualifiers());
        parts.addAll(signature.capacities());
        return String.join(" ", parts);
    }

    /**
     * The three kinds of token that decide which variant a listing refers to.
     *
     * @param generations digit-bearing model tokens, e.g. "15", "s24", "9300"
     * @param qualifiers  variant words, e.g. "pro", "max", "ultra"
     * @param capacities  storage and similar sizes, e.g. "128gb"
     */
    public record ModelSignature(Set<String> generations, Set<String> qualifiers, Set<String> capacities) {

        /**
         * The model number proper: the first digit-bearing token in the title.
         *
         * <p>Only the first one is meaningful. Marketplace titles trail all
         * sorts of other numbers - "Snapdragon 8 Gen 3", "16 GB RAM", warranty
         * periods - and comparing the whole set would reject genuine matches
         * purely because one platform mentioned the chipset and the other did
         * not. Titles lead with brand then model, so the first such token is
         * reliably the one that identifies the product.
         */
        public String primaryGeneration() {
            return generations.isEmpty() ? null : generations.iterator().next();
        }
    }

    /**
     * Splits a normalised title into its variant-defining tokens.
     *
     * <p>Kept separate from plain similarity because these tokens behave as
     * vetoes rather than as weights. Cosine similarity puts "iPhone 15 Pro" and
     * "iPhone 15 Pro Max" at roughly 0.87 - close enough to merge them, and
     * merging them would be flatly wrong. A single qualifier can be the whole
     * difference between two products, so it is checked exactly rather than
     * blended into a score.
     */
    public ModelSignature modelSignature(String normalizedTitle) {
        Set<String> generations = new LinkedHashSet<>();
        Set<String> qualifiers = new LinkedHashSet<>();
        Set<String> capacities = new LinkedHashSet<>();

        if (normalizedTitle == null || normalizedTitle.isBlank()) {
            return new ModelSignature(generations, qualifiers, capacities);
        }

        String[] tokens = normalizedTitle.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.isBlank() || UNITS.contains(t)) {
                continue;
            }
            if (MODEL_WORDS.contains(t)) {
                qualifiers.add(t);
                continue;
            }
            if (!HAS_DIGIT.matcher(t).matches() || NON_MODEL_SPEC_TOKENS.contains(t)) {
                continue;
            }
            boolean followedByUnit = i + 1 < tokens.length && UNITS.contains(tokens[i + 1]);
            if (followedByUnit) {
                capacities.add(t + tokens[i + 1]);
            } else {
                generations.add(t);
            }
        }
        return new ModelSignature(generations, qualifiers, capacities);
    }

    private static String canonicalBrand(String brand) {
        return switch (brand) {
            case "western digital" -> "Western Digital";
            case "fire-boltt", "fire boltt" -> "Fire-Boltt";
            case "hp" -> "HP";
            case "lg" -> "LG";
            case "mi" -> "Mi";
            case "msi" -> "MSI";
            case "jbl" -> "JBL";
            case "amd" -> "AMD";
            case "tcl" -> "TCL";
            case "gopro" -> "GoPro";
            case "oneplus" -> "OnePlus";
            case "iqoo" -> "iQOO";
            case "steelseries" -> "SteelSeries";
            default -> capitalise(brand);
        };
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
