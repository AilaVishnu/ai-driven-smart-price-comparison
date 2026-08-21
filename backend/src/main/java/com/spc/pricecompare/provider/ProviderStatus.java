package com.spc.pricecompare.provider;

import lombok.Builder;

/** What GET /api/platforms reports for one source. */
@Builder
public record ProviderStatus(
        String platformCode,
        String displayName,
        boolean primary,
        boolean configured,
        /** False when the last real call to this provider failed. */
        boolean healthy,
        boolean quotaAvailable,
        int quotaRemaining,
        int quotaUsedThisMonth,
        int monthlyQuota,
        String note
) {
}
