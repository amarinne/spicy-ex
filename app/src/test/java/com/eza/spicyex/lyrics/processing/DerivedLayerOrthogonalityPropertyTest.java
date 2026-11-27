package com.eza.spicyex.lyrics.processing;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.BackgroundLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.TranslationConfig;
import com.eza.spicyex.lyrics.language.ChineseOutputStyle;
import com.eza.spicyex.lyrics.language.KoreanDisplayMode;
import com.eza.spicyex.lyrics.language.MandarinReadings;
import com.eza.spicyex.lyrics.language.RomanizationOptions;
import com.eza.spicyex.lyrics.language.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.language.SpicyRomanizer;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.settings.values.CyrillicMode;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Seeded property & differential tests verifying Sound/Meaning config-key orthogonality,
 * Standard Pinyin vs Mandarin eza式 isolation, pack-absent fallback, Cyrillic Auto resolution,
 * and CanonicalBase digest stability across derived-layer mutations.
 *
 * <p>Set {@code -Dspicy.dst.seed=<long>} to replay a single failing seed directly.
 */
public class DerivedLayerOrthogonalityPropertyTest {

    private static final int CONFIG_SEEDS = 200;
    private static final int TEXT_SEEDS = 150;

    private static final String[] CHINESE_MODES = {
            "pinyin", "pinyin-eza", "jyutping", "jyutping-eza", "auto", "auto-eza", "Off"
    };
    private static final String[] KOREAN_MODES = {
            KoreanDisplayMode.RR_STANDARD.value,
            KoreanDisplayMode.VN_PRONUNCIATION.value,
            "Off"
    };
    private static final String[] CYRILLIC_MODES = {
            SpicyRomanizer.CYRILLIC_RUSSIAN,
            SpicyRomanizer.CYRILLIC_UKRAINIAN,
            CyrillicMode.AUTO.storedLabel(),
            "Off"
    };
    private static final String[] TRANSLATION_BACKENDS = {
            "google_unofficial", "disabled"
    };
    private static final String[] TRANSLATION_TARGETS = {
            "en", "vi", "fr", "de", "ja", "ko", "zh-CN"
    };
    private static final String[] SOURCE_MODES = {
            "auto", "strict"
    };
    private static final String[] AI_PIPELINES = {
            "raw", "google_draft", "google_preview"
    };
    private static final String[] HAN_TOKENS = {
            "银行", "音乐", "重新", "乐谱", "中国", "嗯", "谱", "世界", "你好", "夜晚"
    };
    private static final String[] ASCII_TOKENS = {
            "BTS4", "2024", "hello", "A", "OK!", "x-y"
    };
    private static final String[] UKRAINIAN_MARKERS = {
            "і", "ї", "є", "ґ", "І", "Ї", "Є", "Ґ"
    };

    @Test
    public void soundAndMeaningConfigIdsAreStrictlyOrthogonal() {
        FakeAndroidContext context = new FakeAndroidContext();
        SharedPreferences prefs = context.getSharedPreferences(
                SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE);

        for (long seed : seeds(CONFIG_SEEDS, 0x0B74060L)) {
            Random rng = new Random(seed);
            String tag = "orthogonality seed=" + seed;

            RomanizationOptions soundOptsA = randomOptions(rng);
            String sourceLang = TRANSLATION_TARGETS[rng.nextInt(TRANSLATION_TARGETS.length)];

            // 1. Mutating every Meaning/Translation preference must never alter soundConfigId.
            applyRandomTranslationPrefs(prefs, rng);
            String soundIdBefore = LyricsDocumentProcessor.soundConfigId(context, soundOptsA, sourceLang);
            applyRandomTranslationPrefs(prefs, rng);
            String soundIdAfter = LyricsDocumentProcessor.soundConfigId(context, soundOptsA, sourceLang);
            assertEquals(tag + " translation prefs mutated soundConfigId", soundIdBefore, soundIdAfter);

            // 2. Mutating every Sound/Romanization preference must never alter meaningConfigId.
            String meaningIdBefore = LyricsDocumentProcessor.meaningConfigId(context);
            String meaningFromReaderBefore = TranslationConfig.read(SpotifyPlusConfig.from(context))
                    .identityInputs().configId();
            assertEquals(tag + " meaningConfigId diverged from TranslationConfig.identityInputs",
                    meaningIdBefore, meaningFromReaderBefore);

            prefs.edit()
                    .putBoolean(Settings.TRANSLITERATION_ENABLED.key, rng.nextBoolean())
                    .putString(Settings.CHINESE_MODE.key, pick(rng, CHINESE_MODES))
                    .putBoolean(Settings.CHINESE_TONES.key, rng.nextBoolean())
                    .putString(Settings.KOREAN_ROMANIZATION.key, pick(rng, KOREAN_MODES))
                    .putString(Settings.CYRILLIC_MODE.key, pick(rng, CYRILLIC_MODES))
                    .putBoolean(Settings.CYRILLIC_KEEP_SIGNS.key, rng.nextBoolean())
                    .apply();

            String meaningIdAfter = LyricsDocumentProcessor.meaningConfigId(context);
            assertEquals(tag + " romanization prefs mutated meaningConfigId",
                    meaningIdBefore, meaningIdAfter);
        }
    }

