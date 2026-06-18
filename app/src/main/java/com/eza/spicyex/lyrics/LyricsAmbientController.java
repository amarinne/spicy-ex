package com.eza.spicyex.lyrics;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.beautifullyrics.entities.AmbientBackgroundLayer;
import com.eza.spicyex.beautifullyrics.entities.AnimatedBackgroundView;
import com.eza.spicyex.beautifullyrics.entities.KawarpBackgroundView;

import java.io.IOException;

import de.robv.android.xposed.XposedBridge;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Owns the native lyrics ambient gradient and optional animated album-art background. */
public final class LyricsAmbientController {
    private static final String TAG = "[SpotifyPlusAmbientController]";

    private final Activity activity;
    private final OkHttpClient http;
    private final SpotifyPlusConfig config;
    private static final long PAGE_BACKGROUND_TRANSITION_MS = 500L;

    private final android.graphics.drawable.GradientDrawable pageBackground;
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();
    private AmbientBackgroundLayer animatedBackground;
    private FrameLayout animatedParent;
    private boolean animatedForceDark;
    private String lastArtImageId = "";
    private int[] currentPageColors;
    private ValueAnimator pageColorAnimator;

    public LyricsAmbientController(Activity activity, OkHttpClient http, SpotifyPlusConfig config) {
        this.activity = activity;
        this.http = http;
        this.config = config;
        int[] seed = {Color.rgb(30, 21, 18), Color.rgb(62, 19, 28), Color.rgb(16, 15, 16)};
        this.pageBackground = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, seed);
        this.currentPageColors = seed.clone();
    }

    public android.graphics.drawable.GradientDrawable pageBackground() {
        return pageBackground;
    }

    /** Pause/resume the animated background (e.g. while a settings modal is open). */
    public void pauseAnimation() {
        if (animatedBackground != null) animatedBackground.pauseRendering();
    }

    public void resumeAnimation() {
        if (animatedBackground != null) animatedBackground.resumeRendering();
    }

    /** Apply the "Animated background" setting live: show+resume or hide+pause the layer. */
    public void applyEnabled(boolean enabled) {
        applySettings(enabled, config == null || config.get(Settings.FORCE_DARK_BACKGROUND));
    }

    public void applySettings(boolean enabled, boolean forceDark) {
        if (animatedParent != null && enabled && animatedBackground == null) {
            createAnimatedLayer(animatedParent, forceDark);
        } else if (animatedBackground != null && forceDark != animatedForceDark) {
            ViewGroup parent = animatedBackground.asView().getParent() instanceof ViewGroup
                    ? (ViewGroup) animatedBackground.asView().getParent()
                    : animatedParent;
            if (parent != null) {
                animatedBackground.pauseRendering();
                parent.removeView(animatedBackground.asView());
                animatedBackground = null;
                createAnimatedLayer((FrameLayout) parent, forceDark);
            }
        }
        if (animatedBackground == null) return; // not attached this session — applies on next open
        if (enabled) {
            animatedBackground.asView().setVisibility(android.view.View.VISIBLE);
            animatedBackground.resumeRendering();
        } else {
            animatedBackground.pauseRendering();
            animatedBackground.asView().setVisibility(android.view.View.GONE);
        }
    }

    public void attachAnimatedLayer(FrameLayout parent) {
        animatedParent = parent;
        if (parent == null || config == null || !config.get(Settings.ENABLE_BACKGROUND)) return;
        createAnimatedLayer(parent, config.get(Settings.FORCE_DARK_BACKGROUND));
    }

    private void createAnimatedLayer(FrameLayout parent, boolean forceDark) {
        if (parent == null) return;
        // kawarp domain-warp shader needs AGSL (Android 13+); fall back to the CPU blob renderer
        // on older devices or if the RuntimeShader fails to compile.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            try {
                animatedBackground = new KawarpBackgroundView(activity, forceDark);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " kawarp shader unavailable, falling back: " + t);
                animatedBackground = new AnimatedBackgroundView(activity, null, parent);
            }
        } else {
            animatedBackground = new AnimatedBackgroundView(activity, null, parent);
        }
        animatedForceDark = forceDark;
        parent.addView(animatedBackground.asView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void updateForTrack(SpotifyTrack track, RunningState runningState) {
        int seed = LyricVisuals.parseSpotifyExtractedColor(track == null ? "" : track.color);
        boolean forceDark = config == null || config.get(Settings.FORCE_DARK_BACKGROUND);
        int[] colors = LyricVisuals.spicyColorBackgroundColors(seed, forceDark);
        pageBackground.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        animatePageBackgroundColors(colors);
        updateAnimatedBackgroundArt(track, runningState);
    }

    // Smoothly cross-fade the page gradient to the new track's colors (ported from codex branch)
    // instead of snapping, so track changes ease the ambient backdrop.
    private void animatePageBackgroundColors(int[] targetColors) {
        if (targetColors == null || targetColors.length == 0) return;
        if (currentPageColors == null || currentPageColors.length != targetColors.length) {
            currentPageColors = targetColors.clone();
            pageBackground.setColors(currentPageColors);
            return;
        }
        boolean unchanged = true;
        for (int i = 0; i < targetColors.length; i++) {
            if (currentPageColors[i] != targetColors[i]) { unchanged = false; break; }
        }
        if (unchanged) return;
        if (pageColorAnimator != null) pageColorAnimator.cancel();
        final int[] from = currentPageColors.clone();
        final int[] to = targetColors.clone();
        pageColorAnimator = ValueAnimator.ofFloat(0f, 1f);
        pageColorAnimator.setDuration(PAGE_BACKGROUND_TRANSITION_MS);
        pageColorAnimator.addUpdateListener(animation -> {
            float p = (Float) animation.getAnimatedValue();
            int[] frame = new int[to.length];
            for (int i = 0; i < to.length; i++) {
                frame[i] = (Integer) argbEvaluator.evaluate(p, from[i], to[i]);
            }
            pageBackground.setColors(frame);
            currentPageColors = frame;
        });
        pageColorAnimator.start();
    }

    private void updateAnimatedBackgroundArt(SpotifyTrack track, RunningState runningState) {
        AmbientBackgroundLayer background = animatedBackground;
        if (background == null) return;
        String imageId = track == null ? "" : safe(track.imageId);
        if (isBlank(imageId) || imageId.equals(lastArtImageId)) return;
        lastArtImageId = imageId;
        Request request = new Request.Builder()
                .url("https://i.scdn.co/image/" + Uri.encode(imageId))
                .get()
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                XposedBridge.log(TAG + " album art fetch failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) return;
                    byte[] data = response.body().bytes();
                    android.graphics.Bitmap art = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (art == null) return;
                    if (runningState != null && !runningState.isRunning()) return;
                    if (!imageId.equals(lastArtImageId)) return;
                    background.updateImage(art);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " album art decode failed: " + t);
                }
            }
        });
    }

    public interface RunningState {
        boolean isRunning();
    }
}
