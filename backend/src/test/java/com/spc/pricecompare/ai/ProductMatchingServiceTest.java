package com.spc.pricecompare.ai;

import com.spc.pricecompare.provider.RawListing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The matching engine is the component the whole product depends on, so these
 * tests pin down both halves of the job: genuine cross-platform pairs must
 * merge, and sibling variants must not.
 *
 * <p>The negative cases are the interesting ones. Cosine similarity alone rates
 * "iPhone 15 Pro" against "iPhone 15 Pro Max" at roughly 0.87, which is above
 * any threshold that still matches real pairs - so these tests are what force
 * the variant veto to exist.
 */
class ProductMatchingServiceTest {

    private final ProductMatchingService service =
            new ProductMatchingService(new TextNormalizer(), new MatchingProperties());

    private static RawListing listing(String platform, String id, String title,
                                      String brand, String category, String price) {
        return RawListing.builder()
                .platformCode(platform)
                .externalId(id)
                .title(title)
                .brand(brand)
                .categoryHint(category)
                .price(new BigDecimal(price))
                .currency("INR")
                .ratingCount(0)
                .inStock(true)
                .reviews(List.of())
                .build();
    }

    private boolean matches(RawListing a, RawListing b) {
        List<ProductMatchingService.Cluster> clusters = service.cluster(List.of(a, b));
        return clusters.size() == 1;
    }

    @Test
    @DisplayName("The same phone titled differently by Amazon and Flipkart becomes one product")
    void mergesTheSamePhoneAcrossPlatforms() {
        RawListing amazon = listing("AMAZON_IN", "B0CHX1W1XY",
                "Apple iPhone 15 Pro (128 GB) - Blue Titanium",
                "Apple", "smartphones", "129900");
        RawListing flipkart = listing("FLIPKART", "MOBGTAGPTB3VS24W",
                "APPLE iPhone 15 Pro (Blue Titanium, 128GB Storage)",
                "Apple", "Mobiles", "127999");

        assertTrue(matches(amazon, flipkart),
                "Two titles for the identical phone should collapse into one product");
    }

    @Test
    @DisplayName("Pro and Pro Max are different products and must not merge")
    void doesNotMergeProWithProMax() {
        RawListing pro = listing("AMAZON_IN", "A1",
                "Apple iPhone 15 Pro (128 GB) - Blue Titanium",
                "Apple", "smartphones", "129900");
        RawListing proMax = listing("FLIPKART", "F1",
                "APPLE iPhone 15 Pro Max (Blue Titanium, 128GB Storage)",
                "Apple", "Mobiles", "159900");

        assertFalse(matches(pro, proMax),
                "A qualifier like Max is the whole difference between two SKUs");
    }

    @Test
    @DisplayName("Consecutive generations are different products and must not merge")
    void doesNotMergeAcrossGenerations() {
        RawListing s24 = listing("AMAZON_IN", "A2",
                "Samsung Galaxy S24 Ultra 5G (256 GB) Titanium Grey",
                "Samsung", "smartphones", "129999");
        RawListing s23 = listing("FLIPKART", "F2",
                "SAMSUNG Galaxy S23 Ultra 5G (Titanium Grey, 256 GB)",
                "Samsung", "Mobiles", "104999");

        assertFalse(matches(s24, s23),
                "S24 and S23 share nearly every token but are different phones");
    }

    @Test
    @DisplayName("Different storage capacities are different products")
    void doesNotMergeDifferentCapacities() {
        RawListing small = listing("AMAZON_IN", "A3",
                "Apple iPhone 15 Pro (128 GB) - Natural Titanium",
                "Apple", "smartphones", "129900");
        RawListing large = listing("FLIPKART", "F3",
                "APPLE iPhone 15 Pro (Natural Titanium, 512 GB)",
                "Apple", "Mobiles", "179900");

        assertFalse(matches(small, large),
                "Capacity variants carry genuinely different prices");
    }

    @Test
    @DisplayName("A missing capacity on one side does not block an otherwise clear match")
    void toleratesMissingCapacity() {
        RawListing withCapacity = listing("AMAZON_IN", "A4",
                "Apple iPhone 15 Pro (128 GB) - Natural Titanium",
                "Apple", "smartphones", "129900");
        RawListing withoutCapacity = listing("FLIPKART", "F4",
                "APPLE iPhone 15 Pro (Natural Titanium)",
                "Apple", "Mobiles", "128999");

        assertTrue(matches(withCapacity, withoutCapacity),
                "Platforms often omit capacity; absence is missing data, not disagreement");
    }

