package com.eza.spicyex.diagnostics;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.eza.spicyex.BuildConfig;
import com.eza.spicyex.BuildStamp;
import com.eza.spicyex.CurrentLyricState;
import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.FeatureAvailability;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.LyricsFetchDiagnosticsState;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;

import java.time.Instant;
import de.robv.android.xposed.XposedBridge;

/** Maps allowlisted Spotify-process state to the shared product-neutral intake envelope. */
public final class SpicyDiagnosticReportFactory {
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private SpicyDiagnosticReportFactory() {
    }

    public static Draft createMetadataOnly(Context context, SettingsStore settings,
                                           String category, String description) {
        return create(context, settings, DiagnosticReportContract.newReportId(), category,
                description, null);
    }

    public static Draft createCaptured(Context context, SettingsStore settings,
                                       DiagnosticCaptureStore.FinishedCapture capture) {
        if (capture == null) throw new IllegalArgumentException("capture required");
        return create(context, settings, capture.reportId, capture.category, capture.description, capture);
    }

    private static Draft create(Context context, SettingsStore settings, String reportId,
                                String category, String description,
                                DiagnosticCaptureStore.FinishedCapture capture) {
        if (!DiagnosticReportContract.validReportId(reportId)
                || !DiagnosticReportContract.validCategory(category)
                || !DiagnosticReportContract.validDescription(description)) {
            throw new IllegalArgumentException("invalid diagnostic report");
        }
        long createdAt = capture == null ? System.currentTimeMillis() : capture.finishedAtWallMs;
        JsonObject root = new JsonObject();
        root.addProperty("envelopeVersion", 1);
        root.addProperty("reportId", reportId);
        root.addProperty("product", "spicy_ex");
        root.addProperty("productReportVersion", 2);
        root.addProperty("createdAtUtc", Instant.ofEpochMilli(createdAt).toString());
        root.addProperty("category", category);
        root.addProperty("description", description);
        root.add("commonMetadata", commonMetadata(context));
        SpicySetupCheckPolicy.Result setupChecks =
                SpicySetupCheckCollector.collect(context, settings);
        JsonObject product = productMetadata(settings, capture != null && capture.interrupted,
                setupChecks);
        root.add("productMetadata", product);
        root.add("capture", captureMetadata(capture, createdAt));
        root.add("rawDiagnostics", rawDiagnostics(settings, capture));
        String json = GSON.toJson(root);
        if (containsForbiddenFields(root)
                || DiagnosticReportContract.utf8Bytes(json) > DiagnosticReportContract.CLIENT_BODY_BYTES) {
            throw new IllegalArgumentException("diagnostic report too large");
        }
        return new Draft(reportId, category, description, json, issueTitle(category, reportId),
                issueBody(description, reportId, product), setupChecks);
    }

