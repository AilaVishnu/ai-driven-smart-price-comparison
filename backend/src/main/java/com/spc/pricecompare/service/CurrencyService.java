package com.spc.pricecompare.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Converts source-currency prices into INR, the base currency of the whole
 * application.
 *
 * <p>Amazon.in and Flipkart quote INR natively, so this only matters for the
 * fallback sources, which quote USD. The rate is fetched from a free, keyless
 * endpoint and held for a day - exchange rates move far more slowly than the
 * cache interval, and a price comparison does not need intraday precision.
 *
 * <p>If the rate cannot be fetched the last known value is reused, and failing
 * that a hardcoded floor. Getting a slightly stale rate is very much better
 * than failing a search over it.
 */
@Service
@Slf4j
public class CurrencyService {

    private static final String RATES_URL = "https://open.er-api.com/v6/latest/USD";
    private static final Duration TTL = Duration.ofHours(24);

    /** Only used if the very first fetch fails; replaced as soon as one succeeds. */
    private static final BigDecimal FALLBACK_USD_INR = new BigDecimal("83.00");

    private final RestClient restClient;

    private volatile BigDecimal usdToInr = FALLBACK_USD_INR;
    private volatile Instant fetchedAt = Instant.EPOCH;
    private volatile boolean everFetched = false;

    public CurrencyService(RestClient providerRestClient) {
        this.restClient = providerRestClient;
    }

    /** Converts an amount in the given currency to INR. */
    public BigDecimal toInr(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        if (currency == null || currency.isBlank() || "INR".equalsIgnoreCase(currency)) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
        if ("USD".equalsIgnoreCase(currency)) {
            return amount.multiply(usdToInrRate()).setScale(2, RoundingMode.HALF_UP);
        }
        // Unknown currency: pass the figure through rather than inventing a rate,
        // and say so, so a wrong number is never silently presented as INR.
        log.warn("No conversion available for currency {} - value passed through unconverted", currency);
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal usdToInrRate() {
        if (isStale()) {
            refresh();
        }
        return usdToInr;
    }

    /** True when the rate is a real fetched value rather than the hardcoded floor. */
    public boolean isRateLive() {
        return everFetched;
    }

    public Instant rateFetchedAt() {
        return fetchedAt;
    }

    private boolean isStale() {
        return Duration.between(fetchedAt, Instant.now()).compareTo(TTL) > 0;
    }

    private synchronized void refresh() {
        if (!isStale()) {
            return;
        }
        try {
            Object body = restClient.get().uri(RATES_URL).retrieve().body(Object.class);
            Object rate = null;
            if (body instanceof Map<?, ?> map) {
                Object rates = map.get("rates");
                if (rates instanceof Map<?, ?> r) {
                    rate = r.get("INR");
                }
            }
            if (rate instanceof Number n && n.doubleValue() > 0) {
                usdToInr = BigDecimal.valueOf(n.doubleValue()).setScale(4, RoundingMode.HALF_UP);
                fetchedAt = Instant.now();
                everFetched = true;
                log.info("USD to INR rate refreshed: {}", usdToInr);
                return;
            }
            log.warn("Exchange rate response did not contain an INR rate; keeping {}", usdToInr);
        } catch (Exception e) {
            log.warn("Could not refresh exchange rate ({}); keeping {}", e.toString(), usdToInr);
        }
        // Back off so a persistently failing endpoint is not retried on every call.
        fetchedAt = Instant.now().minus(TTL).plus(Duration.ofMinutes(15));
    }
}