    @Test
    public void standardPinyinIsolationAndPackAbsentFallbackHoldAcrossRandomInputs() {
        boolean packReadyBefore = MandarinReadings.isReady();
        boolean packAvailable = MandarinReadings.available();

        for (long seed : seeds(TEXT_SEEDS, 0x91A140L)) {
            Random rng = new Random(seed);
            String tag = "pinyin seed=" + seed;

            StringBuilder line = new StringBuilder();
            int parts = 2 + rng.nextInt(6);
            for (int i = 0; i < parts; i++) {
                if (i > 0 && rng.nextBoolean()) line.append(' ');
                if (rng.nextBoolean()) {
                    line.append(pick(rng, HAN_TOKENS));
                } else {
                    line.append(pick(rng, ASCII_TOKENS));
                }
            }
            String input = line.toString();
            boolean tones = rng.nextBoolean();

            String first = SpicyJapaneseChineseProcessor.romanizeChinesePinyinLine(input, tones);
            String second = SpicyJapaneseChineseProcessor.romanizeChinesePinyinLine(input, tones);
            assertEquals(tag + " Standard Pinyin is non-deterministic", first, second);
            assertFalse(tag + " Standard Pinyin produced blank output for non-blank input",
                    first.trim().isEmpty());

            // Pack-absent fallback invariant for RomanizationOptions:
            String kr = pick(rng, KOREAN_MODES);
            String cy = pick(rng, CYRILLIC_MODES);
            boolean keepSigns = rng.nextBoolean();
            RomanizationOptions pinyinEza = new RomanizationOptions(
                    "pinyin-eza", kr, tones, cy, keepSigns);
            RomanizationOptions pinyinPlain = new RomanizationOptions(
                    "pinyin", kr, tones, cy, keepSigns);
            RomanizationOptions jyutpingEza = new RomanizationOptions(
                    "jyutping-eza", kr, tones, cy, keepSigns);

            // Cantonese eza式 requires no language model pack.
            assertEquals(tag + " Cantonese eza式 must stay EZA regardless of pack",
                    ChineseOutputStyle.EZA, jyutpingEza.chineseOutputStyle);

            if (!packAvailable) {
                assertEquals(tag + " Mandarin eza式 must fall back to CANONICAL when pack is absent",
                        ChineseOutputStyle.CANONICAL, pinyinEza.chineseOutputStyle);
                assertEquals(tag + " Pack-absent Mandarin eza式 cacheKey must match Standard Pinyin",
                        pinyinPlain.cacheKey(), pinyinEza.cacheKey());
            } else {
                assertEquals(tag + " Mandarin eza式 must stay EZA when pack is present",
                        ChineseOutputStyle.EZA, pinyinEza.chineseOutputStyle);
                assertNotEquals(tag + " Pack-present Mandarin eza式 cacheKey must differ from plain Pinyin",
                        pinyinPlain.cacheKey(), pinyinEza.cacheKey());
            }
        }

        if (!packReadyBefore) {
            assertFalse("Standard Pinyin randomized passes must never load the Mandarin g2pM reader",
                    MandarinReadings.isReady());
        }
    }

