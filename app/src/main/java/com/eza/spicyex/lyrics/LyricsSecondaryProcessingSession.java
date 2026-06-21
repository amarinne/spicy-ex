package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

import de.robv.android.xposed.XposedBridge;

/** Coordinates secondary romanization/translation processing startup and callbacks. */
public final class LyricsSecondaryProcessingSession {
    private final Context context;
    private final SpotifyPlusConfig config;
    private final LyricsSecondaryProcessor processor;
    private final int processingVersion;
    private final String logTag;

    public LyricsSecondaryProcessingSession(
            Context context,
            SpotifyPlusConfig config,
            LyricsSecondaryProcessor processor,
            int processingVersion,
            String logTag
    ) {
        this.context = context;
        this.config = config;
        this.processor = processor;
        this.processingVersion = processingVersion;
        this.logTag = logTag;
    }

    public boolean start(
            String id,
            int generation,
            LyricsDocument snapshot,
            boolean showRomanization,
            RomanizationOptions options,
            LyricsSecondaryProcessor.CurrentGuard currentGuard,
            Callback callback
    ) {
        if (snapshot == null || snapshot.lines == null || snapshot.lines.isEmpty() || !snapshot.processingPending) return false;

        if (callback != null) callback.status("Enhancing " + label(snapshot) + "…");
        final String targetLang = config.get(Settings.TRANSLATION_TARGET);
        final String sourceLangOverride = "manual".equalsIgnoreCase(config.get(Settings.SOURCE_LANGUAGE_MODE))
                ? config.get(Settings.SOURCE_LANGUAGE) : null;
        final String effectiveSourceLang = sourceLangOverride != null ? sourceLangOverride : snapshot.language;

        XposedBridge.log(logTag + " secondary processing start target=" + targetLang + " source=" + effectiveSourceLang);
        processor.start(id, generation, snapshot, showRomanization, options, targetLang, effectiveSourceLang,
                currentGuard,
                new LyricsSecondaryProcessor.Callback() {
                    @Override
                    public void rerender(String message) {
                        if (callback != null) callback.rerender(snapshot, message);
                    }

                    @Override
                    public void progress(String message) {
                        if (callback != null) callback.progress(snapshot, message);
                    }

                    @Override
                    public void complete(String message, int changed) {
                        if (callback == null) return;
                        callback.complete(snapshot, message, changed);
                        LyricsDocumentProcessor.saveProcessedCache(context, snapshot, options, processingVersion);
                        XposedBridge.log(logTag + " secondary processing complete changed=" + changed + " lines=" + snapshot.lines.size());
                    }
                });
        return true;
    }

    private static String label(LyricsDocument snapshot) {
        String label = (snapshot.romanizationPending ? "readings" : "")
                + (snapshot.romanizationPending && snapshot.translationPending ? " + " : "")
                + (snapshot.translationPending ? "translation" : "");
        return label.isEmpty() ? "lyrics" : label;
    }

    public interface Callback {
        void status(String message);
        void rerender(LyricsDocument snapshot, String message);
        void progress(LyricsDocument snapshot, String message);
        void complete(LyricsDocument snapshot, String message, int changed);
    }
}
