package com.eza.spicyex.lyrics;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.eza.spicyex.FeatureAvailability;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.beautifullyrics.entities.AmbientBackgroundLayer;
import com.eza.spicyex.beautifullyrics.entities.AmbientArtworkBackgroundView;

import java.io.IOException;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Future;
import com.eza.spicyex.beautifullyrics.entities.AmbientArtworkTexture;
import com.eza.spicyex.beautifullyrics.entities.AmbientArtworkProfile;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import com.eza.spicyex.xposed.XpLog;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Owns the native lyrics ambient gradient and optional animated album-art background. */
public final class LyricsAmbientController {
    private static final String TAG = "[SpotifyPlusAmbientController]";
    private static final int ART_DECODE_TARGET_PX = 384;
    private static final int MAX_ART_BYTES = 4 * 1024 * 1024;

    private final Activity activity;
    private final OkHttpClient http;
    private final SpotifyPlusConfig config;
    private static final long PAGE_BACKGROUND_TRANSITION_MS = 500L;

    private final MeshGradientDrawable pageBackground;
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();
    private AmbientBackgroundLayer animatedBackground;
    private FrameLayout animatedParent;
    private boolean animatedForceDark;
    private android.graphics.ColorFilter extraDarkFilter;
    private float backgroundBrightness = 1f;
    private volatile String desiredArtImageId = "";
    private volatile String appliedArtImageId = "";
    private volatile String inFlightArtImageId = "";
    private volatile Call inFlightArtCall;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ScheduledThreadPoolExecutor ART_WORKER = new ScheduledThreadPoolExecutor(1);
    static { ART_WORKER.setRemoveOnCancelPolicy(true); }
    private Future<?> artWork;
    private long artGeneration;
    private boolean active;
    private boolean animationPaused;
    private boolean textureEnabled;
    private String currentTrackUri = "";
    private boolean playing = true;
    private int[] currentPageColors;
    private ValueAnimator pageColorAnimator;

    public LyricsAmbientController(Activity activity, OkHttpClient http, SpotifyPlusConfig config) {
        this.activity = activity;
        this.http = http;
        this.config = config;
        int[] seed = {Color.rgb(30, 21, 18), Color.rgb(62, 19, 28), Color.rgb(16, 15, 16)};
        this.pageBackground = new MeshGradientDrawable(seed);
        this.currentPageColors = seed.clone();
    }

    public MeshGradientDrawable pageBackground() {
        return pageBackground;
    }

    /** Pause/resume the animated background (e.g. while a settings modal is open). */
    public void pauseAnimation() {
        animationPaused = true;
        if (animatedBackground != null) animatedBackground.pauseRendering();
    }

    public void resumeAnimation() {
        animationPaused = false;
        resumeRenderer();
    }

    private void resumeRenderer() {
        if (animatedBackground != null && active && textureEnabled && !animationPaused) {
            animatedBackground.resumeRendering();
        }
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        if (animatedBackgroundSupported()) {
            ((AmbientArtworkBackgroundView) animatedBackground).setPlaying(playing);
        }
    }

    public void start() {
        active = true;
        applySettings(LyricsBackgroundStyle.read(config), config == null || config.get(Settings.FORCE_DARK_BACKGROUND),
                config != null ? config.get(Settings.EXTRA_DARK_BACKGROUND) : 0);
    }

    public void stop() {
        active = false;
        animationPaused = false;
        cancelArtwork();
        if (pageColorAnimator != null) pageColorAnimator.cancel();
        if (animatedBackground != null) animatedBackground.release();
        appliedArtImageId = "";
    }

    private void cancelArtwork() {
        artGeneration++;
        if (inFlightArtCall != null) inFlightArtCall.cancel();
        if (artWork != null) artWork.cancel(true);
        artWork = null;
        inFlightArtCall = null;
        inFlightArtImageId = "";
    }

    /** Apply the "Animated background" setting live: show+resume or hide+pause the layer. */
    public void applyEnabled(boolean enabled) {
        applySettings(enabled ? LyricsBackgroundStyle.ANIMATED_TEXTURE
                        : LyricsBackgroundStyle.GRADIENT,
                config == null || config.get(Settings.FORCE_DARK_BACKGROUND),
                config != null ? config.get(Settings.EXTRA_DARK_BACKGROUND) : 0);
    }

