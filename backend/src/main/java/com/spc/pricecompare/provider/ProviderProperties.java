package com.spc.pricecompare.provider;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "providers")
public class ProviderProperties {

    /** Single free RapidAPI key, shared by the Amazon.in and Flipkart adapters. */
    private String rapidapiKey = "";

    /**
     * How long a single search will wait for a provider before giving up on it
     * and returning what the others produced. Keeps interactive search snappy.
     */
    private int requestTimeoutMs = 8000;

    /**
     * Socket timeout on the HTTP client itself.
     *
     * <p>Deliberately much larger than {@link #requestTimeoutMs}. These are
     * different deadlines and conflating them was a mistake: Flipkart averages
     * around five seconds and returns 50KB payloads, so an 8s socket timeout
     * killed catalogue seeding outright. Seeding can afford to wait; a user
     * typing in the search box cannot, and the per-search deadline is enforced
     * separately in ProviderRegistry.
     */
    private int httpTimeoutMs = 30000;

    private int searchLimit = 20;


    private Map<String, Source> sources = new LinkedHashMap<>();

    /**
     * Placeholder text that people leave behind when copying a config template.
     * Treated as "no key" rather than as a key, because a garbage credential
     * produces a stream of 403s that look like a broken application instead of
     * an unfinished setup.
     */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "paste", "your-key", "your_key", "yourkey", "changeme",
            "change-me", "todo", "xxxx", "<", "replace"
    );

    public boolean hasRapidApiKey() {
        return sanitizedKey() != null;
    }

    /**
     * @return the key if it looks like a real one, otherwise null
     */
    public String sanitizedKey() {
        if (rapidapiKey == null || rapidapiKey.isBlank()) {
            return null;
        }
        String trimmed = rapidapiKey.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                return null;
            }
        }
        return trimmed;
    }

    /** True when a value is present but does not look like a usable key. */
    public boolean hasPlaceholderKey() {
        return rapidapiKey != null && !rapidapiKey.isBlank() && sanitizedKey() == null;
    }

    /** RapidAPI keys are around 50 characters; much shorter is worth flagging. */
    public boolean keyLooksTruncated() {
        String key = sanitizedKey();
        return key != null && key.length() < 20;
    }

    public Source source(String key) {
        return sources.getOrDefault(key, new Source());
    }

    @Getter
    @Setter
    public static class Source {
        private boolean enabled = true;
        /** True for the RapidAPI marketplaces. */
        private boolean primary = true;
        private String host;
        private String country;
        /** Monthly call budget enforced by QuotaGuard. 0 means unlimited. */
        private int monthlyQuota = 0;

        /**
         * How many pages to pull per category when seeding the catalogue.
         * Each page is one API call and roughly two dozen products, so this is
         * the main lever on catalogue size versus quota spend.
         */
        private int seedPages = 1;

        /**
         * Calls held back from seeding so interactive searches always have
         * budget left. Seeding stops rather than spending the last of it.
         */
        private int quotaReserve = 40;

        /**
         * Search path template, with {q} standing in for the encoded query.
         * Configurable because the RapidAPI search paths could not be confirmed
         * without a key - correcting one is a properties change, not a rebuild.
         */
        private String searchPath;
    }
}
