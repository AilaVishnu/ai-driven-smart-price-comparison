package com.spc.pricecompare.web;

import com.spc.pricecompare.dto.Dtos;
import com.spc.pricecompare.provider.ProviderRegistry;
import com.spc.pricecompare.provider.ProviderStatus;
import com.spc.pricecompare.repository.CategoryRepository;
import com.spc.pricecompare.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CatalogController {

    private final ProviderRegistry providerRegistry;
    private final CategoryRepository categoryRepository;
    private final ProductService productService;

    /**
     * Reports which platforms are actually live and how much quota is left.
     *
     * <p>Deliberately candid: if no API key is configured, or a monthly budget
     * is spent, the interface says so rather than quietly showing fallback data
     * as though it came from a marketplace.
     */
    @GetMapping("/platforms")
    public List<Dtos.PlatformDto> platforms() {
        return providerRegistry.statuses().stream()
                .map(CatalogController::toDto)
                .toList();
    }

    @GetMapping("/categories")
    public List<Dtos.CategoryDto> categories() {
        return categoryRepository.findAll().stream()
                .map(c -> Dtos.CategoryDto.builder()
                        .id(c.getId())
                        .name(c.getName())
                        .slug(c.getSlug())
                        .productCount(0)
                        .build())
                .toList();
    }

    /** Products found on more than one platform - the comparison the app is for. */
    @GetMapping("/cross-platform")
    public List<Dtos.ProductSummaryDto> crossPlatform(@RequestParam(defaultValue = "8") int limit) {
        return productService.getCrossPlatform(limit);
    }

    @GetMapping("/deals")
    public List<Dtos.ProductSummaryDto> deals(@RequestParam(defaultValue = "24") int limit) {
        return productService.getDeals(limit);
    }

    private static Dtos.PlatformDto toDto(ProviderStatus status) {
        return Dtos.PlatformDto.builder()
                .code(status.platformCode())
                .displayName(status.displayName())
                .primary(status.primary())
                // Live means it actually works: configured, in quota, and not
                // failing its calls. A key that is valid but unsubscribed to
                // this particular API is configured and still not live.
                .live(status.configured() && status.quotaAvailable() && status.healthy())
                .requiresKey(status.primary())
                .quotaRemaining(status.quotaRemaining())
                .quotaUsedThisMonth(status.quotaUsedThisMonth())
                .monthlyQuota(status.monthlyQuota())
                .note(status.note())
                .build();
    }
}
