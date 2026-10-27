package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.eza.spicyex.FeatureAvailability;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.ambient.AmbientBackgroundLayer;
import com.eza.spicyex.ambient.AmbientArtworkBackgroundView;
import com.eza.spicyex.ambient.AmbientShaderEffect;
import com.eza.spicyex.ambient.BackgroundEffectSelection;
import com.eza.spicyex.settings.values.BackgroundEffect;
import com.eza.spicyex.settings.values.BackgroundEffectPool;
import com.eza.spicyex.settings.values.StoredValues;
import com.eza.spicyex.ambient.MeshGradientMotionProfile;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Future;
import com.eza.spicyex.ambient.AmbientArtworkTexture;
import com.eza.spicyex.ambient.AmbientArtworkProfile;
import com.eza.spicyex.ambient.AmbientArtworkPalette;
import com.eza.spicyex.settings.values.BackgroundStyle;

import com.eza.spicyex.xposed.XpLog;
import okhttp3.OkHttpClient;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import com.eza.spicyex.lyrics.cache.ArtworkNetworkCache;
import com.eza.spicyex.lyrics.cache.SpotifyArtworkCache;

/** Owns the native lyrics ambient gradient and optional animated album-art background. */
public final class LyricsAmbientController {
    private static final String TAG = "[SpotifyPlusAmbientController]";
    private static final int ART_DECODE_TARGET_PX = 384;
    /** The fixed render scale of the animated background; the thermal throttle still applies. */
    private static final float BACKGROUND_RENDER_SCALE = 0.35f;
    /** Cover fields handed to the palette renderers; the page mesh draws four fields plus shifts. */
    private static final int PALETTE_COLOR_COUNT = AmbientArtworkPalette.FIELD_COUNT;
    /** Small decode keeps the palette-only CDN fallback cheap; extraction samples 32x32 anyway. */

    private final Activity activity;
    private final OkHttpClient http;
    private final SpotifyPlusConfig config;

    private final MeshGradientDrawable pageBackground;
    private AmbientBackgroundLayer animatedBackground;
    private FrameLayout animatedParent;
    private AmbientShaderEffect effect = AmbientShaderEffect.DEFAULT;
    // Fullscreen and PiP can recreate their controllers without changing the playing song.
    // Their shared, main-thread selection preserves Random across those view lifecycles.
    private static final BackgroundEffectSelection EFFECT_SELECTION = new BackgroundEffectSelection();
    private boolean animatedForceDark;
    private android.graphics.ColorFilter extraDarkFilter;
    private float backgroundBrightness = 1f;
    private volatile String desiredArtImageId = "";
    private volatile String appliedArtImageId = "";
    private volatile String inFlightArtImageId = "";
    /** Which owned representation the accepted and in-flight artwork use. */
    private boolean appliedArtOriginal;
    private boolean inFlightArtOriginal;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final ScheduledThreadPoolExecutor ART_WORKER = new ScheduledThreadPoolExecutor(1);
    static { ART_WORKER.setRemoveOnCancelPolicy(true); }
    private Future<?> artWork;
    private long artGeneration;
    /**
     * Accepted artwork palette for the current cover. Kept across same-song effect/style switches
     * so a palette-only renderer never regresses to the seed colour while its cover is unchanged.
     */
    private int[] artworkPalette;
    private String artworkPaletteImageId = "";
    private String paletteLoadingImageId = "";
    private Future<?> paletteWork;
    private long paletteTaskGeneration;
    private boolean active;
    private boolean animationPaused;
    private boolean textureEnabled;
    private String currentTrackUri = "";
    private boolean playing = true;
    private int[] currentPageColors;
    private MeshGradientMotionProfile currentMotionProfile;
    private long trackGeneration;
    private LyricsDocument timingDocument;

    public LyricsAmbientController(Activity activity, OkHttpClient http, SpotifyPlusConfig config) {
        this.activity = activity;
        this.http = http;
        this.config = config;
        int[] seed = {Color.rgb(30, 21, 18), Color.rgb(62, 19, 28), Color.rgb(16, 15, 16)};
        this.pageBackground = new MeshGradientDrawable(seed);
        this.currentPageColors = seed.clone();
        this.currentMotionProfile = MeshGradientMotionProfile.forTrack("", "", null);
    }

