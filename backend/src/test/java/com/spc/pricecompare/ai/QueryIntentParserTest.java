package com.spc.pricecompare.ai;

import com.spc.pricecompare.ai.QueryIntentParser.ParsedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryIntentParserTest {

    private final QueryIntentParser parser = new QueryIntentParser(new TextNormalizer());

    @Test
    @DisplayName("The worked example parses into all four of its parts")
    void parsesTheCanonicalExample() {
        ParsedQuery parsed = parser.parse("gaming laptop under 60k from dell");

        assertEquals(0, new BigDecimal("60000").compareTo(parsed.maxPrice()));
        assertEquals("Dell", parsed.brand());
        assertEquals("laptops", parsed.category());
        assertTrue(parsed.searchTerms().contains("gaming"),
                "The part that is not a filter should remain searchable");
    }

    @Test
    @DisplayName("Indian money vocabulary is understood")
    void understandsIndianMoneyWords() {
        assertEquals(0, new BigDecimal("60000")
                .compareTo(parser.parse("phone under 60k").maxPrice()));
        assertEquals(0, new BigDecimal("150000.0")
                .compareTo(parser.parse("laptop under 1.5 lakh").maxPrice()));
        assertEquals(0, new BigDecimal("20000000")
                .compareTo(parser.parse("watch under 2 crore").maxPrice()));
        assertEquals(0, new BigDecimal("45000")
                .compareTo(parser.parse("mobile below rs 45,000").maxPrice()));
    }

    @Test
    @DisplayName("A price range is read as a range, not as a minimum")
    void parsesPriceRange() {
        ParsedQuery parsed = parser.parse("samsung phone between 20k and 40k");

        assertEquals(0, new BigDecimal("20000").compareTo(parsed.minPrice()));
        assertEquals(0, new BigDecimal("40000").compareTo(parsed.maxPrice()));
        assertEquals("Samsung", parsed.brand());
        assertEquals("smartphones", parsed.category());
    }

    @Test
    @DisplayName("A reversed range is corrected rather than returned unusable")
    void correctsReversedRange() {
        ParsedQuery parsed = parser.parse("headphones between 40k and 20k");

        assertEquals(0, new BigDecimal("20000").compareTo(parsed.minPrice()));
        assertEquals(0, new BigDecimal("40000").compareTo(parsed.maxPrice()));
    }

    @Test
    @DisplayName("A minimum rating is recognised")
    void parsesMinimumRating() {
        assertEquals(0, new BigDecimal("4")
                .compareTo(parser.parse("laptop 4 star and above").minRating()));
        assertEquals(0, new BigDecimal("4.5")
                .compareTo(parser.parse("phone 4.5 stars").minRating()));
    }

    @Test
    @DisplayName("A brand is found with or without a preposition")
    void findsBrandEitherWay() {
        assertEquals("Dell", parser.parse("laptop from dell").brand());
        assertEquals("Dell", parser.parse("dell laptop").brand());
        assertEquals("OnePlus", parser.parse("oneplus 12").brand());
    }

    @Test
    @DisplayName("A misspelled brand is still recognised")
    void toleratesBrandTypos() {
        assertEquals("Samsung", parser.parse("samsng galaxy phone").brand());
        assertEquals("Apple", parser.parse("aple iphone").brand());
    }

    @Test
    @DisplayName("Short common words are never fuzzy-matched into brands")
    void doesNotInventBrandsFromShortWords() {
        ParsedQuery parsed = parser.parse("the best phone");
        assertNull(parsed.brand(),
                "Fuzzy matching short words would turn ordinary English into brands");
    }

    @Test
    @DisplayName("Stock and discount intents are picked up")
    void parsesStockAndDiscountIntents() {
        assertEquals(Boolean.TRUE, parser.parse("laptop in stock").inStockOnly());
        assertEquals(Boolean.TRUE, parser.parse("phones on sale").discountedOnly());
    }

    @Test
    @DisplayName("Every recognised filter is described back to the user")
    void reportsItsInterpretation() {
        ParsedQuery parsed = parser.parse("gaming laptop under 60k from dell");

        assertTrue(parsed.interpretedAs().size() >= 3,
                "Budget, brand and category should each be shown as a chip");
        assertTrue(parsed.interpretedAs().stream().anyMatch(c -> c.contains("Dell")));
    }

    @Test
    @DisplayName("A plain product name passes through untouched")
    void plainQueryIsLeftAlone() {
        ParsedQuery parsed = parser.parse("sony wh-1000xm5");

        assertNull(parsed.maxPrice());
        assertNull(parsed.minPrice());
        assertEquals("Sony", parsed.brand());
        assertTrue(parsed.searchTerms().contains("wh-1000xm5"));
    }

    @Test
    @DisplayName("Null and empty input are handled without throwing")
    void handlesEmptyInput() {
        assertEquals("", parser.parse(null).searchTerms());
        assertEquals("", parser.parse("   ").searchTerms());
    }
}
