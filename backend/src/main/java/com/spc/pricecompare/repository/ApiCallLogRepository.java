package com.spc.pricecompare.repository;

import com.spc.pricecompare.domain.ApiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ApiCallLogRepository extends JpaRepository<ApiCallLog, Long> {

    /** QuotaGuard's month-to-date counter for a given provider. */
    long countByPlatformCodeAndCalledAtAfter(String platformCode, Instant since);
}