    public MeshGradientDrawable pageBackground() {
        return pageBackground;
    }

    /** Applies a resolved effect. The debug override lasts until the next preference or track update. */
    public void setEffect(AmbientShaderEffect next) {
        if (next == null || next == effect) return;
        if (animatedBackground != null && !animatedBackground.setEffect(next)) return;
        effect = next;
        // A palette-only effect does not fetch artwork; switching back to Kawarp may need it again.
        updateAnimatedBackgroundArt(desiredArtImageId, null);
        // A prior no-art Kawarp state calls release(), which disables the view. Palette-only
        // effects have no artwork prerequisite, so resume only through the controller's gates.
        if (!next.requiresArtworkTexture()) {
            resumeRenderer();
        }
        // The switched renderer must immediately show the current cover's palette rather than the
        // seed colour it was just constructed with.
        requestCurrentArtworkPalette();
    }

    /** Content-free shader state for the debug CLI; none when no animated layer is mounted. */
    public String effectStatus() {
        return animatedBackground instanceof AmbientArtworkBackgroundView
                ? ((AmbientArtworkBackgroundView) animatedBackground).effectStatus()
                : "effect=none rejected=none";
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
        if (animatedBackground instanceof AmbientArtworkBackgroundView) {
            ((AmbientArtworkBackgroundView) animatedBackground).setPlaying(playing);
        }
    }

    /** A shared cover download landed: retry whatever is still waiting for this song's cover. */
    private final Runnable networkArtworkListener = () -> main.post(() -> {
        if (!active || !ArtworkNetworkCache.has(desiredArtImageId)) return;
        updateAnimatedBackgroundArt(desiredArtImageId, null);
        requestCurrentArtworkPalette();
    });

    public void start() {
        active = true;
        ArtworkNetworkCache.addListener(networkArtworkListener);
        applySettings(LyricsRenderConfig.backgroundStyle(config), config == null
                ? Settings.LYRICS_DARKENING.defaultValue : config.get(Settings.LYRICS_DARKENING));
    }

    public void stop() {
        active = false;
        ArtworkNetworkCache.removeListener(networkArtworkListener);
        animationPaused = false;
        cancelArtwork();
        cancelArtworkPalette();
        if (animatedBackground != null) animatedBackground.release();
        appliedArtImageId = "";
        appliedArtOriginal = false;
    }

    private void cancelArtwork() {
        artGeneration++;
        if (artWork != null) artWork.cancel(true);
        artWork = null;
        inFlightArtImageId = "";
        inFlightArtOriginal = false;
    }

    /** Drops queued or in-flight palette work; the accepted palette stays available for reuse. */
    private void cancelArtworkPalette() {
        paletteTaskGeneration++;
        if (paletteWork != null) paletteWork.cancel(true);
        paletteWork = null;
        paletteLoadingImageId = "";
    }

    /** Apply the "Animated background" setting live: show+resume or hide+pause the layer. */
    public void applyEnabled(boolean enabled) {
        applySettings(enabled ? BackgroundStyle.ANIMATED_TEXTURE
                        : BackgroundStyle.DEFAULT,
                config == null ? Settings.LYRICS_DARKENING.defaultValue
                        : config.get(Settings.LYRICS_DARKENING));
    }

    private void resolveEffect(String trackUri) {
        BackgroundEffect mode = StoredValues.fromStored(BackgroundEffect.class,
                config == null ? null : config.get(Settings.BACKGROUND_EFFECT));
        BackgroundEffectPool pool = BackgroundEffectPool.parse(
                config == null ? null : config.get(Settings.BACKGROUND_RANDOM_EFFECTS));
        setEffect(rendererEffect(EFFECT_SELECTION.resolve(trackUri, mode, pool)));
    }

