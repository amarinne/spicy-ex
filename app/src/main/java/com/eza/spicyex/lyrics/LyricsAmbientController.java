package com.eza.spicyex.lyrics;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.eza.spicyex.FeatureAvailability;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.beautifullyrics.entities.AmbientBackgroundLayer;
import com.eza.spicyex.beautifullyrics.entities.KawarpBackgroundView;

import java.io.IOException;

import com.eza.spicyex.xposed.XpLog;
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
    private static final int ART_DECODE_TARGET_PX = 384;
    private static final int HEADER_ART_TARGET_PX = 220;

    private final Activity activity;
    private final OkHttpClient http;
    private final SpotifyPlusConfig config;
    private static final long PAGE_BACKGROUND_TRANSITION_MS = 500L;

    private final android.graphics.drawable.GradientDrawable pageBackground;
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();
    private AmbientBackgroundLayer animatedBackground;
    private FrameLayout animatedParent;
    private boolean animatedForceDark;
    private volatile String desiredArtImageId = "";
    private volatile String appliedArtImageId = "";
    private volatile String inFlightArtImageId = "";
    private volatile AmbientBackgroundLayer inFlightArtTarget;
    private volatile Call inFlightArtCall;
    private volatile android.graphics.Bitmap lastArtBitmap;
    private boolean playing = true;
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

    public int primaryBackgroundColor() {
        return (currentPageColors != null && currentPageColors.length > 0)
                ? currentPageColors[0] : Color.BLACK;
    }

    public int secondaryTextColor() {
        int base = primaryBackgroundColor();
        float whiteAmount = 0.72f;
        int r = Math.round(255f * whiteAmount + Color.red(base) * (1f - whiteAmount));
        int g = Math.round(255f * whiteAmount + Color.green(base) * (1f - whiteAmount));
        int b = Math.round(255f * whiteAmount + Color.blue(base) * (1f - whiteAmount));
        return Color.rgb(Math.min(255, r), Math.min(255, g), Math.min(255, b));
    }

    public void fetchHeaderArtwork(String imageId, HeaderArtCallback callback) {
        if (isBlank(imageId) || callback == null) return;
        if (lastArtBitmap != null && imageId.equals(appliedArtImageId)) {
            callback.onArtwork(imageId, lastArtBitmap);
            return;
        }
        Request request = new Request.Builder()
                .url("https://i.scdn.co/image/" + Uri.encode(imageId))
                .get()
                .build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                XpLog.log(TAG + " header art fetch failed: " + e.getMessage());
                activity.runOnUiThread(() -> callback.onFailure(imageId));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        activity.runOnUiThread(() -> callback.onFailure(imageId));
                        return;
                    }
                    byte[] data = response.body().bytes();
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    android.graphics.BitmapFactory.Options decode = new android.graphics.BitmapFactory.Options();
                    decode.inSampleSize = calculateInSampleSize(
                            bounds.outWidth, bounds.outHeight, HEADER_ART_TARGET_PX);
                    android.graphics.Bitmap art = android.graphics.BitmapFactory.decodeByteArray(
                            data, 0, data.length, decode);
                    if (art == null) {
                        activity.runOnUiThread(() -> callback.onFailure(imageId));
                        return;
                    }
                    activity.runOnUiThread(() -> callback.onArtwork(imageId, art));
                } catch (Throwable t) {
                    XpLog.log(TAG + " header art decode failed: " + t);
                    activity.runOnUiThread(() -> callback.onFailure(imageId));
                }
            }
        });
    }

    public interface HeaderArtCallback {
        void onArtwork(String imageId, android.graphics.Bitmap bitmap);

        void onFailure(String imageId);
    }

    /** Pause/resume the animated background (e.g. while a settings modal is open). */
    public void pauseAnimation() {
        if (animatedBackground != null) animatedBackground.pauseRendering();
    }

    public void resumeAnimation() {
        if (animatedBackground != null) animatedBackground.resumeRendering();
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        if (animatedBackground instanceof KawarpBackgroundView) {
            ((KawarpBackgroundView) animatedBackground).setPlaying(playing);
        }
    }

    /** BPM of the current track (0/negative disables it) - drives the background's beat pulse. */
    public void updateBeatTempo(float bpm) {
        if (animatedBackground instanceof KawarpBackgroundView) {
            ((KawarpBackgroundView) animatedBackground).setBeatTempoBpm(bpm);
        }
    }

    /** Real audio level (0..1) from AudioReactiveController - see NativeSpicyLyricsHook. */
    public void updateAudioLevel(float level0to1) {
        if (animatedBackground instanceof KawarpBackgroundView) {
            ((KawarpBackgroundView) animatedBackground).setAudioLevel(level0to1);
        }
    }

    /** Apply the "Animated background" setting live: show+resume or hide+pause the layer. */
    public void applyEnabled(boolean enabled) {
        applySettings(enabled ? LyricsBackgroundStyle.ANIMATED_TEXTURE
                        : LyricsBackgroundStyle.GRADIENT,
                config == null || config.get(Settings.FORCE_DARK_BACKGROUND));
    }

    public void applySettings(String style, boolean forceDark) {
        String normalized = LyricsBackgroundStyle.normalize(style);
        boolean enabled = LyricsBackgroundStyle.usesTexture(normalized);
        boolean animated = LyricsBackgroundStyle.isAnimated(normalized);
        if (animatedParent != null && enabled && animatedBackground == null) {
            createAnimatedLayer(animatedParent, forceDark, animated);
        } else if (forceDark != animatedForceDark) {
            if (animatedBackground instanceof KawarpBackgroundView) {
                ((KawarpBackgroundView) animatedBackground).setForceDark(forceDark);
            }
            animatedForceDark = forceDark;
        }
        if (animatedBackground == null) return; // not attached this session — applies on next open
        if (animatedBackground instanceof KawarpBackgroundView) {
            ((KawarpBackgroundView) animatedBackground).setMotionEnabled(animated);
        }
        if (enabled) {
            animatedBackground.asView().setVisibility(android.view.View.VISIBLE);
            animatedBackground.resumeRendering();
        } else {
            animatedBackground.pauseRendering();
            animatedBackground.asView().setVisibility(android.view.View.GONE);
        }
    }

    public void attachAnimatedLayer(FrameLayout parent, String style, boolean forceDark) {
        animatedParent = parent;
        if (!FeatureAvailability.animatedBackgroundAvailable()) return;
        if (parent == null || !LyricsBackgroundStyle.usesTexture(style)) return;
        createAnimatedLayer(parent, forceDark, LyricsBackgroundStyle.isAnimated(style));
    }

    private void createAnimatedLayer(FrameLayout parent, boolean forceDark, boolean animated) {
        // Guarded here as well as at the call sites: a pref persisted on a newer device (backup
        // restore, shared prefs copy) must not resurrect the layer on hardware that cannot run it.
        if (parent == null || !FeatureAvailability.animatedBackgroundAvailable()) return;
        try {
            KawarpBackgroundView background = new KawarpBackgroundView(activity, forceDark);
            background.setPlaying(playing);
            background.setMotionEnabled(animated);
            animatedBackground = background;
        } catch (Throwable t) {
            XpLog.log(TAG + " ambient background unavailable: " + t);
            animatedBackground = null;
            return;
        }
        animatedForceDark = forceDark;
        appliedArtImageId = "";
        Call previousCall = inFlightArtCall;
        if (previousCall != null) previousCall.cancel();
        inFlightArtCall = null;
        inFlightArtImageId = "";
        inFlightArtTarget = null;
        parent.addView(animatedBackground.asView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyAnimatedPalette(currentPageColors);
        if (lastArtBitmap != null) {
            animatedBackground.updateImage(lastArtBitmap);
            appliedArtImageId = desiredArtImageId;
        }
        if (!isBlank(desiredArtImageId)) {
            updateAnimatedBackgroundArt(desiredArtImageId, null);
        }
    }

    public void updateForTrack(SpotifyTrack track, RunningState runningState) {
        updateBeatTempo(0f); // cleared until the new track's tempo fetch (see TrackTempoFetcher) resolves
        int seed = LyricVisuals.parseSpotifyExtractedColor(track == null ? "" : track.color);
        boolean forceDark = config == null || config.get(Settings.FORCE_DARK_BACKGROUND);
        int[] colors = LyricVisuals.spicyColorBackgroundColors(seed, forceDark);
        pageBackground.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        animatePageBackgroundColors(colors);
        applyAnimatedPalette(colors);
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
        desiredArtImageId = imageId;
        updateAnimatedBackgroundArt(imageId, runningState);
    }

    private void updateAnimatedBackgroundArt(String imageId, RunningState runningState) {
        AmbientBackgroundLayer background = animatedBackground;
        if (background == null) return;
        if (isBlank(imageId) || imageId.equals(appliedArtImageId)) return;
        if (imageId.equals(inFlightArtImageId) && background == inFlightArtTarget) return;
        Call previousCall = inFlightArtCall;
        if (previousCall != null) previousCall.cancel();
        inFlightArtImageId = imageId;
        inFlightArtTarget = background;
        Request request = new Request.Builder()
                .url("https://i.scdn.co/image/" + Uri.encode(imageId))
                .get()
                .build();
        Call artCall = http.newCall(request);
        inFlightArtCall = artCall;
        artCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                XpLog.log(TAG + " album art fetch failed: " + e.getMessage());
                if (imageId.equals(inFlightArtImageId) && background == inFlightArtTarget) {
                    inFlightArtImageId = "";
                    inFlightArtTarget = null;
                    if (inFlightArtCall == call) inFlightArtCall = null;
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) return;
                    if (isStaleArtRequest(imageId, background, runningState)) return;
                    byte[] data = response.body().bytes();
                    if (isStaleArtRequest(imageId, background, runningState)) return;
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                    android.graphics.BitmapFactory.Options decode = new android.graphics.BitmapFactory.Options();
                    decode.inSampleSize = calculateInSampleSize(
                            bounds.outWidth, bounds.outHeight, ART_DECODE_TARGET_PX);
                    android.graphics.Bitmap art = android.graphics.BitmapFactory.decodeByteArray(
                            data, 0, data.length, decode);
                    if (art == null) return;
                    if (isStaleArtRequest(imageId, background, runningState)) {
                        art.recycle();
                        return;
                    }
                    lastArtBitmap = art;
                    background.updateImage(art);
                    appliedArtImageId = imageId;
                } catch (Throwable t) {
                    XpLog.log(TAG + " album art decode failed: " + t);
                } finally {
                    if (imageId.equals(inFlightArtImageId) && background == inFlightArtTarget) {
                        inFlightArtImageId = "";
                        inFlightArtTarget = null;
                        if (inFlightArtCall == call) inFlightArtCall = null;
                    }
                }
            }
        });
    }

    private boolean isStaleArtRequest(String imageId, AmbientBackgroundLayer background,
                                      RunningState runningState) {
        return (runningState != null && !runningState.isRunning())
                || background != animatedBackground
                || !imageId.equals(desiredArtImageId);
    }

    static int calculateInSampleSize(int width, int height, int targetLongestEdge) {
        if (width <= 0 || height <= 0 || targetLongestEdge <= 0) return 1;
        int longest = Math.max(width, height);
        int target = targetLongestEdge;
        int sample = 1;
        while (longest / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    private void applyAnimatedPalette(int[] colors) {
        if (animatedBackground instanceof KawarpBackgroundView) {
            ((KawarpBackgroundView) animatedBackground).setPaletteColors(colors);
        }
    }

    public interface RunningState {
        boolean isRunning();
    }
}
