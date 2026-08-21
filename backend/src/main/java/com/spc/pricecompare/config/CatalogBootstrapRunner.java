package com.spc.pricecompare.config;

import com.spc.pricecompare.domain.Product;
import com.spc.pricecompare.service.CatalogMaintenanceService;
import com.spc.pricecompare.domain.Offer;
import com.spc.pricecompare.domain.PriceHistory;
import com.spc.pricecompare.domain.PriceSource;
import com.spc.pricecompare.provider.*;
import com.spc.pricecompare.repository.OfferRepository;
import com.spc.pricecompare.repository.PriceHistoryRepository;
import com.spc.pricecompare.repository.ProductRepository;
import com.spc.pricecompare.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Gives a cold install something to show.
 *
 * <p>Runs only when the catalogue is empty, so restarts cost nothing. That
 * matters more than usual here: with only a few hundred marketplace calls a
 * month, re-seeding on every boot would exhaust the budget in a day.
 *
 * <p>With no marketplace configured there is nothing to seed from, and the
 * startup log says so rather than filling the catalogue with stand-in data.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogBootstrapRunner implements ApplicationRunner {

    /** Seed terms used when marketplaces are available - broad enough to populate several categories. */
    private static final List<String> SEED_QUERIES = List.of(
            "iphone", "samsung galaxy", "laptop", "headphones",
            "smartwatch", "tablet", "ssd", "monitor");

    private static final int HISTORY_DAYS = 90;

    /** Matches PriceForecastService: below this, no trend is claimed. */
    private static final int MIN_HISTORY_FOR_FORECAST = 5;

    private final ProviderRegistry providerRegistry;
    private final ProviderProperties providerProperties;
    private final IngestionService ingestionService;
    private final ProductRepository productRepository;
    private final OfferRepository offerRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final CatalogMaintenanceService catalogMaintenanceService;

    @Value("${seed.catalog.enabled:true}")
    private boolean seedCatalogEnabled;

    @Value("${seed.price-history.enabled:false}")
    private boolean seedPriceHistoryEnabled;

    @Override
    public void run(ApplicationArguments args) {
        logProviderStatus();

        if (!seedCatalogEnabled) {
            log.info("Catalogue seeding disabled (seed.catalog.enabled=false)");
        } else if (productRepository.count() > 0) {
            log.info("Catalogue already populated ({} products); skipping general seed to preserve quota",
                    productRepository.count());
            // Seeding is still decided per platform: a marketplace that only
            // became available after the catalogue was first filled would
            // otherwise never contribute anything.
            seedFlipkartCategoriesIfEmpty();
        } else {
            seedCatalogue();
            seedFlipkartCategoriesIfEmpty();
        }

        catalogMaintenanceService.recategorizeFromTitles();

        if (seedPriceHistoryEnabled) {
            backfillPriceHistory();
        }

        Map<String, Long> stats = ingestionService.catalogueStats();
        log.info("Catalogue ready: {} products, {} offers, {} reviews",
                stats.get("products"), stats.get("offers"), stats.get("reviews"));
    }

    private void logProviderStatus() {
        log.info("--- Provider status ---");
        for (ProviderStatus status : providerRegistry.statuses()) {
            log.info("  {} [{}] {} - {}",
                    status.displayName(),
                    "marketplace",
                    status.configured() ? "configured" : "unavailable",
                    status.note());
        }
        // Distinguish the three ways a key can be wrong, because "no marketplace
        // data" has a different fix in each case and a single generic warning
        // sends people looking in the wrong place.
        if (providerProperties.hasPlaceholderKey()) {
            log.warn("The RapidAPI key still contains placeholder text, so it is being ignored. "
                    + "Paste your real key into "
                    + "backend/src/main/resources/application-local.properties "
                    + "(providers.rapidapi-key=...) and restart.");
        } else if (!providerProperties.hasRapidApiKey()) {
            log.warn("No RapidAPI key configured, so Amazon.in and Flipkart are unavailable. "
                    + "Searches will serve only what is already stored. Add a free key to "
                    + "backend/src/main/resources/application-local.properties - "
                    + "see docs/api-keys-setup.md.");
        } else {
            log.info("RapidAPI key detected; Amazon.in and Flipkart adapters are enabled.");
            if (providerProperties.keyLooksTruncated()) {
                log.warn("That key is unusually short ({} characters). RapidAPI keys are around 50 "
                                + "characters, so check nothing was cut off when pasting.",
                        providerProperties.sanitizedKey().length());
            }
        }
    }

    private void seedCatalogue() {
        List<ProductProvider> primaries = providerRegistry.usablePrimaries();

        if (primaries.isEmpty()) {
            log.warn("No usable marketplace, so there is nothing to seed from. Add a RapidAPI key "
                    + "and restart - see docs/api-keys-setup.md.");
            return;
        }

        log.info("Seeding catalogue from {} marketplace(s) across {} queries",
                primaries.size(), SEED_QUERIES.size());
        int ingested = 0;
        for (String query : SEED_QUERIES) {
            try {
                List<RawListing> listings =
                        providerRegistry.searchAll(query, providerProperties.getSearchLimit());
                if (!listings.isEmpty()) {
                    ingestionService.ingest(listings);
                    ingested += listings.size();
                }
            } catch (Exception e) {
                log.warn("Seed query [{}] failed: {}", query, e.toString());
            }
        }
        log.info("Seeded {} listings from marketplaces", ingested);
    }

    /**
     * Populates the catalogue with Flipkart products, one call per category.
     *
     * <p>Flipkart is reached by category rather than by keyword because its free
     * plan paywalls search. Seeding here is what gives the matching engine
     * Flipkart products to link live Amazon results against - without it, there
     * is nothing on the Flipkart side to compare with and the cross-platform
     * view stays empty.
     */
    private void seedFlipkartCategoriesIfEmpty() {
        FlipkartProvider flipkart = providerRegistry.all().stream()
                .filter(FlipkartProvider.class::isInstance)
                .map(FlipkartProvider.class::cast)
                .findFirst()
                .orElse(null);

        if (flipkart == null || !flipkart.isConfigured()) {
            return;
        }
        Map<String, String> categories = FlipkartProvider.categoryIds();

        // Decided per category so a run that partly timed out finishes next
        // time, rather than a single successful category marking the whole
        // platform as done.
        Map<String, String> pending = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> category : categories.entrySet()) {
            if (offerRepository.countByPlatformCodeAndCategorySlug("FLIPKART", category.getKey()) == 0) {
                pending.put(category.getKey(), category.getValue());
            }
        }

        if (pending.isEmpty()) {
            log.info("Flipkart catalogue already covers all {} categories; skipping seed", categories.size());
            return;
        }

        log.info("Seeding Flipkart catalogue: {} of {} categories still empty",
                pending.size(), categories.size());

        int total = 0;
        for (Map.Entry<String, String> category : pending.entrySet()) {
            try {
                List<RawListing> listings =
                        flipkart.fetchByCategory(category.getValue(), category.getKey(), 1);
                if (!listings.isEmpty()) {
                    ingestionService.ingest(listings);
                    total += listings.size();
                    log.info("  {} ({}) -> {} products", category.getKey(), category.getValue(), listings.size());
                } else {
                    log.warn("  {} ({}) -> nothing returned", category.getKey(), category.getValue());
                }
            } catch (Exception e) {
                log.warn("  Flipkart category {} failed: {}", category.getKey(), e.toString());
            }
        }
        log.info("Flipkart seed complete: {} listings ingested", total);
    }

    /**
     * Backfills a plausible 90-day series so the forecasting view has something
     * to work with before real history accrues.
     *
     * <p>Every generated row is stored with source = SIMULATED and rendered as
     * such by the interface. Observed prices are never fabricated, and the two
     * are never mixed silently - a forecast drawn partly from simulated points
     * says so.
     */
    private void backfillPriceHistory() {
        List<Offer> offers = offerRepository.findAll();
        int seeded = 0;

        for (Offer offer : offers) {
            if (offer.getPriceInr() == null || offer.getPriceInr().signum() <= 0) {
                continue;
            }
            // Backfill when history is too thin to forecast from, not merely when
            // it is absent. Ingestion writes one observed point per offer, so an
            // "is it empty" test skipped every offer and left the forecaster
            // permanently reporting insufficient data.
            if (priceHistoryRepository.findByOfferIdOrderByRecordedAtAsc(offer.getId()).size()
                    >= MIN_HISTORY_FOR_FORECAST) {
                continue;
            }

            // Seeded from the offer id so a given offer always produces the same
            // series - a chart that reshuffles on every restart looks broken.
            Random random = new Random(offer.getId() * 31L);
            double current = offer.getPriceInr().doubleValue();

            // Walk backwards from today, so the series lands exactly on the real
            // current price rather than drifting away from it.
            List<PriceHistory> points = new ArrayList<>(HISTORY_DAYS);
            double price = current;
            Instant now = Instant.now();

            // Starts at one day back: today already carries the real observed
            // point, and a simulated row alongside it would be both redundant and
            // misleading.
            for (int day = 1; day <= HISTORY_DAYS; day++) {
                points.add(PriceHistory.builder()
                        .offer(offer)
                        .price(BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP))
                        .recordedAt(now.minus(Duration.ofDays(day)))
                        .source(PriceSource.SIMULATED)
                        .build());

                // Mild drift plus noise, bounded so the series stays believable.
                double drift = 1.0 + (random.nextGaussian() * 0.006) + 0.0008;
                price = Math.max(current * 0.75, Math.min(current * 1.35, price * drift));
            }

            priceHistoryRepository.saveAll(points);
            seeded++;
        }

        if (seeded > 0) {
            log.info("Backfilled {} days of SIMULATED price history for {} offers "
                    + "(labelled as simulated wherever it is shown)", HISTORY_DAYS, seeded);
        }
    }
}
