package com.spc.pricecompare.ai;

import lombok.Builder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads a price series and answers the question a shopper actually has: buy
 * now, or wait?
 *
 * <p>Ordinary least squares is implemented directly rather than pulled from a
 * maths library. It is about twenty lines, it removes a dependency, and - since
 * this is the part of the system most likely to be asked "how does that
 * actually work" - having the arithmetic visible is worth more than the
 * convenience.
 *
 * <p>Two guards keep the output honest. Below a minimum number of observations
 * no trend is claimed at all. And R-squared is reported alongside every
 * forecast, so a confident-looking prediction drawn through scattered points is
 * visibly low-confidence rather than quietly misleading.
 */
@Service
public class PriceForecastService {

    /** Below this, any fitted line says more about noise than about price. */
    private static final int MIN_OBSERVATIONS = 5;

    /** Within this fraction of the period low, the current price counts as a good moment. */
    private static final double NEAR_LOW_TOLERANCE = 0.05;

    /** A predicted drop smaller than this is not worth waiting for. */
    private static final double MEANINGFUL_DROP = 0.03;

    public record PricePoint(Instant at, BigDecimal price) {
    }

    public enum Signal {
        BUY_NOW,
        WAIT,
        HOLD,
        INSUFFICIENT_DATA
    }

    public enum Trend {
        FALLING,
        RISING,
        STABLE,
        UNKNOWN
    }

    @Builder
    public record Forecast(
            int observations,
            BigDecimal currentPrice,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            BigDecimal averagePrice,
            BigDecimal movingAverage7d,
            /** Rupees per day; negative means falling. */
            double slopePerDay,
            /** Goodness of fit in [0, 1]; report this next to any prediction. */
            double rSquared,
            /** Standard deviation as a fraction of the mean. */
            double volatility,
            Trend trend,
            BigDecimal predicted14d,
            BigDecimal predicted30d,
            Signal signal,
            String rationale,
            boolean containsSimulatedData
    ) {
    }

    public Forecast forecast(List<PricePoint> points, boolean containsSimulatedData) {
        if (points == null || points.size() < MIN_OBSERVATIONS) {
            return Forecast.builder()
                    .observations(points == null ? 0 : points.size())
                    .currentPrice(points == null || points.isEmpty()
                            ? null : points.get(points.size() - 1).price())
                    .slopePerDay(0.0)
                    .rSquared(0.0)
                    .volatility(0.0)
                    .trend(Trend.UNKNOWN)
                    .signal(Signal.INSUFFICIENT_DATA)
                    .rationale("At least " + MIN_OBSERVATIONS
                            + " price observations are needed before a trend can be claimed")
                    .containsSimulatedData(containsSimulatedData)
                    .build();
        }

        List<PricePoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparing(PricePoint::at));

        int n = sorted.size();
        Instant origin = sorted.get(0).at();