    public void applySettings(String style, boolean forceDark, int extraDark) {
        String normalized = LyricsBackgroundStyle.normalize(style);
        boolean enabled = LyricsBackgroundStyle.usesTexture(normalized);
        boolean animated = LyricsBackgroundStyle.isAnimated(normalized);
        textureEnabled = enabled;
        applyExtraDark(enabled && forceDark, extraDark);
        if (animatedParent != null && enabled && animatedBackground == null) {
            createAnimatedLayer(animatedParent, forceDark, animated);
        } else if (forceDark != animatedForceDark) {
            if (animatedBackground instanceof AmbientArtworkBackgroundView) {
                ((AmbientArtworkBackgroundView) animatedBackground).setForceDark(forceDark);
            }
            animatedForceDark = forceDark;
        }
        if (animatedBackgroundSupported()) {
            ((AmbientArtworkBackgroundView) animatedBackground).setDarkening(backgroundBrightness, extraDarkFilter);
            ((AmbientArtworkBackgroundView) animatedBackground).setRenderScale(readRenderScale());
        }
        if (animatedBackground == null) return; // not attached this session — applies on next open
        if (animatedBackgroundSupported()) {
            ((AmbientArtworkBackgroundView) animatedBackground).setMotionEnabled(animated);
        }
        if (enabled && active) {
            animatedBackground.asView().setVisibility(android.view.View.VISIBLE);
            resumeRenderer();
            updateAnimatedBackgroundArt(desiredArtImageId, null);
        } else {
            cancelArtwork();
            animatedBackground.release();
            appliedArtImageId = "";
            animatedBackground.asView().setVisibility(android.view.View.GONE);
        }
    }

    public void attachAnimatedLayer(FrameLayout parent, String style, boolean forceDark, int extraDark) {
        animatedParent = parent;
        textureEnabled = LyricsBackgroundStyle.usesTexture(style);
        applyExtraDark(textureEnabled && forceDark, extraDark);
        if (!FeatureAvailability.animatedBackgroundAvailable()) return;
        if (parent == null || !LyricsBackgroundStyle.usesTexture(style)) return;
        createAnimatedLayer(parent, forceDark, LyricsBackgroundStyle.isAnimated(style));
    }

    private float readRenderScale() {
        try {
            return (config == null ? Settings.BACKGROUND_RENDER_QUALITY.defaultValue
                    : config.get(Settings.BACKGROUND_RENDER_QUALITY)) / 100f;
        } catch (Throwable ignored) {
            return Settings.BACKGROUND_RENDER_QUALITY.defaultValue / 100f;
        }
    }

    /** Scale only the completed background, including the artwork-loading fallback. */
    private void applyExtraDark(boolean enabled, int level) {
        float factor = enabled ? Math.max(0f, Math.min(1f, 1f - level / 100f)) : 1f;
        if (factor == backgroundBrightness) return;
        backgroundBrightness = factor;
        if (factor != 1f) {
            android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix();
            matrix.setScale(factor, factor, factor, 1f);
            extraDarkFilter = new android.graphics.ColorMatrixColorFilter(matrix);
        } else {
            extraDarkFilter = null;
        }
        pageBackground.setColorFilter(extraDarkFilter);
    }