    private static AmbientShaderEffect rendererEffect(BackgroundEffect effect) {
        if (effect == BackgroundEffect.MESH_GRADIENT) return AmbientShaderEffect.MESH_GRADIENT;
        AmbientShaderEffect mapped = AmbientShaderEffect.fromArgument(effect.stableId());
        return mapped == null ? AmbientShaderEffect.KAWARP : mapped;
    }

    public void applySettings(BackgroundStyle style, int darkening) {
        resolveEffect(currentTrackUri);
        boolean enabled = style.usesTexture();
        boolean animated = style.isAnimated();
        boolean forceDark = darkening > 0;
        textureEnabled = enabled;
        // The page gradient is a real background too. Darkening used to be gated by texture mode,
        // so the editor control visibly did nothing in the default Gradient mode.
        applyExtraDark(darkening);
        if (animatedParent != null && enabled && animatedBackground == null) {
            createAnimatedLayer(animatedParent, forceDark, animated);
        } else if (forceDark != animatedForceDark) {
            if (animatedBackground instanceof AmbientArtworkBackgroundView) {
                ((AmbientArtworkBackgroundView) animatedBackground).setForceDark(forceDark);
            }
            animatedForceDark = forceDark;
        }
        if (animatedBackground instanceof AmbientArtworkBackgroundView) {
            ((AmbientArtworkBackgroundView) animatedBackground).setDarkening(backgroundBrightness, extraDarkFilter);
            ((AmbientArtworkBackgroundView) animatedBackground).setRenderScale(readRenderScale());
        }
        if (animatedBackground == null) { // not attached this session — applies on next open
            requestCurrentArtworkPalette();
            return;
        }
        if (animatedBackground instanceof AmbientArtworkBackgroundView) {
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
            appliedArtOriginal = false;
            animatedBackground.asView().setVisibility(android.view.View.GONE);
        }
        // A style change can make the page gradient the visible surface (or switch the effect),
        // so the accepted palette must be reapplied without waiting for the next track.
        requestCurrentArtworkPalette();
    }

    public void attachAnimatedLayer(FrameLayout parent, BackgroundStyle style, int darkening) {
        resolveEffect(currentTrackUri);
        animatedParent = parent;
        textureEnabled = style.usesTexture();
        applyExtraDark(darkening);
        if (!FeatureAvailability.animatedBackgroundAvailable()) return;
        if (parent == null || !style.usesTexture()) return;
        createAnimatedLayer(parent, darkening > 0, style.isAnimated());
    }

    /** Extra scale on the background's render resolution: a picture-in-picture window shows the
     *  screen scaled far down, so it needs a fraction of the pixels. */
    private float renderScaleFactor = 1f;

    public void setRenderScaleFactor(float factor) {
        renderScaleFactor = factor;
    }

    private float readRenderScale() {
        // The render quality is fixed: the animated shader renders into a downsampled offscreen
        // surface and upscales it, because its cost is per output pixel and a full-resolution
        // shader running for a whole lyrics session is a sustained heat source on weaker GPUs.
        return BACKGROUND_RENDER_SCALE * renderScaleFactor;
    }