    @Test
    @DisplayName("A 5G marketing suffix does not split a product in two")
    void ignoresConnectivitySuffix() {
        RawListing with5g = listing("AMAZON_IN", "A5",
                "OnePlus 12 5G (256 GB) Flowy Emerald",
                "OnePlus", "smartphones", "64999");
        RawListing without5g = listing("FLIPKART", "F5",
                "OnePlus 12 (Flowy Emerald, 256 GB)",
                "OnePlus", "Mobiles", "63999");

        assertTrue(matches(with5g, without5g),
                "5G describes connectivity, not which model this is");
    }

    @Test
    @DisplayName("A phone and its accessory are kept apart by the price gate")
    void separatesAccessoryFromDevice() {
        RawListing phone = listing("AMAZON_IN", "A6",
                "Apple iPhone 15 Pro (128 GB) - Blue Titanium",
                "Apple", "smartphones", "129900");
        RawListing cover = listing("FLIPKART", "F6",
                "Apple iPhone 15 Pro Silicone Case Cover Blue",
                "Apple", "smartphones", "4900");

        assertFalse(matches(phone, cover),
                "Nearly identical wording, wildly different price - the price gate must catch this");
    }

    @Test
    @DisplayName("Different brands never merge")
    void doesNotMergeAcrossBrands() {
        RawListing dell = listing("AMAZON_IN", "A7",
                "Dell XPS 13 9315 Laptop 16 GB RAM 512 GB SSD",
                "Dell", "laptops", "99990");
        RawListing hp = listing("FLIPKART", "F7",
                "HP Pavilion 13 Laptop 16 GB RAM 512 GB SSD",
                "HP", "laptops", "97990");

        assertFalse(matches(dell, hp), "Different manufacturers are different products");
    }

    @Test
    @DisplayName("A three-platform product collapses to one cluster spanning three platforms")
    void mergesThreeListingsIntoOneProduct() {
        List<RawListing> listings = List.of(
                listing("AMAZON_IN", "A8", "Sony WH-1000XM5 Wireless Headphones Black",
                        "Sony", "headphones", "26990"),
                listing("FLIPKART", "F8", "SONY WH-1000XM5 Bluetooth Headset (Black)",
                        "Sony", "headphones", "25990"),
                listing("DUMMYJSON", "D8", "Sony WH-1000XM5 Headphones - Black",
                        "Sony", "headphones", "27500"));

        List<ProductMatchingService.Cluster> clusters = service.cluster(listings);

        assertEquals(1, clusters.size(), "All three listings describe one product");
        assertEquals(3, clusters.get(0).platformCount(),
                "The product should report availability on three platforms");
    }

    @Test
    @DisplayName("The canonical title is the most complete of the matched listings")
    void picksTheMostCompleteTitle() {
        RawListing terse = listing("FLIPKART", "F9",
                "OnePlus 12 5G (Flowy Emerald, 256 GB)",
                "OnePlus", "smartphones", "63999");
        RawListing complete = listing("AMAZON_IN", "A9",
                "OnePlus 12 5G (256 GB, Flowy Emerald) with Snapdragon 8 Gen 3 Processor",
                "OnePlus", "smartphones", "64999");

        List<ProductMatchingService.Cluster> clusters = service.cluster(List.of(terse, complete));

        assertEquals(1, clusters.size());
        assertEquals(complete.title(), clusters.get(0).getCanonicalTitle(),
                "The richer title is the better representative");
    }

    @Test
    @DisplayName("Unrelated products each stand alone")
    void keepsUnrelatedProductsSeparate() {
        List<RawListing> listings = List.of(
                listing("AMAZON_IN", "A10", "Apple iPhone 15 Pro (128 GB)", "Apple", "smartphones", "129900"),
                listing("AMAZON_IN", "A11", "Dell XPS 13 9315 Laptop", "Dell", "laptops", "99990"),
                listing("AMAZON_IN", "A12", "Sony WH-1000XM5 Headphones", "Sony", "headphones", "26990"));

        assertEquals(3, service.cluster(listings).size(),
                "Three different products should stay three products");
    }

    @Test
    @DisplayName("Generic listings with no brand and no model number do not merge on wording alone")
    void doesNotMergeOnWordingAlone() {
        // Caught in a real run: two unrelated t-shirts were merged into one
        // product because neither carried a brand or a model number, so both
        // vetoes stayed silent and only the wording was left to judge on. With
        // no distinguishing evidence, the right answer is to keep them apart.
        RawListing first = listing("FAKESTORE", "1",
                "Mens Casual Premium Slim Fit T-Shirts",
                null, "clothing", "1531.35");
        RawListing second = listing("FAKESTORE", "2",
                "Mens Casual Slim Fit T-Shirts",
                null, "clothing", "2135.66");

        assertFalse(matches(first, second),
                "Similar wording is not evidence enough when nothing identifies the product");
    }

