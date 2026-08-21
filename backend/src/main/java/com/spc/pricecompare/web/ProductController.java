package com.spc.pricecompare.web;

import com.spc.pricecompare.ai.PriceForecastService;
import com.spc.pricecompare.ai.SentimentAnalyzer;
import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.service.AccountService;
import com.spc.pricecompare.service.AuthService;
import com.spc.pricecompare.service.ProductService;
import com.spc.pricecompare.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final SearchService searchService;
    private final ProductService productService;
    private final AccountService accountService;
    private final AuthService authService;

    /**
     * Searches across every platform.
     *
     * <p>Filters may be supplied as parameters or written into the phrase itself
     * ("under 60k from dell"); an explicit parameter wins where the two
     * disagree. The response echoes back how the phrase was understood so the
     * interpretation is visible rather than silent.
     */
    @GetMapping("/search")
    public Dtos.SearchResponse search(
            @RequestParam(name = "q", required = false, defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String minPrice,
            @RequestParam(required = false) String maxPrice,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String minRating,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String inStock,
            @RequestParam(required = false) String discounted,
            @RequestParam(required = false, defaultValue = "relevance") String sort) {

        Map<String, String> filters = new HashMap<>();
        putIfPresent(filters, "minPrice", minPrice);
        putIfPresent(filters, "maxPrice", maxPrice);
        putIfPresent(filters, "brand", brand);
        putIfPresent(filters, "category", category);
        putIfPresent(filters, "minRating", minRating);
        putIfPresent(filters, "platform", platform);
        putIfPresent(filters, "inStock", inStock);
        putIfPresent(filters, "discounted", discounted);

        Dtos.SearchResponse response = searchService.search(query, page, size, filters, sort);

        // Recorded for signed-in and anonymous users alike; the latter simply
        // have no user attached.
        accountService.recordSearch(authService.currentUser().orElse(null),
                query, (int) response.totalResults());

        return response;
    }

    @GetMapping("/{id}")
    public Dtos.ProductDetailDto detail(@PathVariable Long id,
                                        @RequestParam(defaultValue = "90") int historyDays) {
        return productService.getDetail(id, historyDays);
    }

    @GetMapping("/{id}/offers")
    public List<Dtos.OfferDto> offers(@PathVariable Long id) {
        return productService.getOffers(id);
    }

    @GetMapping("/{id}/reviews")
    public List<Dtos.ReviewDto> reviews(@PathVariable Long id) {
        return productService.getReviews(id);
    }

    @GetMapping("/{id}/sentiment")
    public SentimentAnalyzer.Summary sentiment(@PathVariable Long id) {
        return productService.getSentiment(id);
    }

    @GetMapping("/{id}/price-history")
    public List<Dtos.PricePointDto> priceHistory(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "90") int days) {
        return productService.getPriceHistory(id, days);
    }

    @GetMapping("/{id}/forecast")
    public PriceForecastService.Forecast forecast(@PathVariable Long id) {
        return productService.getForecast(id);
    }

    @GetMapping("/{id}/similar")
    public List<Dtos.ProductSummaryDto> similar(@PathVariable Long id,
                                                @RequestParam(defaultValue = "6") int limit) {
        return productService.getSimilar(id, limit);
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
