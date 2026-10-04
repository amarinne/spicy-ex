package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Credits follow the source of each response, rather than the API used to fetch it. */
public final class SpicyOrgAttribution {
    public static final class Credit {
        public final String label;
        public final String url;
        public final int nameStart;

        private Credit(String label, String url) {
            this(label, url, -1);
        }

        private Credit(String label, String url, int nameStart) {
            this.label = label;
            this.url = url;
            this.nameStart = nameStart;
        }
    }

    private SpicyOrgAttribution() { }

    public static List<Credit> credits(LyricsDocument document) {
        return credits(document, true);
    }

    /** Display provenance without changing the canonical provider or catalog identity. */
    public static String sourceLabel(LyricsDocument document) {
        if (document == null) return "Unknown source";
        String provider = providerLabel(document);
        if (document.syncUpgradeProvenance == null) return provider;
        String donor = safe(document.syncUpgradeProvenance.donorProvider);
        if (donor.isEmpty()) donor = safe(document.syncUpgradeProvenance.donorSource);
        return donor.isEmpty() ? provider : provider + " + " + donor;
    }

    private static String providerLabel(LyricsDocument document) {
        if (SpicyOrgPolicy.isRestricted(document)) {
            switch (safe(document.spicyOrgSource)) {
                case "spicy_lyrics": return "Spicy Lyrics";
                case "apple_music": return "Apple Music";
                case "spotify": return "Spotify";
                case "musixmatch": return "Musixmatch";
                default: return "Unknown source";
            }
        }
        String provider = safe(document.provider);
        if (provider.toLowerCase(java.util.Locale.ROOT).endsWith(" cache")) {
            provider = provider.substring(0, provider.length() - " cache".length()).trim();
        }
        return provider.isEmpty() ? "unknown" : provider;
    }

    /** The source footer already displays the provider, so it only needs contributor links. */
    public static List<Credit> credits(LyricsDocument document, boolean includeProvider) {
        if (document == null) return Collections.emptyList();
        List<Credit> credits = new ArrayList<>();
        if (includeProvider && (SpicyOrgPolicy.isRestricted(document)
                || document.syncUpgradeProvenance != null)) {
            credits.add(new Credit((SpicyOrgPolicy.isRestricted(document) ? "Lyrics from " : "")
                    + sourceLabel(document), ""));
        }
        if (SpicyOrgPolicy.isRestricted(document)) {
            String source = safe(document.spicyOrgSource);
            if ("spicy_lyrics".equals(source)) {
                addContributor(credits, "uploaded by ", document.spicyOrgUploader, document.spicyOrgUploaderUrl);
                addContributor(credits, "made by ", document.spicyOrgMaker, document.spicyOrgMakerUrl);
            }
        }
        return Collections.unmodifiableList(credits);
    }

    private static void addContributor(List<Credit> credits, String prefix, String name, String url) {
        if (!safe(name).isEmpty()) credits.add(new Credit(prefix + safe(name), safe(url), prefix.length()));
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
