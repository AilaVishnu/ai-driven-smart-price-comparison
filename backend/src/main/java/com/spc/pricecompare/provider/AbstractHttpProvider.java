package com.spc.pricecompare.provider;

import com.spc.pricecompare.domain.CallStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Shared plumbing for every HTTP-backed provider: quota check, the call itself,
 * quota-header extraction and call logging.
 *
 * <p>The contract subclasses rely on is that {@link #fetch} never throws. A
 * provider that is out of quota, timing out or returning malformed JSON yields
 * an empty Optional, so a failing marketplace degrades the result set rather
 * than failing the user's search.
 */
@Slf4j
public abstract class AbstractHttpProvider implements ProductProvider {

    protected final RestClient restClient;
    protected final QuotaGuard quotaGuard;
    protected final ProviderProperties properties;

    /** Key into {@code providers.sources.*}, e.g. "amazon-in". */
    private final String configKey;

    /**
     * Why this provider last failed, or null if it is healthy.
     *
     * <p>Exists so the platforms endpoint can distinguish a provider that is
     * merely configured from one that actually works. A key can be valid and
     * still be unsubscribed from a particular API, and reporting that as "Live"
     * would be exactly the kind of quiet inaccuracy this application is
     * supposed to avoid.
     */
    private volatile String healthNote;

    /** When that failure happened, so a stale one can be allowed to lapse. */
    private volatile long healthNoteAt;

    /**
     * How long a failure keeps a provider marked down.
     *
     * <p>Without this a single transient upstream error marks a provider off
     * forever, because the flag only clears on a later success and a provider
     * that is never called again never gets one. That is exactly what happened:
     * an upstream 500 during seeding left Flipkart showing as off, and queries
     * that map to no browsable category make no call at all, so nothing ever
     * cleared it. A ten minute old failure is not evidence about right now.
     */
    private static final long HEALTH_NOTE_TTL_MS = 10 * 60 * 1000L;

    protected AbstractHttpProvider(RestClient restClient,
                                   QuotaGuard quotaGuard,
                                   ProviderProperties properties,
                                   String configKey) {
        this.restClient = restClient;
        this.quotaGuard = quotaGuard;
        this.properties = properties;
        this.configKey = configKey;
    }

    protected ProviderProperties.Source config() {
        return properties.source(configKey);
    }

    @Override
    public boolean isPrimary() {
        return config().isPrimary();
    }

    public int remainingQuota() {
        return quotaGuard.remaining(platformCode(), config().getMonthlyQuota());
    }

    /**
     * Performs a GET and returns the decoded body as plain Maps and Lists.
     *
     * @param endpointLabel short label for the call log, not the full URL
     */
    protected Optional<Object> fetch(String url, Map<String, String> headers, String endpointLabel) {
        int quota = config().getMonthlyQuota();
        if (!quotaGuard.tryAcquire(platformCode(), quota)) {
            quotaGuard.record(platformCode(), endpointLabel, CallStatus.QUOTA_BLOCKED, 0);
            return Optional.empty();
        }

        try {
            ResponseEntity<Object> response = restClient.get()
                    .uri(url)
                    .headers(h -> headers.forEach(h::set))
                    .retrieve()
                    .toEntity(Object.class);

            Integer remaining = extractRemaining(response);
            quotaGuard.record(platformCode(), endpointLabel, CallStatus.SUCCESS, remaining);
            healthNote = null;
            healthNoteAt = 0L;
            return Optional.ofNullable(response.getBody());

        } catch (Exception e) {
            String message = e.toString();
            boolean timeout = message.toLowerCase().contains("timeout");
            quotaGuard.record(platformCode(), endpointLabel,
                    timeout ? CallStatus.TIMEOUT : CallStatus.FAILURE, null);
            healthNote = classifyFailure(message);
            healthNoteAt = System.currentTimeMillis();
            log.warn("{} call to {} failed: {}", platformCode(), endpointLabel, message);
            return Optional.empty();
        }
    }

    /**
     * Turns a raw exception into something a user can act on.
     *
     * <p>The distinction that matters most is 403 "not subscribed": the key is
     * valid, but this particular API was never subscribed to on RapidAPI. That
     * is a two-click fix, and saying so beats a generic failure message.
     */
    private String classifyFailure(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("not subscribed")) {
            return "Key is valid but not subscribed to this API - subscribe to it on RapidAPI "
                    + "(free plan), see docs/api-keys-setup.md";
        }
        if (lower.contains("403")) {
            return "Rejected with 403 - check the key and that the plan covers this API";
        }
        if (lower.contains("401")) {
            return "Rejected with 401 - the API key appears to be invalid";
        }
        if (lower.contains("429")) {
            return "Rate limited by the provider - the plan quota may be exhausted";
        }
        if (lower.contains("404")) {
            return "Endpoint not found - check the configured host and search-path";
        }
        if (lower.contains("timeout")) {
            return "Timed out - serving from cached data";
        }
        return "Last call failed - serving from cached data";
    }

    /**
     * Null when healthy; otherwise why the last call failed.
     *
     * <p>A configuration problem is reported until it is fixed, since it will
     * not resolve itself. A transient failure lapses, so one upstream blip does
     * not leave a working provider labelled off indefinitely.
     */
    public String healthNote() {
        String note = healthNote;
        if (note == null) {
            return null;
        }
        boolean configurationProblem = note.contains("not subscribed")
                || note.contains("invalid")
                || note.contains("plan covers")
                || note.contains("host and search-path");
        if (configurationProblem) {
            return note;
        }
        return System.currentTimeMillis() - healthNoteAt > HEALTH_NOTE_TTL_MS ? null : note;
    }

    /** RapidAPI reports true remaining quota in a response header; prefer it over local counting. */
    private Integer extractRemaining(ResponseEntity<Object> response) {
        for (String header : new String[]{"x-ratelimit-requests-remaining", "X-RateLimit-Requests-Remaining"}) {
            String value = response.getHeaders().getFirst(header);
            if (value != null) {
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    // Header present but unparseable - fall back to local counting.
                }
            }
        }
        return null;
    }

    protected static String encode(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