    private void createAnimatedLayer(FrameLayout parent, boolean forceDark, boolean animated) {
        // Guarded here as well as at the call sites: a pref persisted on a newer device (backup
        // restore, shared prefs copy) must not resurrect the layer on hardware that cannot run it.
        if (parent == null || !FeatureAvailability.animatedBackgroundAvailable()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            AmbientArtworkBackgroundView background = new AmbientArtworkBackgroundView(activity, forceDark);
            background.setDarkening(backgroundBrightness, extraDarkFilter);
            background.setRenderScale(readRenderScale());
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
        cancelArtwork();
        parent.addView(animatedBackground.asView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyAnimatedPalette(currentPageColors);
    }

    public void updateForTrack(SpotifyTrack track, RunningState runningState) {
        int seed = LyricVisuals.parseSpotifyExtractedColor(track == null ? "" : track.color);
        boolean forceDark = config == null || config.get(Settings.FORCE_DARK_BACKGROUND);
        int[] colors = LyricVisuals.spicyColorBackgroundColors(seed, forceDark);
        animatePageBackgroundColors(colors);
        applyAnimatedPalette(colors);
        updateAnimatedBackgroundArt(track, runningState);
        if (!textureEnabled && track != null) loadMeshArtworkColors(safe(track.imageId), safe(track.uri), 0);
    }

    /**
     * The colours background takes its fields from the cover itself once Spotify has it decoded
     * (retrying briefly, since the cover usually arrives a moment after the track change).
     */
    private void loadMeshArtworkColors(String imageId, String trackUri, int attempt) {
        if (imageId.isEmpty() || !trackUri.equals(currentTrackUri)) return;
        Bitmap art = SpotifyArtworkCache.snapshot(imageId, trackUri);
        if (art == null) {
            if (attempt < 8) main.postDelayed(() -> loadMeshArtworkColors(imageId, trackUri, attempt + 1), 400L);
            return;
        }
        ART_WORKER.execute(() -> {
            int[] fields = MeshGradientDrawable.extractPalette(art, 4);
            art.recycle();
            if (fields == null) return;
            // A deep shade of the lead colour, not near-black: the fields blend into it.
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(fields[0], hsv);
            hsv[1] = Math.min(0.85f, hsv[1] * 0.9f);
            hsv[2] = 0.24f;
            int base = android.graphics.Color.HSVToColor(hsv);
            main.post(() -> {
                if (!trackUri.equals(currentTrackUri) || textureEnabled) return;
                pageBackground.setArtworkColors(fields, base);
            });
        });
    }

    // The mesh crossfades from the previous track's colours by itself.
    private void animatePageBackgroundColors(int[] targetColors) {
        if (targetColors == null || targetColors.length == 0) return;
        currentPageColors = targetColors.clone();
        pageBackground.setColors(currentPageColors);
    }

    private void updateAnimatedBackgroundArt(SpotifyTrack track, RunningState runningState) {
        currentTrackUri = track == null ? "" : safe(track.uri);
        desiredArtImageId = track == null ? "" : safe(track.imageId);
        updateAnimatedBackgroundArt(desiredArtImageId, runningState);
    }

    /** The lyrics background as a still image, or null when it is not the animated texture. */
    public android.graphics.Bitmap snapshotBackground(int width, int height) {
        if (!animatedBackgroundSupported()) return null;
        return ((AmbientArtworkBackgroundView) animatedBackground).snapshot(width, height);
    }

    private boolean animatedBackgroundSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && animatedBackground instanceof AmbientArtworkBackgroundView;
    }

    private void updateAnimatedBackgroundArt(String imageId, RunningState runningState) {
        AmbientBackgroundLayer background = animatedBackground;
        if (background == null || !active || !textureEnabled) return;
        if (isBlank(imageId)) {
            cancelArtwork();
            background.release();
            appliedArtImageId = "";
            return;
        }
        if (imageId.equals(appliedArtImageId) || imageId.equals(inFlightArtImageId)) return;
        cancelArtwork();
        final long generation = artGeneration;
        inFlightArtImageId = imageId;
        // Borrow only artwork with matching metadata identity; own the small copy before dispatch.
        Bitmap borrowed = SpotifyArtworkCache.snapshot(imageId, currentTrackUri);
        final Bitmap local = borrowed;
        // Ad creatives / remote playback may already carry a full https URL as imageId —
        // prefixing the CDN host again would produce an invalid URL and break ad artwork.
        String artUrl = imageId.startsWith("http") ? imageId
                : "https://i.scdn.co/image/" + Uri.encode(imageId);
        artWork = ART_WORKER.submit(() -> {
            Bitmap prepared = null;
            AmbientArtworkProfile profile = null;
            // Built here, not on the main thread: the first request of the process initializes
            // OkHttp/okio, a few hundred ms that used to land in the lyrics screen's first frame.
            Call call = local != null ? null : http.newCall(new Request.Builder().url(artUrl).build());
            if (call != null) inFlightArtCall = call;
            try {
                if (local != null) {
                    AmbientArtworkTexture.Prepared done = AmbientArtworkTexture.prepareWithProfile(local);
                    prepared = done.bitmap;
                    profile = done.profile;
                } else {
                    try (Response response = call.execute()) {
                        if (!response.isSuccessful() || response.body() == null
                                || response.body().contentLength() > MAX_ART_BYTES) return;
                        byte[] data = readBounded(response.body().byteStream(), MAX_ART_BYTES);
                        if (data == null || Thread.currentThread().isInterrupted()) return;
                        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeByteArray(data,0,data.length,bounds);
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return;
                        android.graphics.BitmapFactory.Options decode = new android.graphics.BitmapFactory.Options();
                        decode.inSampleSize = calculateInSampleSize(bounds.outWidth,bounds.outHeight,ART_DECODE_TARGET_PX);
                        Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(data,0,data.length,decode);
                        if (decoded != null) {
                            try {
                                AmbientArtworkTexture.Prepared done =
                                        AmbientArtworkTexture.prepareWithProfile(decoded);
                                prepared = done.bitmap;
                                profile = done.profile;
                            }
                            finally { decoded.recycle(); }
                        }
                    }
                }
                final Bitmap result = prepared;
                final AmbientArtworkProfile resultProfile = profile;
                prepared = null;
                main.post(() -> {
                    if (generation != artGeneration || !active || !textureEnabled
                            || background != animatedBackground || !imageId.equals(desiredArtImageId)) {
                        if (result != null) result.recycle();
                        return;
                    }
                    if (result != null) {
                        background.updateImage(result, resultProfile);
                        resumeRenderer();
                        appliedArtImageId = imageId;
                        XpLog.log(TAG + " artwork source=" + (local == null ? "cdn" : "spotify_metadata"));
                    }
                });
            } catch (Exception e) {
                if (!call.isCanceled()) XpLog.log(TAG + " artwork unavailable: " + e.getClass().getSimpleName());
            } finally {
                if (local != null) local.recycle();
                if (prepared != null) prepared.recycle();
                main.post(() -> {
                    if (generation == artGeneration) {
                        inFlightArtImageId = "";
                        inFlightArtCall = null;
                        artWork = null;
                    }
                });
            }
        });
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count > maxBytes - total) return null;
                out.write(buffer, 0, count);
                total += count;
            }
            return out.toByteArray();
        }
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
        if (animatedBackgroundSupported()) {
            ((AmbientArtworkBackgroundView) animatedBackground).setPaletteColors(colors);
        }
    }

    public interface RunningState {
        boolean isRunning();
    }
}