    @Test
    @DisplayName("Unbranded listings still merge when a model number agrees")
    void stillMergesUnbrandedWhenModelAgrees() {
        RawListing first = listing("AMAZON_IN", "A20",
                "Wireless Headphones WH-1000XM5 Black", null, "headphones", "26990");
        RawListing second = listing("FLIPKART", "F20",
                "WH-1000XM5 Bluetooth Headset Black", null, "headphones", "25990");

        assertTrue(matches(first, second),
                "A shared model number is real evidence, brand or not");
    }

    @Test
    @DisplayName("Accessories are not classified as the device they mention")
    void classifiesAccessoriesSeparatelyFromDevices() {
        // The reason this exists: an iPhone charger and a silicone case both
        // contain "iphone", and filing them under smartphones put a 1,900 rupee
        // charger next to a 65,000 rupee phone in a smartphone search.
        assertEquals("accessories",
                ProductMatchingService.inferCategoryFromTitle("Apple iPhone Charger"));
        assertEquals("accessories",
                ProductMatchingService.inferCategoryFromTitle("iPhone 12 Silicone Case with MagSafe Plum"));
        assertEquals("accessories",
                ProductMatchingService.inferCategoryFromTitle("Selfie Lamp with iPhone"));
        assertEquals("smartphones",
                ProductMatchingService.inferCategoryFromTitle("Apple iPhone 16 (White, 128 GB)"));
    }

    @Test
    @DisplayName("Category is inferred from the title when a platform reports none")
    void infersCategoryFromTitle() {
        // Amazon returns no category field at all on search, so without this
        // every Amazon product would be filed as "other".
        assertEquals("headphones", ProductMatchingService.inferCategoryFromTitle(
                "Sony WH-1000XM5 Wireless Bluetooth Over Ear Headphones"));
        assertEquals("laptops", ProductMatchingService.inferCategoryFromTitle(
                "Lenovo V15 G4 AMD Athlon Laptop 8GB RAM 512 GB SSD"));
        assertEquals("smartwatches", ProductMatchingService.inferCategoryFromTitle(
                "Noise ColorFit Pro 5 Smart Watch"));
        assertNull(ProductMatchingService.inferCategoryFromTitle("Assorted Household Item"));
        assertNull(ProductMatchingService.inferCategoryFromTitle(null));
    }

    @Test
    @DisplayName("A platform category naming accessories is not read as the device")
    void normalizesAccessoryCategoriesCorrectly() {
        // "mobile-accessories" contains "mobile"; testing for phones first read
        // it as smartphones.
        assertEquals("accessories", ProductMatchingService.normalizeCategory("mobile-accessories"));
        assertEquals("smartphones", ProductMatchingService.normalizeCategory("smartphones"));
        assertEquals("smartphones", ProductMatchingService.normalizeCategory("Mobiles"));
    }

    @Test
    @DisplayName("A speaker that mentions laptops is a speaker, not a laptop")
    void classifiesSpeakersBeforeLaptops() {
        // Seen in a real search: these two ranked above an actual laptop in a
        // search for "laptop", because cheap items score well on price and the
        // classifier had matched the word "Laptop" in their titles.
        assertEquals("speakers", ProductMatchingService.inferCategoryFromTitle(
                "artis Mini Portable Laptop/Desktop Speaker Black-Blue, 2.0 Channel"));
        assertEquals("speakers", ProductMatchingService.inferCategoryFromTitle(
                "artis S21 5 W Laptop/Desktop Speaker Black, 2.0 Channel"));
        assertEquals("laptops", ProductMatchingService.inferCategoryFromTitle(
                "Acer Aspire 3 Intel Celeron Dual Core 8 GB 256 GB SSD Windows 11"));
    }

    @Test
    @DisplayName("explain() exposes why a pair did or did not match")
    void explainSurfacesTheDecision() {
        RawListing pro = listing("AMAZON_IN", "A13", "Apple iPhone 15 Pro (128 GB)",
                "Apple", "smartphones", "129900");
        RawListing proMax = listing("FLIPKART", "F13", "APPLE iPhone 15 Pro Max (128 GB)",
                "Apple", "smartphones", "159900");

        var explanation = service.explain(pro, proMax);

        assertEquals(Boolean.TRUE, explanation.get("variantConflict"),
                "The Max qualifier should be reported as the reason");
        assertEquals(Boolean.FALSE, explanation.get("wouldMatch"));
    }
}