    @Test
    public void cyrillicAutoDetectionAndCanonicalDigestInvarianceHoldAcrossRandomDocuments() {
        for (long seed : seeds(TEXT_SEEDS, 0xC7B111L)) {
            Random rng = new Random(seed);
            String tag = "cyrillic-digest seed=" + seed;
            boolean injectUkrainian = rng.nextBoolean();
            boolean inBackground = rng.nextBoolean();

            LyricsDocument doc = new LyricsDocument();
            doc.trackId = "spotify:track:cy" + seed;
            int lineCount = 2 + rng.nextInt(6);
            int targetLine = rng.nextInt(lineCount);
            for (int i = 0; i < lineCount; i++) {
                LyricsLine line = new LyricsLine();
                line.text = "Привет мир " + i;
                if (injectUkrainian && i == targetLine && !inBackground) {
                    line.text = line.text + " " + pick(rng, UKRAINIAN_MARKERS);
                }
                if (rng.nextBoolean() || (injectUkrainian && i == targetLine && inBackground)) {
                    BackgroundLine bg = new BackgroundLine();
                    bg.text = "Ночь " + i + ((injectUkrainian && i == targetLine && inBackground)
                            ? " " + pick(rng, UKRAINIAN_MARKERS) : "");
                    line.backgroundLines.add(bg);
                }
                doc.lines.add(line);
            }

            RomanizationOptions autoCyr = new RomanizationOptions(
                    "pinyin", KoreanDisplayMode.RR_STANDARD.value, false,
                    CyrillicMode.AUTO.storedLabel(), rng.nextBoolean());
            RomanizationOptions resolved = autoCyr.forDocument(doc);
            String expectedCyr = injectUkrainian
                    ? SpicyRomanizer.CYRILLIC_UKRAINIAN : SpicyRomanizer.CYRILLIC_RUSSIAN;
            assertEquals(tag + " Cyrillic AUTO resolved to wrong preset",
                    expectedCyr, resolved.cyrillicMode);

            // Derived-layer text mutations must never alter CanonicalBase digest.
            String digestBefore = CanonicalBase.fromDocument(doc.trackId, doc).digest;
            for (LyricsLine line : doc.lines) {
                line.romanizedText = "mutated-sound-" + rng.nextInt(1000);
                line.translatedText = "mutated-meaning-" + rng.nextInt(1000);
            }
            String digestAfter = CanonicalBase.fromDocument(doc.trackId, doc).digest;
            assertEquals(tag + " derived-layer text mutation altered CanonicalBase digest",
                    digestBefore, digestAfter);
            assertTrue(tag + " CanonicalBase digest unexpectedly empty", !digestBefore.isEmpty());
        }
    }

    private static RomanizationOptions randomOptions(Random rng) {
        return new RomanizationOptions(
                pick(rng, CHINESE_MODES),
                pick(rng, KOREAN_MODES),
                rng.nextBoolean(),
                pick(rng, CYRILLIC_MODES),
                rng.nextBoolean());
    }

    private static void applyRandomTranslationPrefs(SharedPreferences prefs, Random rng) {
        prefs.edit()
                .putBoolean(Settings.TRANSLATION_ENABLED.key, rng.nextBoolean())
                .putString(Settings.TRANSLATION_BACKEND.key, pick(rng, TRANSLATION_BACKENDS))
                .putString(Settings.TRANSLATION_TARGET.key, pick(rng, TRANSLATION_TARGETS))
                .putString(Settings.SOURCE_LANGUAGE_MODE.key, pick(rng, SOURCE_MODES))
                .putString(Settings.SOURCE_LANGUAGE.key, pick(rng, TRANSLATION_TARGETS))
                .putString(Settings.AI_TRANSLATION_PIPELINE.key, pick(rng, AI_PIPELINES))
                .apply();
    }

    private static String pick(Random rng, String[] values) {
        return values[rng.nextInt(values.length)];
    }

    private static long[] seeds(int count, long baseSeed) {
        String single = System.getProperty("spicy.dst.seed");
        if (single != null && !single.trim().isEmpty()) {
            return new long[]{Long.parseLong(single.trim())};
        }
        long[] out = new long[count];
        for (int i = 0; i < count; i++) out[i] = baseSeed + i;
        return out;
    }
}
