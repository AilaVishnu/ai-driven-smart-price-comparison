package com.spc.pricecompare.ai;

import com.spc.pricecompare.provider.RawListing;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decides which listings from different platforms are the same product.
 *
 * <p>This is the component the entire application rests on. Amazon and Flipkart
 * hand back disjoint catalogues with no shared identifier, so "the same phone,
 * on both sites" is not something either API can tell us - it has to be
 * inferred. Without this step there is no comparison, only two unrelated lists.
 *
 * <p>The score is a weighted blend of three views of the same pair:
 *
 * <pre>
 *   similarity = 0.55 * cosine(TF-IDF over titles)
 *              + 0.25 * jaroWinkler(model signature)
 *              + 0.20 * brandAgreement
 * </pre>
 *
 * <p>Cosine alone merges siblings - "Galaxy S24" and "Galaxy S23" share almost
 * every token. The model term is what separates them, and the brand term stops
 * a generic accessory drifting into a branded cluster.
 *
 * <p>Before any pair is scored it must pass two cheap gates: same category, and
 * prices within a configured band. Besides being a large speed win, the price
 * gate encodes something true - a phone and its case are described with nearly
 * the same words and are not remotely the same product.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductMatchingService {

    private final TextNormalizer normalizer;
    private final MatchingProperties properties;

    /**
     * One inferred product: several platform listings judged to be the same
     * item, plus the listing chosen to represent them.
     */
    @Getter
    @Builder
    public static class Cluster {
        private final String canonicalTitle;
        private final String normalizedTitle;
        private final String modelKey;
        private final String brand;
        private final String categoryHint;
        private final List<RawListing> listings;

        /** How many distinct platforms carry this product - the headline comparison fact. */
        public long platformCount() {
            return listings.stream().map(RawListing::platformCode).distinct().count();
        }
    }

    /** A listing with its derived features, computed once and reused across comparisons. */
    private static final class Candidate {
        private final RawListing listing;
        private final String normalized;
        private final String modelKey;
        private final TextNormalizer.ModelSignature signature;
        private final String brand;
        private final String category;
        private final BigDecimal price;
        private Map<String, Double> vector;

        private Candidate(RawListing listing, String normalized, String modelKey,
                          TextNormalizer.ModelSignature signature,
                          String brand, String category, BigDecimal price) {
            this.listing = listing;
            this.normalized = normalized;
            this.modelKey = modelKey;
            this.signature = signature;
            this.brand = brand;
            this.category = category;
            this.price = price;
        }
    }

    /** Category for the gate: what the platform said, or what the title implies. */
    private static String resolveCategoryFor(RawListing listing) {
        String fromHint = normalizeCategory(listing.categoryHint());
        if (fromHint != null && !"other".equals(fromHint)) {
            return fromHint;
        }
        return inferCategoryFromTitle(listing.title());
    }

    private Candidate candidate(RawListing listing) {
        String normalized = normalizer.normalize(listing.title());
        return new Candidate(
                listing,
                normalized,
                normalizer.extractModelKey(normalized),
                normalizer.modelSignature(normalized),
                normalizer.extractBrand(listing.title(), listing.brand()),
                resolveCategoryFor(listing),
                listing.price());
    }

    /**
     * Groups listings into products.
     *
     * <p>Prices must already be in INR: the price gate compares them directly
     * and would be meaningless across mixed currencies.
     */
    public List<Cluster> cluster(List<RawListing> listings) {
        if (listings == null || listings.isEmpty()) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>(listings.size());
        List<List<String>> corpus = new ArrayList<>(listings.size());

        for (RawListing listing : listings) {
            if (listing == null || !listing.isUsable()) {
                continue;
            }
            Candidate c = candidate(listing);
            if (c.normalized.isBlank()) {
                continue;
            }
            candidates.add(c);
            corpus.add(normalizer.tokenize(c.normalized));
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        // IDF is learned over this batch, so a term common to these listings is
        // correctly discounted for these listings.
        TfIdfVectorizer.Model model = TfIdfVectorizer.fit(corpus);
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).vector = model.vectorize(corpus.get(i));
        }

        // Greedy agglomeration against cluster representatives. O(n*k) with k
        // clusters rather than O(n^2), and stable enough for batch sizes here.
        List<List<Candidate>> clusters = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int bestIndex = -1;
            double bestScore = 0.0;

            for (int i = 0; i < clusters.size(); i++) {
                Candidate representative = clusters.get(i).get(0);
                if (!passesGates(candidate, representative)) {
                    continue;
                }
                double score = similarity(candidate, representative);
                boolean accept = score >= properties.getThreshold()
                        || isStrongModelMatch(candidate, representative);
                if (accept && score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }

            if (bestIndex >= 0) {
                clusters.get(bestIndex).add(candidate);
            } else {
                List<Candidate> fresh = new ArrayList<>();
                fresh.add(candidate);
                clusters.add(fresh);
            }
        }

        List<Cluster> out = new ArrayList<>(clusters.size());
        for (List<Candidate> group : clusters) {
            out.add(toCluster(group));
        }

        long multi = out.stream().filter(c -> c.platformCount() > 1).count();
        log.debug("Matched {} listings into {} products ({} of them carried by more than one platform)",
                candidates.size(), out.size(), multi);
        return out;
    }

    /**
     * Cheap gates applied before scoring: same category, and prices within the
     * configured band.
     */
    private boolean passesGates(Candidate a, Candidate b) {
        if (a.category != null && b.category != null && !a.category.equals(b.category)) {
            return false;
        }
        // Two known, different manufacturers cannot be the same product. Treating
        // this as a veto rather than a score term means the blend can afford to
        // be generous elsewhere without ever merging a Dell into an HP.
        if (a.brand != null && b.brand != null && !a.brand.equalsIgnoreCase(b.brand)) {
            return false;
        }
        if (hasVariantConflict(a.signature, b.signature)) {
            return false;
        }
        if (a.price == null || b.price == null
                || a.price.signum() <= 0 || b.price.signum() <= 0) {
            return true;
        }
        double ratio = a.price.doubleValue() / b.price.doubleValue();
        return ratio >= properties.getPriceBandLow() && ratio <= properties.getPriceBandHigh();
    }

    /**
     * Rejects pairs that are sibling variants rather than the same product.
     *
     * <p>This is a veto, not a penalty, and that distinction matters. Cosine
     * similarity scores "iPhone 15 Pro" against "iPhone 15 Pro Max" at around
     * 0.87 and "Galaxy S24 Ultra" against "Galaxy S23 Ultra" similarly - both
     * comfortably above any threshold that still matches genuine pairs. No
     * weighting fixes that, because the single differing token carries the
     * entire meaning. So qualifiers and generations must agree exactly.
     *
     * <p>Capacity is treated more leniently: platforms often omit it, so it only
     * blocks a match when both sides state one and they disagree.
     */
    static boolean hasVariantConflict(TextNormalizer.ModelSignature a, TextNormalizer.ModelSignature b) {
        // Qualifiers are compared exactly: Pro and Pro Max are never the same phone.
        if (!a.qualifiers().equals(b.qualifiers())) {
            return true;
        }
        // Only the leading model number is compared. The full set would also pick
        // up chipset and RAM figures, rejecting real matches whenever one platform
        // mentioned a spec the other left out.
        String genA = a.primaryGeneration();
        String genB = b.primaryGeneration();
        if (genA != null && genB != null && !genA.equals(genB)) {
            return true;
        }
        // Capacity only blocks when both sides state one and they disagree.
        return !a.capacities().isEmpty()
                && !b.capacities().isEmpty()
                && !a.capacities().equals(b.capacities());
    }

    /**
     * True when both listings carry the same distinctive model code.
     *
     * <p>An exact match on something like "1000xm5" or "xps13 9300" is close to
     * conclusive - that is how catalogue matching works on manufacturer part
     * numbers in the real world. It gets its own path because the blended score
     * can miss these: platforms describe the same headphones as "Wireless
     * Headphones" and "Bluetooth Headset", which shares almost no vocabulary and
     * drags cosine down to about 0.43 even though the model code agrees exactly.
     *
     * <p>This bypasses the score threshold only. Every veto and gate still
     * applies first, so a shared code can never merge across different brands,
     * capacities or variants.
     */
    private static boolean isStrongModelMatch(Candidate a, Candidate b) {
        String keyA = a.modelKey;
        String keyB = b.modelKey;
        if (keyA == null || keyB == null || !keyA.equals(keyB)) {
            return false;
        }
        // Short or purely numeric keys are not distinctive enough: plenty of
        // unrelated products are "12".
        return keyA.length() >= 4 && keyA.chars().anyMatch(Character::isDigit);
    }

    private double similarity(Candidate a, Candidate b) {
        MatchingProperties.Weight w = properties.getWeight();

        double cosine = Similarity.cosine(a.vector, b.vector);

        double modelScore;
        boolean aHasModel = a.modelKey != null && !a.modelKey.isBlank();
        boolean bHasModel = b.modelKey != null && !b.modelKey.isBlank();
        if (aHasModel && bHasModel) {
            modelScore = Similarity.jaroWinkler(a.modelKey, b.modelKey);
        } else if (!aHasModel && !bHasModel) {
            // Neither side carries a model signature, so this view has nothing to
            // say and scores neutral.
            //
            // It previously fell back to the cosine value, which was a mistake:
            // that counted a single piece of evidence twice and let two generic
            // listings with no brand and no model number merge on wording alone.
            // Two unrelated t-shirts duly merged into one product. Where there is
            // no distinguishing evidence, the honest answer is no opinion.
            modelScore = 0.5;
        } else {
            modelScore = 0.0;
        }

        double brandScore;
        if (a.brand != null && b.brand != null) {
            brandScore = a.brand.equalsIgnoreCase(b.brand) ? 1.0 : 0.0;
        } else {
            // An unreported brand is missing information, not disagreement.
            brandScore = 0.5;
        }

        return (w.getCosine() * cosine) + (w.getModel() * modelScore) + (w.getBrand() * brandScore);
    }

    /** Component-by-component breakdown, so a match decision can be inspected rather than trusted. */
    public Map<String, Object> explain(RawListing first, RawListing second) {
        List<RawListing> pair = List.of(first, second);
        List<List<String>> corpus = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (RawListing listing : pair) {
            Candidate c = candidate(listing);
            candidates.add(c);
            corpus.add(normalizer.tokenize(c.normalized));
        }
        TfIdfVectorizer.Model model = TfIdfVectorizer.fit(corpus);
        candidates.get(0).vector = model.vectorize(corpus.get(0));
        candidates.get(1).vector = model.vectorize(corpus.get(1));

        Candidate a = candidates.get(0);
        Candidate b = candidates.get(1);
        double score = similarity(a, b);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("normalizedA", a.normalized);
        out.put("normalizedB", b.normalized);
        out.put("modelKeyA", a.modelKey);
        out.put("modelKeyB", b.modelKey);
        out.put("brandA", a.brand);
        out.put("brandB", b.brand);
        out.put("cosine", round(Similarity.cosine(a.vector, b.vector)));
        out.put("modelSimilarity", round(Similarity.jaroWinkler(
                Objects.toString(a.modelKey, ""), Objects.toString(b.modelKey, ""))));
        out.put("variantConflict", hasVariantConflict(a.signature, b.signature));
        out.put("passedGates", passesGates(a, b));
        out.put("strongModelMatch", isStrongModelMatch(a, b));
        out.put("score", round(score));
        out.put("threshold", properties.getThreshold());
        out.put("wouldMatch", passesGates(a, b)
                && (score >= properties.getThreshold() || isStrongModelMatch(a, b)));
        return out;
    }

    private Cluster toCluster(List<Candidate> group) {
        // The longest title is used as canonical: it is almost always the most
        // complete, carrying capacity, colour and variant the others omit.
        Candidate best = group.stream()
                .max(Comparator.comparingInt(c -> c.listing.title() == null ? 0 : c.listing.title().length()))
                .orElse(group.get(0));

        String brand = group.stream()
                .map(c -> c.brand)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        String category = group.stream()
                .map(c -> c.category)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        return Cluster.builder()
                .canonicalTitle(best.listing.title())
                .normalizedTitle(best.normalized)
                .modelKey(best.modelKey)
                .brand(brand)
                .categoryHint(category)
                .listings(group.stream().map(c -> c.listing).toList())
                .build();
    }

    /**
     * Folds platform category vocabularies onto one taxonomy so the category
     * gate does not reject a genuine match purely over naming.
     */
    public static String normalizeCategory(String hint) {
        if (hint == null || hint.isBlank()) {
            return null;
        }
        String h = hint.toLowerCase().trim().replace('_', '-').replace(' ', '-');

        // Checked before the device categories, not after. "mobile-accessories"
        // contains "mobile", so testing for phones first filed chargers and
        // cases as smartphones - which then surfaced them under a smartphone
        // search and let the matcher compare a case against a phone.
        if (h.contains("accessor") || h.contains("cover") || h.contains("case")) {
            return "accessories";
        }
        if (h.contains("smartphone") || h.contains("mobile") || h.equals("phones") || h.contains("cell-phone")) {
            return "smartphones";
        }
        if (h.contains("laptop") || h.contains("notebook")) {
            return "laptops";
        }
        if (h.contains("tablet") || h.contains("ipad")) {
            return "tablets";
        }
        if (h.contains("speaker") || h.contains("soundbar") || h.contains("home-theat")) {
            return "speakers";
        }
        if (h.contains("headphone") || h.contains("earphone") || h.contains("earbud") || h.contains("audio")) {
            return "headphones";
        }
        if (h.contains("watch")) {
            return "smartwatches";
        }
        if (h.contains("camera")) {
            return "cameras";
        }
        if (h.contains("tv") || h.contains("television")) {
            return "televisions";
        }
        if (h.contains("monitor") || h.contains("display")) {
            return "monitors";
        }
        if (h.contains("storage") || h.contains("ssd") || h.contains("hard-drive") || h.contains("hdd")) {
            return "storage";
        }
        if (h.contains("shoe") || h.contains("footwear") || h.contains("sneaker")) {
            return "footwear";
        }
        if (h.contains("beauty") || h.contains("skin") || h.contains("fragrance")) {
            return "beauty";
        }
        if (h.contains("grocer")) {
            return "groceries";
        }
        if (h.contains("electronic")) {
            return "accessories";
        }
        return h;
    }

    /**
     * Works out a category from the product title.
     *
     * <p>Needed because not every platform says what a product is. Amazon
     * returns no category field at all on search, so without this every Amazon
     * product was filed as "other" - invisible to category filters, and skipped
     * by the matching gate that only compares within a category.
     *
     * <p>Accessories are tested first and deliberately: "iPhone Charger" and
     * "Silicone Case for iPhone" both contain "iphone", and calling them
     * smartphones is how a 1,900 rupee charger ends up beside a 65,000 rupee
     * phone.
     */
    public static String inferCategoryFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String t = " " + title.toLowerCase().replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ") + " ";

        if (containsAny(t, "charger", "cable", "adapter", "case", "cover", "screen guard",
                "tempered glass", "screen protector", "pouch", "stand", "holder",
                "power bank", "powerbank", "lamp", "skin")) {
            return "accessories";
        }
        if (containsAny(t, "earbud", "earbuds", "headphone", "headphones", "headset",
                "earphone", "earphones", "airdopes", "buds", "neckband")) {
            return "headphones";
        }
        // Before laptops on purpose. "Laptop/Desktop Speaker" contains "laptop",
        // and filing a 420 rupee speaker as a laptop put it at the top of a
        // laptop search, above an actual laptop, because cheap things score well
        // on price. The noun the product IS beats the noun it mentions.
        if (containsAny(t, "speaker", "soundbar", "sound bar", "woofer", "subwoofer",
                "home theatre", "home theater")) {
            return "speakers";
        }
        if (containsAny(t, "laptop", "notebook", "macbook", "chromebook", "ultrabook")) {
            return "laptops";
        }
        if (containsAny(t, "tablet", "ipad", "tab")) {
            return "tablets";
        }
        if (containsAny(t, "smartwatch", "smart watch")) {
            return "smartwatches";
        }
        if (containsAny(t, "smartband", "smart band", "fitness band", "fitness tracker")) {
            return "smartbands";
        }
        if (containsAny(t, "television", " tv ", "smart tv", "led tv")) {
            return "televisions";
        }
        if (containsAny(t, "monitor")) {
            return "monitors";
        }
        if (containsAny(t, "ssd", "hard drive", "hard disk", "pendrive", "memory card", "microsd")) {
            return "storage";
        }
        if (containsAny(t, "camera", "dslr", "gopro")) {
            return "cameras";
        }
        // Checked last: phone brand names appear in accessory titles too.
        if (containsAny(t, "smartphone", "iphone", "galaxy", "redmi", "oneplus", "vivo",
                "realme", "oppo", "poco", "motorola", "5g mobile", "mobile phone", "keypad phone")) {
            return "smartphones";
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle.startsWith(" ") ? needle : " " + needle)
                    || haystack.contains(needle + " ")) {
                return true;
            }
        }
        return false;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