    /** Scale only the completed background, including the artwork-loading fallback. */
    private void applyExtraDark(int darkening) {
        float factor = backgroundBrightness(darkening);
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

    static float backgroundBrightness(int darkening) {
        return darkening > 0 ? Math.max(0f, Math.min(1f, 1f - darkening / 100f)) : 1f;
    }

    private void createAnimatedLayer(FrameLayout parent, boolean forceDark, boolean animated) {
        // Guarded here as well as at the call sites: a pref persisted on a newer device (backup
        // restore, shared prefs copy) must not resurrect the layer on hardware that cannot run it.
        if (parent == null || !FeatureAvailability.animatedBackgroundAvailable()) return;
        try {
            AmbientArtworkBackgroundView background = new AmbientArtworkBackgroundView(activity, forceDark);
            background.setDarkening(backgroundBrightness, extraDarkFilter);
            background.setRenderScale(readRenderScale());
            background.setPlaying(playing);
            background.setMotionEnabled(animated);
            background.setEffect(effect);
            animatedBackground = background;
        } catch (Throwable t) {
            XpLog.log(TAG + " ambient background unavailable: " + t);
            animatedBackground = null;
            return;
        }
        animatedForceDark = forceDark;
        appliedArtImageId = "";
        appliedArtOriginal = false;
        cancelArtwork();
        // The shell already contains the lyric and chrome layers when this is first selected.
        // Insert the texture at the back so a live preference change cannot place it above the
        // mounted lyrics until the screen is reopened.
        parent.addView(animatedBackground.asView(), 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyAnimatedPalette(currentPageColors, currentMotionProfile);
    }

    public void updateForTrack(SpotifyTrack track, RunningState runningState) {
        String nextTrackUri = track == null ? "" : safe(track.uri);
        String nextImageId = track == null ? "" : safe(track.imageId);
        boolean sameTrack = nextTrackUri.equals(currentTrackUri);
        // Resolve against the new identity before requesting its artwork and palette.
        resolveEffect(nextTrackUri);
        trackGeneration++;
        // Own the identity before any asynchronous texture or palette work is dispatched.
        currentTrackUri = nextTrackUri;
        desiredArtImageId = nextImageId;
        currentMotionProfile = MeshGradientMotionProfile.forTrack(
                track == null ? "" : track.uri, track == null ? "" : track.imageId, null)
                .smoothFrom(currentMotionProfile);
        if (sameTrack && timingDocument != null) {
            currentMotionProfile = MeshGradientMotionProfile.forTrack(
                    nextTrackUri, track == null ? "" : track.imageId, timingDocument)
                    .smoothFrom(currentMotionProfile);
        } else if (!sameTrack) {
            timingDocument = null;
        }
        int seed = LyricVisuals.parseSpotifyExtractedColor(track == null ? "" : track.color);
        int darkening = config == null ? Settings.LYRICS_DARKENING.defaultValue
                : config.get(Settings.LYRICS_DARKENING);
        boolean forceDark = darkening > 0;
        // A style or same-song settings refresh re-enters here; keep accepted artwork colours
        // instead of briefly regressing to the seed colour before the palette is requested again.
        boolean paletteKnown = effect.usesArtworkPalette(textureEnabled)
                && artworkPalette != null && nextImageId.equals(artworkPaletteImageId);
        if (paletteKnown) {
            applyArtworkPalette();
        } else {
            int[] colors = LyricVisuals.spicyColorBackgroundColors(seed, forceDark);
            animatePageBackgroundColors(colors);
            applyAnimatedPalette(colors, currentMotionProfile);
        }
        updateAnimatedBackgroundArt(track, runningState);
        if (!nextImageId.isEmpty() && !paletteKnown) {
            requestCurrentArtworkPalette();
        }
    }

    /** Applies lyric timing only after the current document has passed the session's track gate. */
    public void updateForDocument(String trackUri, LyricsDocument document) {
        if (document == null || !safe(trackUri).equals(currentTrackUri)) return;
        currentMotionProfile = MeshGradientMotionProfile.forTrack(currentTrackUri,
                desiredArtImageId, document).smoothFrom(currentMotionProfile);
        timingDocument = document;
        applyAnimatedPalette(currentPageColors, currentMotionProfile);
    }

    // The mesh crossfades from the previous track's colours by itself.
    private void animatePageBackgroundColors(int[] targetColors) {
        if (targetColors == null || targetColors.length == 0) return;
        currentPageColors = targetColors.clone();
        pageBackground.setColors(currentPageColors);
    }

    /**
     * Palette-only renderers need the cover even when Spotify's metadata bitmap is absent or
     * arrives late. Try the shared metadata cache first, then the same bounded CDN fetch the
     * texture path uses: a cache miss must not freeze the seed colour for the whole track.
     */
    private void loadArtworkPalette(String imageId, String trackUri, long generation) {
        if (!active || imageId.isEmpty() || generation != trackGeneration
                || !trackUri.equals(currentTrackUri)
                || !imageId.equals(desiredArtImageId)) return;
        if (imageId.equals(artworkPaletteImageId) && artworkPalette != null) {
            applyArtworkPalette();
            return;
        }
        if (imageId.equals(paletteLoadingImageId)) return;
        cancelArtworkPalette();
        final long task = paletteTaskGeneration;
        // Snapshot on the caller thread: the cache holds one weak bitmap, so the owned copy must
        // exist before the worker is queued. Without it, the shared download's listener retries.
        Bitmap owned = SpotifyArtworkCache.snapshot(imageId, trackUri);
        if (owned == null) owned = ArtworkNetworkCache.copyOrFetch(imageId, http);
        if (owned == null) return;
        final Bitmap art = owned;
        paletteLoadingImageId = imageId;
        paletteWork = ART_WORKER.submit(() -> {
            try {
                if (Thread.currentThread().isInterrupted()) return;
                final int[] fields = MeshGradientDrawable.extractPalette(art, PALETTE_COLOR_COUNT);
                if (fields == null || fields.length == 0) return;
                main.post(() -> acceptArtworkPalette(fields, imageId, trackUri, task));
            } catch (Exception e) {
                XpLog.log(TAG + " artwork palette unavailable: " + e.getClass().getSimpleName());
            } finally {
                art.recycle();
                main.post(() -> {
                    if (task == paletteTaskGeneration) {
                        paletteWork = null;
                        paletteLoadingImageId = "";
                    }
                });
            }
        });
    }

    /**
     * Reapplies the accepted palette for a same-song effect or style switch, or starts acquiring
     * it. An image-backed effect waits for its texture worker, which extracts from the same decode.
     */
    private void requestCurrentArtworkPalette() {
        if (!active || isBlank(desiredArtImageId)
                || !effect.usesArtworkPalette(textureEnabled)) return;
        if (artworkPalette != null && desiredArtImageId.equals(artworkPaletteImageId)) {
            applyArtworkPalette();
            return;
        }
        if (waitsForArtworkTexture()) return;
        loadArtworkPalette(desiredArtImageId, currentTrackUri, trackGeneration);
    }

    /**
     * True while the texture worker will decode this cover and hand its palette over. An already
     * applied texture does not re-decode, so the palette loader covers that case.
     */
    private boolean waitsForArtworkTexture() {
        AmbientBackgroundLayer background = animatedBackground;
        return active && textureEnabled && background != null && background.usesArtworkTexture()
                && !desiredArtImageId.equals(appliedArtImageId);
    }

    private void acceptArtworkPalette(int[] fields, String imageId, String trackUri, long task) {
        // The task token is the async generation: it changes on stop and on a superseding request.
        // The view's track generation may advance for a same-song settings refresh, so identity,
        // not that counter, decides whether this result still belongs on screen.
        if (task != paletteTaskGeneration) return;
        if (!active
                || !trackUri.equals(currentTrackUri)
                || !imageId.equals(desiredArtImageId)
                || !effect.usesArtworkPalette(textureEnabled)) return;
        artworkPalette = fields.clone();
        artworkPaletteImageId = imageId;
        paletteLoadingImageId = "";
        applyArtworkPalette();
    }

    /** The texture decode and the palette loader share one cover; take whichever finishes first. */
    private void acceptTextureArtworkPalette(int[] fields, String imageId, String trackUri) {
        if (fields == null || fields.length == 0) return;
        if (!active || !textureEnabled || !trackUri.equals(currentTrackUri)
                || !imageId.equals(desiredArtImageId)
                || !effect.usesArtworkPalette(textureEnabled)) return;
        artworkPalette = fields.clone();
        artworkPaletteImageId = imageId;
        applyArtworkPalette();
    }

    private void applyArtworkPalette() {
        int[] fields = artworkPalette;
        if (fields == null || fields.length == 0) return;
        pageBackground.setArtworkColors(fields, AmbientArtworkPalette.baseColor(fields[0]));
        currentPageColors = fields.clone();
        applyAnimatedPalette(fields, currentMotionProfile);
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
        // A palette-only effect has no texture input, so do not fetch or decode artwork for it.
        if (!background.usesArtworkTexture()) {
            cancelArtwork();
            appliedArtImageId = "";
            appliedArtOriginal = false;
            return;
        }
        if (isBlank(imageId)) {
            cancelArtwork();
            background.release();
            appliedArtImageId = "";
            appliedArtOriginal = false;
            return;
        }
        final boolean original = background.usesOriginalArtwork();
        // Kawarp's atlas and Paper's original cover are different owned bitmaps. An effect change
        // that crosses that boundary must reload even when the image id is unchanged.
        if (original != appliedArtOriginal) appliedArtImageId = "";
        if (original != inFlightArtOriginal) cancelArtwork();
        if (imageId.equals(appliedArtImageId)) return;
        if (imageId.equals(inFlightArtImageId)) return;
        // The texture worker owns this cover's palette for image-backed effects; the shared
        // single-thread worker must not sit behind a second palette request for the same bytes.
        cancelArtworkPalette();
        cancelArtwork();
        // Own a copy before dispatch: Spotify's published cover first, then the shared download.
        // A miss starts that download; its listener calls back here when the cover lands.
        Bitmap metadataArt = SpotifyArtworkCache.snapshot(imageId, currentTrackUri);
        final boolean fromMetadata = metadataArt != null;
        final Bitmap local = fromMetadata ? metadataArt : ArtworkNetworkCache.copyOrFetch(imageId, http);
        if (local == null) return;
        final long generation = artGeneration;
        inFlightArtImageId = imageId;
        inFlightArtOriginal = original;
        final String artTrackUri = currentTrackUri;
        // Kawarp ignores the palette; only extract when the active effect consumes it.
        final boolean paletteNeeded = effect.usesArtworkPalette(textureEnabled);
        artWork = ART_WORKER.submit(() -> {
            Bitmap prepared = null;
            AmbientArtworkProfile profile = null;
            int[] paletteFields = null;
            try {
                // One decode feeds both the texture and the palette for image-backed effects.
                if (paletteNeeded) {
                    paletteFields = MeshGradientDrawable.extractPalette(local, PALETTE_COLOR_COUNT);
                }
                if (original) {
                    prepared = AmbientArtworkTexture.prepareOriginal(local, ART_DECODE_TARGET_PX);
                } else {
                    AmbientArtworkTexture.Prepared done = AmbientArtworkTexture.prepareWithProfile(local);
                    prepared = done.bitmap;
                    profile = done.profile;
                }
                final Bitmap result = prepared;
                final AmbientArtworkProfile resultProfile = profile;
                final int[] resultPalette = paletteFields;
                prepared = null;
                main.post(() -> {
                    if (generation != artGeneration || !active || !textureEnabled
                            || background != animatedBackground || !imageId.equals(desiredArtImageId)
                            || background.usesOriginalArtwork() != original) {
                        if (result != null) result.recycle();
                        return;
                    }
                    if (result != null) {
                        if (original) background.updateOriginalImage(result);
                        else background.updateImage(result, resultProfile);
                        resumeRenderer();
                        appliedArtImageId = imageId;
                        appliedArtOriginal = original;
                        // Image shaders keep their texture; the palette must follow the same cover.
                        acceptTextureArtworkPalette(resultPalette, imageId, artTrackUri);
                        XpLog.log(TAG + " artwork source=" + (fromMetadata ? "spotify_metadata" : "cdn"));
                    }
                });
            } catch (Exception e) {
                XpLog.log(TAG + " artwork unavailable: " + e.getClass().getSimpleName());
            } finally {
                local.recycle();
                if (prepared != null) prepared.recycle();
                main.post(() -> {
                    if (generation == artGeneration) {
                        inFlightArtImageId = "";
                        inFlightArtOriginal = false;
                        artWork = null;
                    }
                });
            }
        });
    }

    private void applyAnimatedPalette(int[] colors, MeshGradientMotionProfile profile) {
        if (animatedBackground instanceof AmbientArtworkBackgroundView) {
            AmbientArtworkBackgroundView view = (AmbientArtworkBackgroundView) animatedBackground;
            view.setMeshPaletteAndMotion(colors, profile.phase, profile.speed, trackGeneration);
        }
    }

    public interface RunningState {
        boolean isRunning();
    }
}
