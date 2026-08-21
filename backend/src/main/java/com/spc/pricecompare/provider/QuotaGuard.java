package com.spc.pricecompare.provider;

import com.spc.pricecompare.domain.ApiCallLog;
import com.spc.pricecompare.domain.CallStatus;
import com.spc.pricecompare.repository.ApiCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accounts for every outbound marketplace call and refuses to exceed the
 * configured monthly budget.
 *
 * <p>The free RapidAPI tiers are small - a few hundred calls a month - and a
 * runaway loop could burn the lot in seconds. So calls are counted rather than
 * estimated: the month-to-date total is seeded from {@code api_call_log} on
 * first use and then tracked in memory, which keeps the common path off the
 * database while still surviving a restart.
 *
 * <p>When RapidAPI reports its own remaining count in a response header, that
 * figure is trusted over the local one, since it reflects usage from anywhere.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuotaGuard {

    private final ApiCallLogRepository repository;

    private final Map<String, AtomicInteger> monthToDate = new ConcurrentHashMap<>();
    private final Map<String, Integer> reportedRemaining = new ConcurrentHashMap<>();
    private volatile String currentPeriod = period();

    /**
     * @return true if a call may proceed. A quota of 0 means unlimited.
     */
    public boolean tryAcquire(String platformCode, int monthlyQuota) {
        rollOverIfNewMonth();
        if (monthlyQuota <= 0) {
            return true;
        }
        Integer reported = reportedRemaining.get(platformCode);
        if (reported != null && reported <= 0) {
            log.warn("Quota exhausted for {} (provider reported 0 remaining)", platformCode);
            return false;
        }
        int used = counter(platformCode).get();
        if (used >= monthlyQuota) {
            log.warn("Quota exhausted for {}: {}/{} calls used this month", platformCode, used, monthlyQuota);
            return false;
        }
        return true;
    }

    /** Remaining calls, preferring the provider's own figure when it gave one. */
    public int remaining(String platformCode, int monthlyQuota) {
        rollOverIfNewMonth();
        Integer reported = reportedRemaining.get(platformCode);
        if (reported != null) {
            return Math.max(0, reported);
        }
        if (monthlyQuota <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, monthlyQuota - counter(platformCode).get());
    }

    public int used(String platformCode) {
        rollOverIfNewMonth();
        return counter(platformCode).get();
    }

    /**
     * Records a completed call. Runs in its own transaction so that logging a
     * call never rolls back with, or gets rolled back by, the caller's work.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String platformCode, String endpoint, CallStatus status, Integer quotaRemaining) {
        rollOverIfNewMonth();
        // Only calls the gateway would actually bill count against the budget.
        // A 4xx - an unsubscribed API, a bad key - is not charged, and counting
        // it would let a misconfiguration silently eat the month. A timeout is
        // counted because the request may well have been served.
        if (status == CallStatus.SUCCESS || status == CallStatus.TIMEOUT) {
            counter(platformCode).incrementAndGet();
        }
        if (quotaRemaining != null) {
            reportedRemaining.put(platformCode, quotaRemaining);
        }
        try {
            repository.save(ApiCallLog.builder()
                    .platformCode(platformCode)
                    .endpoint(endpoint.length() > 200 ? endpoint.substring(0, 200) : endpoint)
                    .status(status)
                    .quotaRemaining(quotaRemaining)
                    .calledAt(Instant.now())
                    .build());
        } catch (Exception e) {
            // Never let bookkeeping break a search.
            log.debug("Could not persist api_call_log row for {}: {}", platformCode, e.toString());
        }
    }

    private AtomicInteger counter(String platformCode) {
        return monthToDate.computeIfAbsent(platformCode, code -> {
            long persisted = repository.countByPlatformCodeAndCalledAtAfter(code, startOfMonth());
            return new AtomicInteger((int) persisted);
        });
    }

    private void rollOverIfNewMonth() {
        String now = period();
        if (!now.equals(currentPeriod)) {
            synchronized (this) {
                if (!now.equals(currentPeriod)) {
                    monthToDate.clear();
                    reportedRemaining.clear();
                    currentPeriod = now;
                    log.info("Quota counters reset for new period {}", now);
                }
            }
        }
    }

    private static String period() {
        LocalDate d = LocalDate.now(ZoneId.systemDefault());
        return d.getYear() + "-" + d.getMonthValue();
    }

    private static Instant startOfMonth() {
        return LocalDate.now(ZoneId.systemDefault())
                .withDayOfMonth(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();
    }
}
