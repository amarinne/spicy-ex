package com.eza.spicyex.lyrics;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import com.eza.spicyex.lyrics.ai.AiChunkFailure;
import com.eza.spicyex.lyrics.ai.AiFailureReason;
import com.eza.spicyex.lyrics.ai.AiLayerFailures;
import com.eza.spicyex.lyrics.ai.AiLayerPanelPolicy;
import com.eza.spicyex.lyrics.ai.AiRequestFeedbackState;
import com.eza.spicyex.lyrics.session.*;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exercises production status projection and chip pixels without provider calls or saved data. */
final class AiFailureIndicatorInstrumentation {
    private static final int RED = 0xFFE91429, GREEN = 0xFF1ED760, GRAY = 0xFF8E8E93;

    static void run(Instrumentation runner) {
        List<String> failures = new ArrayList<>();
        runner.runOnMainSync(() -> {
            for (int scenario = 0; scenario < 6; scenario++) {
                Bundle status = new Bundle();
                status.putString("test", "ai-status-" + scenario);
                status.putInt("numtests", 6); status.putInt("current", scenario + 1);
                runner.sendStatus(1, status);
                try { check(runner, scenario); }
                catch (Throwable failure) { failures.add(scenario + ": " + failure); }
            }
        });
        Bundle result = new Bundle();
        result.putString("stream", failures.isEmpty() ? "PASS: 6 native AI status cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        result.putInt("expectedCases", 6); result.putInt("completedCases", 6 - failures.size());
        runner.finish(failures.isEmpty() ? -1 : 0, result);
    }

    private static void check(Instrumentation runner, int scenario) throws Exception {
        LyricsDocument canonical = new LyricsDocument();
        LyricsLine line = new LyricsLine(); line.text = "ichi"; canonical.lines.add(line);
        LyricSession session = LyricSession.of(CanonicalBase.fromDocument("fixture", canonical), 1);
        MeaningArtifact fallback = new MeaningArtifact(session.base.digest, "google",
                new LayerProvenance(LayerAuthority.MACHINE, "google", "google", 0),
                Collections.singletonList(new MeaningEntry(session.base.rows.get(0).rowId, "one", "en")), false);
        LayerFailure google = new LayerFailure(LayerFailure.Reason.TIMEOUT, "SocketTimeoutException", 0);
        LayerFailure ai = AiLayerFailures.toLayerFailure(AiChunkFailure.of(AiFailureReason.DELIVERY_UNKNOWN));
        LayerState state = session.meaning.processing(LayerAuthority.AI, "ai", "", "run");
        switch (scenario) {
            case 0: state = state.settled(null, google); break;
            case 1: state = state.settled(fallback, google); break;
            case 2: state = state.settled(fallback, ai); break;
            case 3: state = state.settled(null, ai); break;
            case 4: state = state.settled(fallback, ai); break;
            case 5:
                MeaningArtifact accepted = new MeaningArtifact(session.base.digest, "ai",
                        new LayerProvenance(LayerAuthority.AI, "fixture", "ai", 0),
                        fallback.allEntries().stream().map(entry -> (MeaningEntry) entry)
                                .collect(java.util.stream.Collectors.toList()), false);
                state = state.settled(accepted, LayerFailure.NONE); break;
            default: throw new AssertionError("unknown scenario");
        }
        LyricsDocument projected = LegacyDocumentComposer.compose(canonical, session.withMeaning(state));
        AiRequestFeedbackState feedback = new AiRequestFeedbackState();
        boolean running = false;
        if (scenario == 4) {
            feedback.started();
            running = feedback.isPending(projected.translationAiPending);
        }
        boolean aiFailed = !running && !projected.translationAiFailureToken.isEmpty();
        AiLayerPanelPolicy.Destination destination = AiLayerPanelPolicy.destination(
                running, projected.translationAiFailureToken, projected.translationFromAi);
        AiLayerPanelPolicy.Destination expected = scenario == 4 ? AiLayerPanelPolicy.Destination.RUNNING_STATUS
                : scenario == 5 ? AiLayerPanelPolicy.Destination.REVIEW
                : scenario >= 2 ? AiLayerPanelPolicy.Destination.FAILURE : AiLayerPanelPolicy.Destination.COMPOSER;
        require(destination == expected, "wrong panel route");
        if (scenario == 2 || scenario == 3) require("delivery_unknown".equals(projected.translationAiFailureToken),
                "unknown-delivery warning token lost");
        if (scenario == 1 || scenario == 2 || scenario == 4) require("one".equals(projected.lines.get(0).translatedText),
                "fallback output lost");
        ChipSpinnerDrawable sound = new ChipSpinnerDrawable(runner.getTargetContext());
        ChipSpinnerDrawable meaning = new ChipSpinnerDrawable(runner.getTargetContext());
        LyricsToggleSpinnerController controller = new LyricsToggleSpinnerController(sound, meaning);
        controller.setFailed(false, projected.translationFailed && !aiFailed && !running);
        controller.update(true, false, false, false, projected.translationFromAi,
                false, running, false, aiFailed);
        require(meaning.isAiFailed() == (scenario == 2 || scenario == 3), "wrong AI failure mark");
        require(meaning.isFailed() == (scenario == 0), "wrong ordinary retry mark");
        Bitmap pixels = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        try {
            meaning.setBounds(0, 0, 96, 96); meaning.draw(new Canvas(pixels));
            require((count(pixels, RED) > 0) == (scenario == 0 || scenario == 2 || scenario == 3), "wrong red pixels");
            // A retry has a green progress ring and a gray sparkle.
            if (scenario != 4) require((count(pixels, GREEN) > 0) == (scenario == 5), "wrong green pixels");
            require((count(pixels, GRAY) > 0) == (scenario == 4), "wrong pending pixels");
            File directory = new File(runner.getTargetContext().getCacheDir(), "behavior/ai-status");
            require(directory.isDirectory() || directory.mkdirs(), "evidence directory unavailable");
            try (FileOutputStream stream = new FileOutputStream(new File(directory, scenario + ".png"))) {
                require(pixels.compress(Bitmap.CompressFormat.PNG, 100, stream), "evidence write failed");
            }
        } finally { pixels.recycle(); controller.reset(); }
        if (scenario == 4) {
            feedback.reset();
            require(!feedback.isPending(false), "next song inherits pending mark");
            require(feedback.observe(false, false, "runtime_unavailable") == AiRequestFeedbackState.Outcome.NONE,
                    "next song inherits failure feedback");
        }
    }

    private static int count(Bitmap pixels, int color) {
        int count = 0;
        for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++)
            if (pixels.getPixel(x, y) == color) count++;
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
