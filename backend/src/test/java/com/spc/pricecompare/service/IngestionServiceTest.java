package com.spc.pricecompare.service;

import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.provider.RawListing;
import com.spc.pricecompare.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration cover for ingestion, against the in-memory test database. Makes
 * no network calls - listings are handed in directly.
 */
@SpringBootTest
@Transactional
class IngestionServiceTest {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ProductRepository productRepository;

    private static RawListing listing(String platform, String id, String title, String price) {
        return RawListing.builder()
                .platformCode(platform)
                .externalId(id)
                .title(title)
                .brand("Apple")
                .categoryHint("smartphones")
                .price(new BigDecimal(price))
                .currency("INR")
                .ratingCount(10)
                .rating(new BigDecimal("4.5"))
                .inStock(true)
                .reviews(List.of())
                .build();
    }

    @Test
    @DisplayName("A product is searchable in the same transaction that ingested it")
    void productIsVisibleImmediatelyAfterIngest() {
        // Regression cover for a real defect: the offer was persisted but never
        // added to the in-memory Product, so a re-query inside the same
        // transaction got the managed instance back with an empty offers
        // collection. Search filtered it out, and the first search for any new
        // term returned nothing until it was run a second time.
        ingestionService.ingest(List.of(
                listing("AMAZON_IN", "TEST-ASIN-1", "Apple iPhone 15 Pro 256 GB Blue Titanium", "129900")));

        List<Product> found = productRepository.searchByText("iphone 15 pro");

        assertFalse(found.isEmpty(), "The freshly ingested product should be searchable");
        assertFalse(found.get(0).getOffers().isEmpty(),
                "Both sides of the product-offer association must be in step, or search filters it out");
    }

    @Test
    @DisplayName("The same listing ingested twice updates rather than duplicating")
    void reIngestingUpdatesInPlace() {
        ingestionService.ingest(List.of(
                listing("AMAZON_IN", "TEST-ASIN-2", "Apple iPhone 15 Pro 512 GB Black", "159900")));
        long afterFirst = productRepository.count();

        ingestionService.ingest(List.of(
                listing("AMAZON_IN", "TEST-ASIN-2", "Apple iPhone 15 Pro 512 GB Black", "149900")));

        assertEquals(afterFirst, productRepository.count(),
                "Re-ingesting the same external id must not create a second product");

        Product product = productRepository.searchByText("iphone 15 pro 512").get(0);
        assertEquals(1, product.getOffers().size(), "and must not create a second offer either");
        assertEquals(0, new BigDecimal("149900.00").compareTo(product.getOffers().get(0).getPriceInr()),
                "the price should have been updated to the newer value");
    }

    @Test
    @DisplayName("HTML entities are decoded before a title is stored")
    void storedTitlesAreDecoded() {
        ingestionService.ingest(List.of(
                listing("AMAZON_IN", "TEST-ASIN-3", "OnePlus 13 India&#x27;s First Snapdragon", "64999")));

        List<Product> found = productRepository.searchByText("oneplus 13");

        assertFalse(found.isEmpty());
        assertTrue(found.get(0).getCanonicalTitle().contains("India's"),
                "Entities must be decoded on the way in, not left for the interface to deal with");
        assertFalse(found.get(0).getCanonicalTitle().contains("&#x27;"));
    }
}
