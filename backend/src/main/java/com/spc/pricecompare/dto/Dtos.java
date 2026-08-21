package com.spc.pricecompare.dto;

import com.spc.pricecompare.ai.PriceForecastService;
import com.spc.pricecompare.ai.SentimentAnalyzer;
import com.spc.pricecompare.ai.TopsisScoringService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response shapes for the REST API.
 *
 * <p>Grouped in one file because they are small records with no behaviour;
 * scattering three dozen one-line types across as many files would make the
 * contract harder to read, not easier.
 */
public final class Dtos {

    private Dtos() {
    }

    // ---------------------------------------------------------------- auth

    public record RegisterRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 190) String email,
            @NotBlank @Size(min = 8, max = 100) String password
    ) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    @Builder
    public record AuthResponse(String token, UserDto user, long expiresInMs) {
    }

    @Builder
    public record UserDto(Long id, String name, String email, String role, Instant createdAt) {
    }

    // ------------------------------------------------------------ products

    @Builder
    public record OfferDto(
            Long id,
            String platformCode,
            String platformName,
            String title,
            String url,
            String imageUrl,
            BigDecimal price,
            BigDecimal originalPrice,
            BigDecimal discountPct,
            BigDecimal rating,
            Integer ratingCount,
            Boolean inStock,
            Integer deliveryDays,
            String warranty,
            String returnPolicy,
            String seller,
            Instant fetchedAt,
            /** True for the cheapest in-stock offer of this product. */
            boolean bestPrice
    ) {
    }

    @Builder
    public record ProductSummaryDto(
            Long id,
            String title,
            String brand,
            String category,
            String imageUrl,
            BigDecimal bestPrice,
            BigDecimal highestPrice,
            /** What buying the cheapest option saves against the dearest listing. */
            BigDecimal potentialSaving,
            String bestPlatformCode,
            String bestPlatformName,
            int offerCount,
            long platformCount,
            BigDecimal rating,
            Integer ratingCount,
            BigDecimal maxDiscountPct,
            Boolean inStock,
            /** TOPSIS closeness, 0-100. Null when the result set was too small to rank. */
            Double valueScore
    ) {
    }

    @Builder
    public record ProductDetailDto(
            ProductSummaryDto summary,
            String description,
            List<OfferDto> offers,
            List<ReviewDto> reviews,
            SentimentAnalyzer.Summary sentiment,
            PriceForecastService.Forecast forecast,
            List<PricePointDto> priceHistory,
            List<ProductSummaryDto> similar
    ) {
    }

    @Builder
    public record ReviewDto(
            Long id,
            String author,
            BigDecimal rating,
            String body,
            Instant reviewDate,
            BigDecimal sentimentScore,
            String sentimentLabel
    ) {
    }

    @Builder
    public record PricePointDto(Instant at, BigDecimal price, String platformCode, String source) {
    }

    @Builder
    public record SearchResponse(
            String query,
            /** How the query was understood, shown back to the user as chips. */
            List<String> interpretedAs,
            List<ProductSummaryDto> products,
            int page,
            int size,
            long totalResults,
            int totalPages,
            /** True when live provider calls contributed, rather than cache alone. */
            boolean fetchedLive,
            List<String> sourcesUsed
    ) {
    }

    // ------------------------------------------------------------- compare

    public record CompareRequest(
            @NotEmpty List<Long> productIds,
            /** Criterion key to weight, e.g. {"price": 40, "rating": 30}. Normalised server-side. */
            Map<String, Double> weights
    ) {
    }

    @Builder
    public record CompareResponse(
            List<ProductDetailDto> products,
            List<TopsisScoringService.Scored> ranking,
            Long winnerProductId,
            String winnerReason,
            Map<String, Double> weightsUsed,
            String note
    ) {
    }

    // ------------------------------------------------------------ platform

    @Builder
    public record PlatformDto(
            String code,
            String displayName,
            String baseUrl,
            boolean primary,
            boolean live,
            boolean requiresKey,
            int quotaRemaining,
            int quotaUsedThisMonth,
            int monthlyQuota,
            String note
    ) {
    }

    @Builder
    public record CategoryDto(Long id, String name, String slug, long productCount) {
    }

    // ------------------------------------------------------------- account

    @Builder
    public record FavoriteDto(Long id, ProductSummaryDto product, Instant createdAt) {
    }

    @Builder
    public record SearchHistoryDto(Long id, String query, int resultCount, Instant createdAt) {
    }

    @Builder
    public record ComparisonHistoryDto(
            Long id,
            List<Long> productIds,
            Long winnerProductId,
            Instant createdAt
    ) {
    }

    public record FavoriteRequest(@NotBlank String productId) {
    }

    // --------------------------------------------------------------- error

    @Builder
    public record ApiError(
            int status,
            String error,
            String message,
            String path,
            Instant timestamp,
            Map<String, String> fieldErrors
    ) {
    }
}
