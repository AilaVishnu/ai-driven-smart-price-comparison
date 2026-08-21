package com.spc.pricecompare.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    @DisplayName("Two platform titles for the same phone normalise to the same tokens")
    void normalisesEquivalentTitlesAlike() {
        String amazon = normalizer.normalize("Apple iPhone 15 Pro (128 GB) - Blue Titanium");
        String flipkart = normalizer.normalize("APPLE iPhone 15 Pro (Blue Titanium, 128GB Storage)");

        assertTrue(amazon.contains("128 gb"), "Spacing of capacity must be regularised");
        assertTrue(flipkart.contains("128 gb"), "128GB and 128 GB must produce identical tokens");
        assertTrue(amazon.contains("iphone") && flipkart.contains("iphone"));
        assertTrue(amazon.contains("15") && flipkart.contains("15"));
    }

    @Test
    @DisplayName("Terabytes are expressed in gigabytes so capacities compare directly")
    void convertsTerabytesToGigabytes() {
        assertTrue(normalizer.normalize("WD 1TB Portable Hard Drive").contains("1024 gb"),
                "1TB and 1024GB describe the same capacity and must agree");
    }

    @Test
    @DisplayName("Marketing and logistics copy is stripped")
    void stripsMarketingNoise() {
        String result = normalizer.normalize(
                "Apple iPhone 15 (Renewed) with Offers, 1 Year Warranty, Free Delivery");

        assertFalse(result.contains("renewed"));
        assertFalse(result.contains("warranty"));
        assertFalse(result.contains("delivery"));
        assertTrue(result.contains("iphone"), "The actual product must survive");
    }

    @Test
    @DisplayName("Punctuation and case are folded away")
    void foldsCaseAndPunctuation() {
        String result = normalizer.normalize("SONY WH-1000XM5!!! Wireless, Black.");
        assertEquals(result, result.toLowerCase());
        assertFalse(result.contains("!"));
        assertFalse(result.contains(","));
    }

    @Test
    @DisplayName("Brand comes from the platform when reported, and from the title otherwise")
    void extractsBrand() {
        assertEquals("Apple", normalizer.extractBrand("iPhone 15 Pro", "Apple"));
        assertEquals("Apple", normalizer.extractBrand("Apple iPhone 15 Pro", null));
        assertEquals("OnePlus", normalizer.extractBrand("OnePlus 12 5G", null));
        assertEquals("Western Digital",
                normalizer.extractBrand("Western Digital 2TB Elements Drive", null),
                "Multi-word brands must beat a shorter accidental match");
        assertNull(normalizer.extractBrand("Generic USB Cable", null),
                "An unrecognised brand should be reported as absent, not guessed");
    }

    @Test
    @DisplayName("The model signature separates generations, qualifiers and capacities")
    void buildsModelSignature() {
        TextNormalizer.ModelSignature signature =
                normalizer.modelSignature(normalizer.normalize("Apple iPhone 15 Pro Max 256GB"));

        assertEquals(Set.of("15"), signature.generations());
        assertEquals(Set.of("pro", "max"), signature.qualifiers());
        assertEquals(Set.of("256gb"), signature.capacities());
    }

    @Test
    @DisplayName("Connectivity markers are not mistaken for model generations")
    void ignoresConnectivityMarkers() {
        TextNormalizer.ModelSignature with5g =
                normalizer.modelSignature(normalizer.normalize("OnePlus 12 5G 256GB"));
        TextNormalizer.ModelSignature without =
                normalizer.modelSignature(normalizer.normalize("OnePlus 12 256GB"));

        assertEquals(without.generations(), with5g.generations(),
                "5G describes connectivity, not which model this is");
    }

    @Test
    @DisplayName("Alphanumeric model codes are captured as generations")
    void capturesAlphanumericModelCodes() {
        TextNormalizer.ModelSignature signature =
                normalizer.modelSignature(normalizer.normalize("Samsung Galaxy S24 Ultra 512GB"));

        assertTrue(signature.generations().contains("s24"));
        assertTrue(signature.qualifiers().contains("ultra"));
        assertTrue(signature.capacities().contains("512gb"));
    }

    @Test
    @DisplayName("HTML entities in marketplace titles are decoded")
    void decodesHtmlEntities() {
        // Observed in live Amazon.in responses, which carry raw entities because
        // the underlying pages are HTML.
        assertEquals("India's First Snapdragon",
                TextNormalizer.decodeEntities("India&#x27;s First Snapdragon"));
        assertEquals("Tom & Jerry", TextNormalizer.decodeEntities("Tom &amp; Jerry"));
        assertEquals("6.1\" Display", TextNormalizer.decodeEntities("6.1&quot; Display"));
        assertEquals("don't", TextNormalizer.decodeEntities("don&#39;t"));
    }

    @Test
    @DisplayName("Text with no entities is returned untouched")
    void leavesPlainTextAlone() {
        String plain = "Apple iPhone 15 Pro 128 GB";
        assertEquals(plain, TextNormalizer.decodeEntities(plain));
        assertEquals(null, TextNormalizer.decodeEntities(null));
    }

    @Test
    @DisplayName("Empty and null input are handled without throwing")
    void handlesEmptyInput() {
        assertEquals("", normalizer.normalize(null));
        assertEquals("", normalizer.normalize("   "));
        assertEquals("", normalizer.extractModelKey(""));
        assertTrue(normalizer.tokenize("").isEmpty());
    }
}