    private static JsonObject commonMetadata(Context context) {
        JsonObject common = new JsonObject();
        common.addProperty("appVersionName", bounded(BuildConfig.VERSION_NAME, 256));
        common.addProperty("appVersionCode", BuildConfig.VERSION_CODE);
        common.addProperty("buildType", bounded(BuildConfig.BUILD_TYPE, 64));
        common.addProperty("manufacturer", bounded(Build.MANUFACTURER, 256));
        common.addProperty("brand", bounded(Build.BRAND, 256));
        common.addProperty("model", bounded(Build.MODEL, 256));
        common.addProperty("device", bounded(Build.DEVICE, 256));
        common.addProperty("product", bounded(Build.PRODUCT, 256));
        common.addProperty("androidRelease", bounded(Build.VERSION.RELEASE, 64));
        common.addProperty("androidApi", Build.VERSION.SDK_INT);
        common.addProperty("androidSecurityPatch", bounded(Build.VERSION.SECURITY_PATCH, 64));
        common.addProperty("androidDisplay", bounded(Build.DISPLAY, 256));
        common.addProperty("androidIncremental", bounded(Build.VERSION.INCREMENTAL, 256));
        common.addProperty("buildFingerprint", bounded(Build.FINGERPRINT, 1024));
        common.add("xiaomiOsProperties", new JsonObject());
        JsonArray locales = new JsonArray();
        String tags = context.getResources().getConfiguration().getLocales().toLanguageTags();
        for (String tag : tags.split(",")) {
            if (locales.size() >= 8) break;
            if (!tag.trim().isEmpty()) locales.add(bounded(tag.trim(), 64));
        }
        common.add("locales", locales);
        JsonObject packages = new JsonObject();
        packages.add("spicy_ex", packageVersion(true, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        packages.add("spotify", installedPackage(context, "com.spotify.music"));
        common.add("packageVersions", packages);
        return common;
    }

    private static JsonObject productMetadata(SettingsStore settings, boolean interrupted,
                                              SpicySetupCheckPolicy.Result setupChecks) {
        JsonObject product = new JsonObject();
        product.addProperty("buildStampVersion", bounded(BuildStamp.VERSION, 128));
        product.addProperty("buildClue", bounded(BuildStamp.CLUE, 128));
        product.addProperty("versionCode", BuildConfig.VERSION_CODE);
        product.addProperty("networkCacheEpoch", bounded(BuildStamp.NETWORK_CACHE_EPOCH, 128));
        product.addProperty("flavor", "lite".equals(BuildConfig.FLAVOR) ? "lite" : "full");
        try {
            product.addProperty("xposedApiVersion", XposedBridge.getXposedVersion());
        } catch (Throwable ignored) {
            product.add("xposedApiVersion", JsonNull.INSTANCE);
        }
        product.addProperty("processIdentity", bounded(Application.getProcessName(), 64));
        JsonObject features = new JsonObject();
        features.addProperty("lyricsHooks", true);
        features.addProperty("translation", FeatureAvailability.translationAvailable());
        features.addProperty("transliteration", FeatureAvailability.transliterationAvailable());
        features.addProperty("fullscreenRenderer", settings.get(Settings.NATIVE_SPICY_ENABLED));
        features.addProperty("nowPlayingCard", true);
        features.addProperty("hyperGlowBridge", true);
        product.add("featureAvailability", features);

        LyricsFetchDiagnosticsState.Snapshot fetch = LyricsFetchDiagnosticsState.get();
        CurrentLyricState runtime = CurrentLyricState.get();
        String diagnosticLanguage = SpicyDiagnosticLanguage.resolve(
                runtime.language, fetch.language, runtime.originalLine);
        JsonObject fetchJson = new JsonObject();
        fetchJson.addProperty("source", boundedToken(fetch.sourceChosen, 32));
        fetchJson.addProperty("provider", boundedToken(fetch.provider, 64));
        fetchJson.addProperty("status", boundedToken(fetch.spicyQueryStatus, 32));
        fetchJson.addProperty("language", boundedToken(diagnosticLanguage, 32));
        fetchJson.addProperty("timingType", boundedToken(fetch.typeChosen, 32));
        fetchJson.addProperty("tokenPresent", fetch.tokenPresent);
        fetchJson.addProperty("cacheHit", "cache".equals(fetch.sourceChosen));
        fetchJson.addProperty("cacheWrite", fetch.cacheWrite);
        fetchJson.addProperty("nativeAvailable", fetch.candidatesSeen.contains("native"));
        fetchJson.addProperty("candidateCount", Math.max(0, fetch.candidateCount));
        fetchJson.addProperty("ageMs", fetch.recordedAtMs <= 0L ? 0L
                : Math.max(0L, System.currentTimeMillis() - fetch.recordedAtMs));
        product.add("lyricsFetchDiagnostics", fetchJson);

        product.addProperty("runtimeStatus", boundedToken(runtime.status, 32));
        product.addProperty("runtimeBackend", boundedToken(runtime.backend, 64));
        product.addProperty("runtimeLanguage", boundedToken(diagnosticLanguage, 32));
        product.addProperty("runtimePlaying", runtime.playing);
        if (runtime.updatedAtMs > 0L) {
            product.addProperty("runtimeStateAgeMs",
                    Math.max(0L, System.currentTimeMillis() - runtime.updatedAtMs));
        } else {
            product.add("runtimeStateAgeMs", JsonNull.INSTANCE);
        }
        product.addProperty("hyperGlowBridgeStatus", boundedToken(Diagnostics.hyperGlowBridgeStatus(), 32));
        product.addProperty("captureInterrupted", interrupted);
        product.add("currentMediaEvidence", currentMediaEvidence(runtime, fetch,
                diagnosticLanguage));
        product.add("setupChecks", setupChecks.toJson());
        return product;
    }

    private static JsonObject currentMediaEvidence(CurrentLyricState runtime,
                                                   LyricsFetchDiagnosticsState.Snapshot fetch,
                                                   String diagnosticLanguage) {
        JsonObject evidence = new JsonObject();
        boolean present = runtime.trackUri != null
                && runtime.trackUri.startsWith("spotify:track:")
                && runtime.title != null && !runtime.title.trim().isEmpty();
        evidence.addProperty("present", present);
        evidence.addProperty("trackUri", present ? bounded(runtime.trackUri,
                DiagnosticReportContract.MEDIA_METADATA_BYTES) : "");
        evidence.addProperty("title", present ? bounded(runtime.title,
                DiagnosticReportContract.MEDIA_METADATA_BYTES) : "");
        evidence.addProperty("artist", present ? bounded(runtime.artist,
                DiagnosticReportContract.MEDIA_METADATA_BYTES) : "");
        evidence.addProperty("album", present ? bounded(runtime.album,
                DiagnosticReportContract.MEDIA_METADATA_BYTES) : "");
        evidence.addProperty("source", present ? boundedToken(fetch.sourceChosen, 32) : "");
        evidence.addProperty("provider", present ? bounded(fetch.provider,
                DiagnosticReportContract.MEDIA_METADATA_BYTES) : "");
        evidence.addProperty("language", present ? bounded(diagnosticLanguage,
                DiagnosticReportContract.MEDIA_METADATA_BYTES) : "");
        evidence.addProperty("timingType", present ? boundedToken(fetch.typeChosen, 32) : "");
        evidence.addProperty("lineIndex", present ? Math.max(-1, Math.min(5000, runtime.lineIndex)) : -1);
        evidence.addProperty("originalLine", present ? bounded(runtime.originalLine,
                DiagnosticReportContract.LYRIC_LINE_BYTES) : "");
        evidence.addProperty("romanizedLine", present ? bounded(runtime.romanizedLine,
                DiagnosticReportContract.LYRIC_LINE_BYTES) : "");
        evidence.addProperty("translatedLine", present ? bounded(runtime.translatedLine,
                DiagnosticReportContract.LYRIC_LINE_BYTES) : "");
        if (present && runtime.updatedAtMs > 0L) {
            evidence.addProperty("stateAgeMs", Math.max(0L,
                    System.currentTimeMillis() - runtime.updatedAtMs));
        } else {
            evidence.add("stateAgeMs", JsonNull.INSTANCE);
        }
        return evidence;
    }

    private static JsonObject captureMetadata(DiagnosticCaptureStore.FinishedCapture capture,
                                              long finishedAtMs) {
        JsonObject object = new JsonObject();
        object.addProperty("outcome", capture == null ? "not_requested" : capture.outcome);
        if (capture == null) object.add("startedAtUtc", JsonNull.INSTANCE);
        else object.addProperty("startedAtUtc", Instant.ofEpochMilli(capture.startedAtWallMs).toString());
        object.addProperty("finishedAtUtc", Instant.ofEpochMilli(finishedAtMs).toString());
        object.add("previousDiagnosticLoggingEnabled", JsonNull.INSTANCE);
        object.addProperty("rootAccessStatus", "not_checked");
        object.add("commandFailures", new JsonArray());
        JsonObject truncation = new JsonObject();
        truncation.addProperty("capturedEvents", capture != null && capture.truncated);
        object.add("truncationFlags", truncation);
        object.addProperty("interrupted", capture != null && capture.interrupted);
        return object;
    }

    private static JsonObject rawDiagnostics(SettingsStore settings,
                                             DiagnosticCaptureStore.FinishedCapture capture) {
        JsonObject raw = new JsonObject();
        raw.addProperty("diagnosticEventsAndLogs", capture == null ? "" : capture.eventsJsonl);
        raw.addProperty("crashExcerpt", "");
        raw.addProperty("lsposedModuleLines", "");
        JsonObject runtime = new JsonObject();
        runtime.addProperty("lyricsProvider", "automatic");
        runtime.addProperty("translationEnabled", String.valueOf(settings.get(Settings.TRANSLATION_ENABLED)));
        runtime.addProperty("translationLanguage", bounded(settings.get(Settings.TRANSLATION_TARGET), 256));
        runtime.addProperty("transliterationEnabled", String.valueOf(settings.get(Settings.TRANSLITERATION_ENABLED)));
        runtime.addProperty("romanizationMode", bounded(settings.get(Settings.JAPANESE_READING_MODE), 256));
        runtime.addProperty("fullscreenEnabled", String.valueOf(settings.get(Settings.NATIVE_SPICY_ENABLED)));
        runtime.addProperty("nowPlayingCardEnabled", "true");
        runtime.addProperty("hyperGlowBridgeEnabled", String.valueOf(settings.get(Settings.HYPERGLOW_ENABLED)));
        raw.add("runtimeSettings", runtime);
        return raw;
    }

    private static JsonObject installedPackage(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return packageVersion(true, info.versionName == null ? "" : info.versionName,
                    Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageVersion(false, "missing", 0L);
        } catch (Throwable ignored) {
            return packageVersion(false, "unavailable", 0L);
        }
    }

    private static JsonObject packageVersion(boolean present, String name, long code) {
        JsonObject object = new JsonObject();
        object.addProperty("present", present);
        object.addProperty("versionName", bounded(name, 256));
        object.addProperty("versionCode", Math.max(0L, code));
        return object;
    }

    private static String issueTitle(String category, String reportId) {
        return "[Spicy EX] " + categoryLabel(category) + " — " + reportId;
    }

    private static String issueBody(String description, String reportId, JsonObject product) {
        StringBuilder body = new StringBuilder("## Description\n\n")
                .append(description.trim()).append("\n\n")
                .append("## Report details\n\n")
                .append("- **Report ID:** `").append(reportId).append("`\n")
                .append("- **Spicy EX:** `").append(markdownText(BuildConfig.VERSION_NAME))
                .append("` (`vC").append(BuildConfig.VERSION_CODE).append("`)\n")
                .append("- **Device:** ")
                .append(markdownText(bounded(Build.MANUFACTURER + " " + Build.MODEL, 256)))
                .append("\n")
                .append("- **Flavor:** `")
                .append(markdownText(product.get("flavor").getAsString())).append("`\n")
                .append("- **Runtime:** `")
                .append(markdownText(product.get("runtimeStatus").getAsString()))
                .append("` via `").append(markdownText(product.get("runtimeBackend").getAsString()))
                .append("`\n")
                .append("- **HyperGlow bridge:** `")
                .append(markdownText(product.get("hyperGlowBridgeStatus").getAsString()))
                .append("`\n\n");
        JsonObject media = product.getAsJsonObject("currentMediaEvidence");
        if (media != null && media.has("present") && media.get("present").getAsBoolean()) {
            body.append("## Song evidence\n\n")
                    .append("- **Song:** ").append(markdownText(media.get("title").getAsString()))
                    .append(" — ").append(markdownText(media.get("artist").getAsString())).append('\n')
                    .append("- **Spotify URI:** `")
                    .append(markdownText(media.get("trackUri").getAsString())).append("`\n")
                    .append("- **Lyrics:** source=`")
                    .append(markdownText(media.get("source").getAsString()))
                    .append("`, provider=`").append(markdownText(media.get("provider").getAsString()))
                    .append("`, language=`").append(markdownText(media.get("language").getAsString()))
                    .append("`, timing=`").append(markdownText(media.get("timingType").getAsString()))
                    .append("`\n\n");
        }
        return body.append("## Diagnostic data\n\n")
                .append("Private lyric text, captured events, and settings are stored under the report ID; ")
                .append("they are not included in this issue. See the [diagnostic data policy](")
                .append(DiagnosticReportContract.DATA_POLICY_URL).append(").")
                .toString();
    }

    public static String prettyJson(String json) {
        return PRETTY_GSON.toJson(JsonParser.parseString(json));
    }

    private static String categoryLabel(String category) {
        if (category == null || category.isEmpty()) return "Problem report";
        String words = category.replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String markdownText(String value) {
        if (value == null) return "";
        return value.replace('`', '\'').replace('\n', ' ').replace('\r', ' ');
    }

    private static String bounded(String value, int bytes) {
        return DiagnosticReportContract.utf8Prefix(value == null ? "" : value, bytes);
    }

    private static String boundedToken(String value, int chars) {
        String safe = DiagnosticEventBuffer.safeToken(value, chars);
        return safe.isEmpty() ? "unknown" : safe;
    }

    public static Draft fromJson(String json) {
        try {
            if (json == null || DiagnosticReportContract.utf8Bytes(json) > DiagnosticReportContract.CLIENT_BODY_BYTES) return null;
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (containsForbiddenFields(root)) return null;
            if (root.get("envelopeVersion").getAsInt() != 1
                    || !"spicy_ex".equals(root.get("product").getAsString())
                    || root.get("productReportVersion").getAsInt() != 2) return null;
            String reportId = root.get("reportId").getAsString();
            String category = root.get("category").getAsString();
            String description = root.get("description").getAsString();
            if (!DiagnosticReportContract.validReportId(reportId)
                    || !DiagnosticReportContract.validCategory(category)
                    || !DiagnosticReportContract.validDescription(description)) return null;
            JsonObject product = root.getAsJsonObject("productMetadata");
            SpicySetupCheckPolicy.Result setupChecks = setupChecksFromJson(product);
            return new Draft(reportId, category, description, json, issueTitle(category, reportId),
                    issueBody(description, reportId, product), setupChecks);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static SpicySetupCheckPolicy.Result setupChecksFromJson(JsonObject product) {
        if (product == null || !product.has("setupChecks")
                || !product.get("setupChecks").isJsonObject()) {
            return SpicySetupCheckPolicy.Result.unknown();
        }
        try {
            JsonObject object = product.getAsJsonObject("setupChecks");
            JsonArray failures = object.getAsJsonArray("setupFailures");
            java.util.List<String> failureKeys = new java.util.ArrayList<>();
            if (failures != null) {
                for (JsonElement failure : failures) {
                    if (failureKeys.size() >= 16) break;
                    failureKeys.add(boundedToken(failure.getAsString(), 64));
                }
            }
            return new SpicySetupCheckPolicy.Result(
                    object.get("runtimeHookActive").getAsBoolean(),
                    object.get("xposedApiAvailable").getAsBoolean(),
                    boundedToken(object.get("installationMode").getAsString(), 32),
                    object.get("spotifyMainProcess").getAsBoolean(),
                    object.get("moduleRuntimeAvailable").getAsBoolean(),
                    boundedToken(object.get("modulePackageStatus").getAsString(), 32),
                    object.get("internetPermissionGranted").getAsBoolean(),
                    object.get("requiredFeaturesAvailable").getAsBoolean(),
                    object.get("hyperGlowEnabled").getAsBoolean(),
                    boundedToken(object.get("hyperGlowBridgeStatus").getAsString(), 32),
                    boundedToken(object.get("setupState").getAsString(), 16),
                    failureKeys
            );
        } catch (Throwable ignored) {
            return SpicySetupCheckPolicy.Result.unknown();
        }
    }

    static boolean containsForbiddenFields(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) return false;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (containsForbiddenFields(child)) return true;
            }
            return false;
        }
        for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (FORBIDDEN_FIELDS.contains(entry.getKey()) || containsForbiddenFields(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<String> FORBIDDEN_FIELDS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "artworkId", "imageId", "currentLyricState",
                    "accessToken", "token", "cookie", "account", "androidId", "serial",
                    "imei", "ssid", "url", "responseBody"
            ));

    public static final class Draft {
        public final String reportId;
        public final String category;
        public final String description;
        public final String json;
        public final String issueTitle;
        public final String issueBody;
        public final SpicySetupCheckPolicy.Result setupChecks;

        Draft(String reportId, String category, String description, String json,
              String issueTitle, String issueBody, SpicySetupCheckPolicy.Result setupChecks) {
            this.reportId = reportId;
            this.category = category;
            this.description = description;
            this.json = json;
            this.issueTitle = issueTitle;
            this.issueBody = issueBody;
            this.setupChecks = setupChecks;
        }
    }
}