        // x is days elapsed since the first observation, so the slope reads
        // directly as rupees per day.
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = Duration.between(origin, sorted.get(i).at()).toMinutes() / 1440.0;
            y[i] = sorted.get(i).price().doubleValue();
        }

        double meanX = mean(x);
        double meanY = mean(y);

        double covariance = 0.0;
        double varianceX = 0.0;
        for (int i = 0; i < n; i++) {
            covariance += (x[i] - meanX) * (y[i] - meanY);
            varianceX += (x[i] - meanX) * (x[i] - meanX);
        }

        // Every observation on the same day leaves the slope undefined.
        double slope = varianceX == 0.0 ? 0.0 : covariance / varianceX;
        double intercept = meanY - slope * meanX;

        double totalSumSquares = 0.0;
        double residualSumSquares = 0.0;
        for (int i = 0; i < n; i++) {
            double predicted = intercept + slope * x[i];
            residualSumSquares += Math.pow(y[i] - predicted, 2);
            totalSumSquares += Math.pow(y[i] - meanY, 2);
        }
        double rSquared = totalSumSquares == 0.0 ? 1.0 : Math.max(0.0, 1.0 - (residualSumSquares / totalSumSquares));

        double standardDeviation = Math.sqrt(totalSumSquares / n);
        double volatility = meanY == 0.0 ? 0.0 : standardDeviation / meanY;

        double current = y[n - 1];
        double min = min(y);
        double max = max(y);
        double latestX = x[n - 1];

        double predicted14 = Math.max(0.0, intercept + slope * (latestX + 14));
        double predicted30 = Math.max(0.0, intercept + slope * (latestX + 30));

        // A trend is only called when the drift over a month is material relative
        // to the price, which keeps ordinary noise from being read as direction.
        double monthlyDrift = slope * 30.0;
        Trend trend;
        if (meanY > 0 && Math.abs(monthlyDrift) / meanY < 0.02) {
            trend = Trend.STABLE;
        } else if (slope < 0) {
            trend = Trend.FALLING;
        } else if (slope > 0) {
            trend = Trend.RISING;
        } else {
            trend = Trend.STABLE;
        }

        Signal signal;
        String rationale;
        boolean nearLow = min > 0 && (current - min) / min <= NEAR_LOW_TOLERANCE;
        boolean meaningfulDropAhead = current > 0 && (current - predicted14) / current >= MEANINGFUL_DROP;

        // Tracks whether the advice rests on the fitted line or on a plain
        // observation. It decides which conclusions the confidence check below
        // is entitled to overrule.
        boolean modelDerived;

        if (nearLow) {
            signal = Signal.BUY_NOW;
            rationale = "Current price is within "
                    + Math.round(NEAR_LOW_TOLERANCE * 100)
                    + "% of the lowest price seen in this period";
            modelDerived = false;
        } else if (trend == Trend.FALLING && meaningfulDropAhead) {
            signal = Signal.WAIT;
            rationale = "Price is trending down; a further drop of about "
                    + Math.round(((current - predicted14) / current) * 100)
                    + "% is projected over the next two weeks";
            modelDerived = true;
        } else if (trend == Trend.RISING) {
            signal = Signal.BUY_NOW;
            rationale = "Price is trending upward, so waiting is likely to cost more";
            modelDerived = true;
        } else {
            signal = Signal.HOLD;
            rationale = "No strong movement either way; price has been broadly stable";
            modelDerived = false;
        }

        if (modelDerived && rSquared < 0.3) {
            // The line does not describe these points well, so any conclusion
            // drawn from its direction is withdrawn. Note this deliberately also
            // catches a BUY_NOW inferred from a rising trend: that reading is
            // exactly as dependent on the fit as a WAIT is. Only the near-low
            // case survives, because it compares observed values and involves no
            // model at all.
            signal = Signal.HOLD;
            rationale = "Price history is too scattered for a dependable trend (R-squared "
                    + round2(rSquared) + ")";
        }

        return Forecast.builder()
                .observations(n)
                .currentPrice(money(current))
                .minPrice(money(min))
                .maxPrice(money(max))
                .averagePrice(money(meanY))
                .movingAverage7d(money(movingAverage(sorted, 7)))
                .slopePerDay(round2(slope))
                .rSquared(round4(rSquared))
                .volatility(round4(volatility))
                .trend(trend)
                .predicted14d(money(predicted14))
                .predicted30d(money(predicted30))
                .signal(signal)
                .rationale(rationale)
                .containsSimulatedData(containsSimulatedData)
                .build();
    }

    /** Mean of the observations falling inside the trailing window. */
    private static double movingAverage(List<PricePoint> sorted, int days) {
        Instant cutoff = sorted.get(sorted.size() - 1).at().minus(Duration.ofDays(days));
        double total = 0.0;
        int count = 0;
        for (PricePoint p : sorted) {
            if (!p.at().isBefore(cutoff)) {
                total += p.price().doubleValue();
                count++;
            }
        }
        return count == 0 ? sorted.get(sorted.size() - 1).price().doubleValue() : total / count;
    }

    private static double mean(double[] values) {
        double total = 0.0;
        for (double v : values) {
            total += v;
        }
        return total / values.length;
    }

    private static double min(double[] values) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static double max(double[] values) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
