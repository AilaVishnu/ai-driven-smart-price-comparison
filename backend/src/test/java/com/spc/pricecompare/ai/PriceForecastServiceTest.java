package com.spc.pricecompare.ai;

import com.spc.pricecompare.ai.PriceForecastService.Forecast;
import com.spc.pricecompare.ai.PriceForecastService.PricePoint;
import com.spc.pricecompare.ai.PriceForecastService.Signal;
import com.spc.pricecompare.ai.PriceForecastService.Trend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceForecastServiceTest {

    private final PriceForecastService service = new PriceForecastService();

    /** Builds a daily series, one point per day, ending today. */
    private static List<PricePoint> series(double... prices) {
        List<PricePoint> points = new ArrayList<>();
        Instant start = Instant.now().minus(Duration.ofDays(prices.length));
        for (int i = 0; i < prices.length; i++) {
            points.add(new PricePoint(start.plus(Duration.ofDays(i)), BigDecimal.valueOf(prices[i])));
        }
        return points;
    }

    @Test
    @DisplayName("A perfectly linear decline recovers the exact slope with R-squared of 1")
    void recoversKnownSlope() {
        // Falls by exactly 100 a day.
        Forecast forecast = service.forecast(
                series(50000, 49900, 49800, 49700, 49600, 49500, 49400), false);

        assertEquals(-100.0, forecast.slopePerDay(), 0.5,
                "The fitted slope should recover the generating rate");
        assertEquals(1.0, forecast.rSquared(), 0.001,
                "A perfectly linear series is perfectly explained by a line");
        assertEquals(Trend.FALLING, forecast.trend());
    }

    @Test
    @DisplayName("A rising series is identified and advises buying before it climbs further")
    void detectsRisingTrend() {
        Forecast forecast = service.forecast(
                series(40000, 41000, 42000, 43000, 44000, 45000, 46000), false);

        assertEquals(Trend.RISING, forecast.trend());
        assertTrue(forecast.slopePerDay() > 0);
        assertEquals(Signal.BUY_NOW, forecast.signal(),
                "Waiting on a rising price costs money");
    }

    @Test
    @DisplayName("A price sitting at its period low is flagged as a buying moment")
    void flagsBuyAtThePeriodLow() {
        Forecast forecast = service.forecast(
                series(60000, 58000, 56000, 54000, 52000, 50000), false);

        assertEquals(Signal.BUY_NOW, forecast.signal());
        assertEquals(new BigDecimal("50000.00"), forecast.minPrice());
        assertEquals(forecast.minPrice(), forecast.currentPrice(),
                "The latest point is the lowest, which is exactly the buy case");
    }

    @Test
    @DisplayName("A flat series is reported as stable rather than as a trend")
    void treatsFlatSeriesAsStable() {
        Forecast forecast = service.forecast(
                series(50000, 50000, 50000, 50000, 50000, 50000), false);

        assertEquals(Trend.STABLE, forecast.trend());
        assertEquals(0.0, forecast.slopePerDay(), 1e-6);
        assertEquals(0.0, forecast.volatility(), 1e-6);
    }

    @Test
    @DisplayName("Too few observations produce no claim at all")
    void refusesToGuessFromTooFewPoints() {
        Forecast forecast = service.forecast(series(50000, 49000, 48000), false);

        assertEquals(Signal.INSUFFICIENT_DATA, forecast.signal());
        assertEquals(Trend.UNKNOWN, forecast.trend());
        assertTrue(forecast.rationale().contains("observations"),
                "The reason should say what is missing");
    }

    @Test
    @DisplayName("A scattered series is not dressed up as a dependable trend")
    void lowConfidenceIsNotPresentedAsCertainty() {
        // Deliberately noisy, and not ending at the low, so the near-low rule
        // does not short-circuit the confidence check.
        Forecast forecast = service.forecast(
                series(50000, 61000, 47000, 65000, 44000, 63000, 58000), false);

        assertTrue(forecast.rSquared() < 0.5, "A line explains this badly");
        assertEquals(Signal.HOLD, forecast.signal(),
                "A weak fit must not become a confident recommendation");
    }

    @Test
    @DisplayName("Simulated history is carried through so the interface can label it")
    void propagatesSimulatedFlag() {
        Forecast forecast = service.forecast(
                series(50000, 49500, 49000, 48500, 48000, 47500), true);

        assertTrue(forecast.containsSimulatedData(),
                "Backfilled points must never be presented as observed");
    }

    @Test
    @DisplayName("Volatility rises with dispersion")
    void volatilityReflectsDispersion() {
        double calm = service.forecast(series(50000, 50100, 49900, 50050, 49950, 50000), false).volatility();
        double wild = service.forecast(series(50000, 65000, 40000, 62000, 38000, 55000), false).volatility();

        assertTrue(wild > calm, "A jumpy series should report higher volatility");
    }

    @Test
    @DisplayName("Null and empty input are handled without throwing")
    void handlesEmptyInput() {
        assertEquals(Signal.INSUFFICIENT_DATA, service.forecast(null, false).signal());
        assertEquals(Signal.INSUFFICIENT_DATA, service.forecast(List.of(), false).signal());
    }
}
