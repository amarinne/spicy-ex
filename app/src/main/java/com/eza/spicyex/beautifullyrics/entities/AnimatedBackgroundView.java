package com.eza.spicyex.beautifullyrics.entities;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.*;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class AnimatedBackgroundView extends View implements AmbientBackgroundLayer {
    @Override
    public View asView() {
        return this;
    }

    private static final int BLUR_RADIUS = 20;
    private static final int BLUR_PASSES = 1;

    // Per-instance quality config (these were mutable statics, so one instance's settings
    // stomped every other instance's).
    private float downsampleFactor = 0.12f;
    private int blobCount = 16;
    private static final long TRANSITION_DURATION_MS = 1000L;
    private static final int BUFFER_COUNT = 3;

    private static final int PALETTE_COLORFUL = 0;
    private static final int PALETTE_DARK_MUTED = 1;
    private static final int PALETTE_BRIGHT_NEUTRAL = 2;
    private static final float FORCE_DARK_SATURATION_MULTIPLIER = 0.85f;
    private static final int FORCE_DARK_TINT_COLOR = Color.rgb(6, 6, 8);
    private static final int FORCE_DARK_TINT_ALPHA = Math.round(255f * 0.28f);

    private int paletteMode = PALETTE_COLORFUL;
    private final boolean forceDarkBackground;

    private final HandlerThread renderThread;
    private Handler renderHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isRendering = new AtomicBoolean(false);
    private final Object lock = new Object();

    private final Random random = new Random();

    private final Bitmap[] buffers = new Bitmap[BUFFER_COUNT];
    private final Canvas[] canvases = new Canvas[BUFFER_COUNT];
    private int renderHeadIndex = 0;
    private Bitmap renderedBitmap;

    private int offW = 1, offH = 1;

    private Bitmap sourceImage;
    private TrackAnalysis currentAnalysis = TrackAnalysis.defaultTrack;
    private int baseColor = 0xFF101010;

    private List<Blob> blobs = new ArrayList<>();
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private long startTimeMs;
    private boolean isTransitioning = false;
    private Bitmap previousBitmap;
    private long transitionStartMs;
    private long lastFrameTimeNanos = 0;

    private float animationSpeedMultiplier = 1.0f;
    private float breathingFrequency = 1.0f;

    private int[] blurBufA, blurBufB;
    private Choreographer.FrameCallback frameCallback = null;
    // Cap the actual blur work to ~22fps (the ambient motion is slow; 60fps was wasted effort).
    // We still get a vsync callback each frame but only re-render when the interval has elapsed.
    private static final long RENDER_INTERVAL_NS = 1_000_000_000L / 22;
    private long lastRenderPostNs = 0;

    public AnimatedBackgroundView(Context ctx, Bitmap bitmap, ViewGroup root) {
        super(ctx);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        if (bitmap != null) {
            this.sourceImage = Bitmap.createScaledBitmap(bitmap, 100, 100, true);
        } else {
            this.sourceImage = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        SpotifyPlusConfig config = SpotifyPlusConfig.from(ctx);
        String quality = config.get(Settings.BACKGROUND_QUALITY);
        forceDarkBackground = config.get(Settings.FORCE_DARK_BACKGROUND);

        // Case-insensitive: the setting enum stores "superLow"; the old case-sensitive switch
        // ("superlow") never matched, silently keeping high quality.
        if ("mid".equalsIgnoreCase(quality)) {
            downsampleFactor = 0.06f;
            blobCount = 10;
        } else if ("low".equalsIgnoreCase(quality)) {
            downsampleFactor = 0.04f;
            blobCount = 6;
        } else if ("superLow".equalsIgnoreCase(quality)) {
            downsampleFactor = 0.02f;
            blobCount = 4;
        } else { // "high" and any unknown value
            downsampleFactor = 0.12f;
            blobCount = 16;
        }

        renderThread = new HandlerThread("FluidBG");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        overlayBgPaint.setColor(0x88000000);
        overlayTextPaint.setColor(Color.WHITE);
        overlayTextPaint.setTextSize(dp(11));
        overlayTextPaint.setFakeBoldText(true);

        startTimeMs = SystemClock.elapsedRealtime();
        lastMetricsSecondMs = startTimeMs;

        blobPaint.setXfermode(null);
        blobPaint.setStyle(Paint.Style.FILL);

        startTimeMs = SystemClock.elapsedRealtime();

        frameCallback = frameTimeNanos -> {
            if (getWindowToken() == null) return;
            long dt = (lastFrameTimeNanos == 0)
                    ? 16_000_000
                    : (frameTimeNanos - lastFrameTimeNanos);
            lastFrameTimeNanos = frameTimeNanos;
            updateUiMetrics(dt);

            if (frameTimeNanos - lastRenderPostNs >= RENDER_INTERVAL_NS) {
                long renderDt = lastRenderPostNs == 0 ? 16_000_000 : (frameTimeNanos - lastRenderPostNs);
                lastRenderPostNs = frameTimeNanos;
                renderHandler.post(() -> renderFrame(renderDt));
            }
            Choreographer.getInstance().postFrameCallback(frameCallback);
        };

        renderHandler.post(this::internalRebuildResources);
    }

    public void updateImage(Bitmap newImage) {
        if (newImage == null || newImage.isRecycled()) return;
        final Bitmap smallCopy = Bitmap.createScaledBitmap(newImage, 100, 100, true);
        synchronized (lock) {
            if (renderedBitmap != null) {
                previousBitmap = renderedBitmap;
                transitionStartMs = SystemClock.elapsedRealtime();
                isTransitioning = true;
            }
            sourceImage = smallCopy;
        }
        renderHandler.post(this::internalRebuildResources);
    }

    public void updateTrackAnalysis(TrackAnalysis analysis) {
        synchronized (lock) {
            this.currentAnalysis = (analysis != null) ? analysis : TrackAnalysis.defaultTrack;
        }
        renderHandler.post(this::internalRebuildResources);
    }

    private boolean isAnalysisDefault() {
        return currentAnalysis == TrackAnalysis.defaultTrack ||
                (currentAnalysis.acousticness == 1 && currentAnalysis.danceability == 1 && currentAnalysis.tempo == 1);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        allocateBuffersIfNeeded(getWidth(), getHeight());
        renderHandler.post(this::internalRebuildResources);
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    /** Pause the render loop (e.g. while a modal is over the lyrics) — call on the main thread. */
    public void pauseRendering() {
        if (frameCallback != null) Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    /** Resume the render loop. Main thread. */
    public void resumeRendering() {
        if (frameCallback != null && getWindowToken() != null) {
            Choreographer.getInstance().removeFrameCallback(frameCallback);
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        renderHandler.removeCallbacksAndMessages(null);
        // Recycle ON the render thread, after any in-flight frame: recycling here raced a queued
        // renderFrame drawing into the same buffer. onDraw already tolerates null/recycled.
        renderHandler.post(() -> {
            synchronized (lock) {
                renderedBitmap = null;
                previousBitmap = null;
                for (int i = 0; i < BUFFER_COUNT; i++) {
                    if (buffers[i] != null) {
                        buffers[i].recycle();
                        buffers[i] = null;
                    }
                    canvases[i] = null;
                }
            }
        });
        renderThread.quitSafely();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        allocateBuffersIfNeeded(w, h);
        renderHandler.post(this::internalRebuildResources);
    }

    private void allocateBuffersIfNeeded(int vw, int vh) {
        if (vw <= 0 || vh <= 0) return;
        int targetW = Math.max(1, Math.round(vw * downsampleFactor));
        int targetH = Math.max(1, Math.round(vh * downsampleFactor));

        if (buffers[0] != null
                && buffers[0].getWidth() == targetW
                && buffers[0].getHeight() == targetH) {
            return;
        }

        synchronized (lock) {
            for (int i = 0; i < BUFFER_COUNT; i++) {
                if (buffers[i] != null) buffers[i].recycle();
                buffers[i] = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                canvases[i] = new Canvas(buffers[i]);
            }
            offW = targetW;
            offH = targetH;
            blurBufA = new int[offW * offH];
            blurBufB = new int[offW * offH];
        }
    }

    private static class PaletteInfo {
        float avgS;
        float avgV;
        int mode;
    }

    private PaletteInfo analyzePalette(Bitmap b) {
        PaletteInfo info = new PaletteInfo();
        int w = b.getWidth();
        int h = b.getHeight();
        float[] hsv = new float[3];
        double sumS = 0.0, sumV = 0.0;
        int count = 0;

        for (int x = 0; x < w; x += 4) {
            for (int y = 0; y < h; y += 4) {
                int c = b.getPixel(x, y);
                Color.colorToHSV(c, hsv);
                sumS += hsv[1];
                sumV += hsv[2];
                count++;
            }
        }

        if (count == 0) {
            info.avgS = 0f;
            info.avgV = 0f;
            info.mode = PALETTE_DARK_MUTED;
            return info;
        }

        info.avgS = (float) (sumS / count);
        info.avgV = (float) (sumV / count);

        if (info.avgV >= 0.75f && info.avgS <= 0.25f) {
            info.mode = PALETTE_BRIGHT_NEUTRAL;
        } else if (info.avgV < 0.30f || info.avgS < 0.18f) {
            info.mode = PALETTE_DARK_MUTED;
        } else {
            info.mode = PALETTE_COLORFUL;
        }
        return info;
    }

    private void internalRebuildResources() {
        if (sourceImage == null) return;

        // Note: We use the class member 'random' here, no seeding from image
        PaletteInfo pi = analyzePalette(sourceImage);
        paletteMode = pi.mode;
        if (forceDarkBackground && paletteMode == PALETTE_COLORFUL) paletteMode = PALETTE_DARK_MUTED;

        // Calculate Modifiers based on Analysis
        if (isAnalysisDefault()) {
            animationSpeedMultiplier = 1.0f;
            breathingFrequency = 1.0f;
        } else {
            animationSpeedMultiplier = 0.4f + (currentAnalysis.energy * 1.4f);
            float bpm = currentAnalysis.tempo;
            if (bpm < 40) bpm = 40;
            if (bpm > 200) bpm = 200;
            breathingFrequency = bpm / 110.0f;
        }

        // Build blob colors
        List<Integer> blobColors = extractDominantColors(sourceImage, blobCount, paletteMode);

        if (blobColors.isEmpty()) {
            int avg = calculateAverageColor(sourceImage);
            baseColor = buildBaseColor(avg);
            blobColors = new ArrayList<>();
            for (int i = 0; i < blobCount; i++) blobColors.add(avg);
        } else {
            int primaryColor = blobColors.get(0);
            baseColor = buildBaseColor(primaryColor);
        }

        List<Blob> newBlobs = new ArrayList<>();

        for (int i = 0; i < blobCount; i++) {
            // extractDominantColors may return fewer colors than blobCount (art with few dominant
            // colors); cycle through what we have instead of indexing past the end (crash).
            int rawColor = blobColors.get(i % blobColors.size());
            int processedColor = boostColorForVividness(rawColor);

            // Spawning logic: Center biased but random
            float originX = 0.5f + (random.nextFloat() - 0.5f) * 0.8f;
            float originY = 0.5f + (random.nextFloat() - 0.5f) * 0.8f;
            float radius = 0.35f + random.nextFloat() * 0.4f;

            // Base velocity - Start in random directions
            float vx = (random.nextFloat() - 0.5f) * 0.003f;
            float vy = (random.nextFloat() - 0.5f) * 0.003f;

            newBlobs.add(new Blob(originX, originY, radius, processedColor, vx, vy));
        }
        this.blobs = newBlobs;
    }

    // === COLOR EXTRACTION (Same logic, just uses shared Random for shuffle) ===

    private static class HueBucket {
        long sumR, sumG, sumB;
        float sumS, sumV;
        int count;
        float hueCenter;
        float score;
    }

    private List<Integer> extractDominantColors(Bitmap b, int needed, int paletteMode) {
        int w = b.getWidth();
        int h = b.getHeight();

        HueBucket[] buckets = new HueBucket[36];
        float[] hsv = new float[3];

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int c = b.getPixel(x, y);
                int a = Color.alpha(c);
                if (a < 64) continue;

                Color.colorToHSV(c, hsv);
                float hDeg = hsv[0];
                float s = hsv[1];
                float v = hsv[2];

                if (v < 0.03f) continue;

                if (paletteMode == PALETTE_COLORFUL) {
                    if (s < 0.18f || v < 0.18f) continue;
                } else if (paletteMode == PALETTE_DARK_MUTED) {
                    if (v < 0.05f && s < 0.05f) continue;
                } else {
                    if (v < 0.50f && s < 0.08f) continue;
                }

                int bin = (int) (hDeg / 10f);
                if (bin < 0) bin = 0;
                if (bin > 35) bin = 35;

                HueBucket bucket = buckets[bin];
                if (bucket == null) {
                    bucket = new HueBucket();
                    bucket.hueCenter = bin * 10f + 5f;
                    buckets[bin] = bucket;
                }

                bucket.sumR += Color.red(c);
                bucket.sumG += Color.green(c);
                bucket.sumB += Color.blue(c);
                bucket.sumS += s;
                bucket.sumV += v;
                bucket.count++;
            }
        }

        List<HueBucket> list = new ArrayList<>();
        for (HueBucket bkt : buckets) {
            if (bkt == null || bkt.count == 0) continue;
            float meanS = bkt.sumS / bkt.count;
            float meanV = bkt.sumV / bkt.count;
            float vividness = meanS * 0.7f + meanV * 0.3f;

            if (paletteMode == PALETTE_COLORFUL) {
                bkt.score = bkt.count * vividness;
            } else if (paletteMode == PALETTE_DARK_MUTED) {
                bkt.score = bkt.count * (0.7f + vividness * 0.3f);
            } else {
                bkt.score = bkt.count * (1.0f + vividness * 0.2f);
            }
            list.add(bkt);
        }

        if (list.isEmpty()) {
            List<Integer> fallback = new ArrayList<>();
            fallback.add(b.getPixel(b.getWidth() / 2, b.getHeight() / 2));
            return fallback;
        }

        Collections.sort(list, (o1, o2) -> Float.compare(o2.score, o1.score));

        final float MIN_MAIN_HUE_DIST = 22f;
        List<HueBucket> mainBuckets = new ArrayList<>();
        mainBuckets.add(list.get(0));

        for (int i = 1; i < list.size() && mainBuckets.size() < 3; i++) {
            HueBucket cand = list.get(i);
            boolean farEnough = true;
            for (HueBucket m : mainBuckets) {
                float dh = Math.abs(cand.hueCenter - m.hueCenter);
                if (dh > 180f) dh = 360f - dh;
                if (dh < MIN_MAIN_HUE_DIST) {
                    farEnough = false;
                    break;
                }
            }
            if (farEnough) mainBuckets.add(cand);
        }

        int mainCount = mainBuckets.size();

        if (mainCount == 1) {
            int color = bucketToColor(mainBuckets.get(0));
            List<Integer> only = new ArrayList<>(needed);
            for (int i = 0; i < needed; i++) only.add(color);
            return only;
        }

        float[] weights;
        if (mainCount == 2) {
            if (paletteMode == PALETTE_DARK_MUTED) {
                weights = new float[]{0.70f, 0.30f};
            } else if (paletteMode == PALETTE_BRIGHT_NEUTRAL) {
                weights = new float[]{0.75f, 0.25f};
            } else {
                weights = new float[]{0.65f, 0.35f};
            }
        } else {
            if (paletteMode == PALETTE_DARK_MUTED) {
                weights = new float[]{0.60f, 0.25f, 0.15f};
            } else if (paletteMode == PALETTE_BRIGHT_NEUTRAL) {
                weights = new float[]{0.70f, 0.18f, 0.12f};
            } else {
                weights = new float[]{0.55f, 0.25f, 0.20f};
            }
        }

        int[] counts = new int[mainCount];
        int primaryMin = Math.max(needed / 3, 4);
        int accentMin = 2;

        int assigned = 0;
        for (int i = 0; i < mainCount; i++) {
            int minCount = (i == 0 ? primaryMin : accentMin);
            int c = Math.round(weights[i] * needed);
            if (c < minCount) c = minCount;
            if (c > needed) c = needed;
            counts[i] = c;
            assigned += c;
        }

        if (assigned > needed) {
            int excess = assigned - needed;
            for (int i = mainCount - 1; i >= 0 && excess > 0; i--) {
                int minCount = (i == 0 ? primaryMin : accentMin);
                int reducible = counts[i] - minCount;
                if (reducible <= 0) continue;
                int delta = Math.min(reducible, excess);
                counts[i] -= delta;
                excess -= delta;
            }
            assigned = needed;
        }

        if (assigned < needed) {
            counts[0] += (needed - assigned);
        }

        List<Integer> out = new ArrayList<>(needed);
        for (int i = 0; i < mainCount; i++) {
            int color = bucketToColor(mainBuckets.get(i));
            for (int j = 0; j < counts[i]; j++) {
                out.add(color);
            }
        }

        // Shuffle with the shared unseeded Random
        Collections.shuffle(out, random);
        return out;
    }

    private static int bucketToColor(HueBucket bkt) {
        int avgR = (int) (bkt.sumR / bkt.count);
        int avgG = (int) (bkt.sumG / bkt.count);
        int avgB = (int) (bkt.sumB / bkt.count);
        return Color.rgb(avgR, avgG, avgB);
    }

    private int boostColorForVividness(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        if (paletteMode == PALETTE_COLORFUL) {
            hsv[1] = clampFloat(hsv[1] * 1.10f, 0.45f, 0.90f);
            float v = hsv[2];
            v = 0.50f + v * 0.30f;
            v = clampFloat(v, 0.55f, 0.80f);
            hsv[2] = v;
        } else if (paletteMode == PALETTE_DARK_MUTED) {
            hsv[1] = clampFloat(hsv[1] * 0.9f, 0.08f, 0.45f);
            float v = hsv[2];
            v = 0.18f + v * 0.24f;
            v = clampFloat(v, 0.12f, 0.46f);
            hsv[2] = v;
        } else {
            hsv[1] = clampFloat(hsv[1] * 1.1f, 0.05f, 0.35f);
            float v = hsv[2];
            v = 0.78f + v * 0.17f;
            v = clampFloat(v, 0.78f, 0.97f);
            hsv[2] = v;
        }

        if (!isAnalysisDefault()) {
            float valence = currentAnalysis.valence;
            float energy = currentAnalysis.energy;

            if (valence > 0.6f) {
                hsv[1] = clampFloat(hsv[1] * (1.0f + (valence - 0.5f) * 0.5f), 0f, 1f);
                hsv[2] = clampFloat(hsv[2] * 1.1f, 0f, 1f);
            } else if (valence < 0.4f) {
                hsv[1] *= (0.7f + valence * 0.3f);
                hsv[2] *= (0.8f + valence * 0.2f);
            }

            if (energy > 0.7f) {
                hsv[1] = clampFloat(hsv[1] * 1.15f, 0f, 1f);
            }
        }

        if (forceDarkBackground) {
            // Blob (highlight) colors — keep a clearly brighter ceiling than the base so the drifting
            // blobs stay visible against the dark backdrop (force-dark was flattening the motion).
            hsv[1] = Math.min(hsv[1] * FORCE_DARK_SATURATION_MULTIPLIER, 0.58f);
            hsv[2] = Math.min(hsv[2] * 0.95f, 0.48f);
        }

        return Color.HSVToColor(hsv);
    }

    private int buildBaseColor(int seedColor) {
        float[] hsv = new float[3];
        Color.colorToHSV(seedColor, hsv);

        if (paletteMode == PALETTE_COLORFUL) {
            hsv[1] *= 0.35f;
            float v = hsv[2];
            v = 0.13f + v * 0.19f;
            v = clampFloat(v, 0.13f, 0.32f);
            hsv[2] = v;
        } else if (paletteMode == PALETTE_DARK_MUTED) {
            hsv[1] *= 0.30f;
            float v = hsv[2];
            v = 0.03f + v * 0.18f;
            v = clampFloat(v, 0.03f, 0.24f);
            hsv[2] = v;
        } else {
            hsv[1] = Math.min(hsv[1] * 0.3f, 0.10f);
            float v = hsv[2];
            v = 0.85f + v * 0.10f;
            v = clampFloat(v, 0.85f, 0.99f);
            hsv[2] = v;
        }

        if (!isAnalysisDefault()) {
            if (currentAnalysis.valence < 0.3f) {
                hsv[2] *= 0.8f;
            } else if (currentAnalysis.valence > 0.7f) {
                hsv[2] = clampFloat(hsv[2] * 1.1f, 0f, 0.9f);
            }
        }

        if (forceDarkBackground) {
            // Base (backdrop) colors — kept low so the brighter blobs above read as motion.
            hsv[1] = Math.min(hsv[1] * 0.62f, 0.50f);
            hsv[2] = Math.min(hsv[2] * 0.52f, 0.18f);
        }

        return Color.HSVToColor(hsv);
    }

    // === RENDERING & NEW PHYSICS ===

    private void renderFrame(long dtNanos) {
        if (!isRendering.compareAndSet(false, true)) return;
        long renderStartNs = System.nanoTime();

        try {
            if (offW <= 0 || offH <= 0) return;

            int index = (renderHeadIndex + 1) % BUFFER_COUNT;
            Bitmap buffer = buffers[index];
            Canvas c = canvases[index];
            if (buffer == null || buffer.isRecycled() || c == null) return;

            c.drawColor(baseColor);

            float dt = dtNanos / 1_000_000_000f;
            float time = (SystemClock.elapsedRealtime() - startTimeMs) / 1000f;
            float frameScale = dt * 60f;

            // Defines the "safe zone" for blobs. If they go outside -0.3 to 1.3, we pull them back.
            // This prevents them from flying off into nothingness.
            float safeMin = -0.3f;
            float safeMax = 1.3f;

            // Standard target speed. We use this to normalize velocity so blobs don't stop.
            float targetSpeed = 0.0025f * animationSpeedMultiplier;

            for (int i = 0; i < blobs.size(); i++) {
                Blob b = blobs.get(i);

                // 1. Organic Steering
                // Randomly adjust velocity direction slightly every frame.
                // This creates a "wandering" effect rather than linear bouncing.
                b.vx += (random.nextFloat() - 0.5f) * 0.0005f * frameScale;
                b.vy += (random.nextFloat() - 0.5f) * 0.0005f * frameScale;

                // 2. Soft Boundaries (The Gravity Pull)
                // Instead of hard bouncing, if a blob is too far out, gently accelerate it towards the center.
                // This ensures they always return to the screen.
                if (b.x < safeMin) b.vx += 0.0002f * frameScale;
                else if (b.x > safeMax) b.vx -= 0.0002f * frameScale;

                if (b.y < safeMin) b.vy += 0.0002f * frameScale;
                else if (b.y > safeMax) b.vy -= 0.0002f * frameScale;

                // 3. Normalize Speed
                // Ensure the blob doesn't get too fast or completely stop.
                float currentSpeed = (float) Math.hypot(b.vx, b.vy);
                if (currentSpeed > 0.00001f) {
                    // Smoothly adjust current speed towards target speed
                    float newSpeed = currentSpeed * 0.95f + targetSpeed * 0.05f;
                    float scale = newSpeed / currentSpeed;
                    b.vx *= scale;
                    b.vy *= scale;
                }

                // 4. Update Position
                b.x += b.vx * frameScale;
                b.y += b.vy * frameScale;

                float drawX = b.x * offW;
                float drawY = b.y * offH;

                float breathe = (float) Math.sin(
                        time * (0.5f * breathingFrequency + (i % 4) * 0.1f) + i
                ) * 0.08f;
                float radiusPx = (b.radius + breathe) * Math.max(offW, offH);

                int cOp = b.color;
                int cTrans = cOp & 0x00FFFFFF;

                RadialGradient shader = new RadialGradient(
                        drawX, drawY, radiusPx,
                        new int[]{cOp, cTrans},
                        null,
                        Shader.TileMode.CLAMP
                );

                blobPaint.setShader(shader);
                blobPaint.setAlpha(190);
                c.drawCircle(drawX, drawY, radiusPx, blobPaint);
            }

            fastBoxBlurOpaque(buffer, BLUR_RADIUS, BLUR_PASSES);
            if (forceDarkBackground) applyForceDarkTint(c);

            synchronized (lock) {
                renderedBitmap = buffer;
                renderHeadIndex = index;
            }

            long renderEndNs = System.nanoTime();
            updateRenderMetrics((renderEndNs - renderStartNs) / 1_000_000f);

            mainHandler.post(this::postInvalidateOnAnimation);
        } finally {
            isRendering.set(false);
        }
    }

    // Multiply a near-black wash over the blurred frame so even bright art stays UI-legible.
    private void applyForceDarkTint(Canvas canvas) {
        if (canvas == null) return;
        int oldAlpha = blobPaint.getAlpha();
        Shader oldShader = blobPaint.getShader();
        Paint.Style oldStyle = blobPaint.getStyle();
        int oldColor = blobPaint.getColor();
        blobPaint.setShader(null);
        blobPaint.setStyle(Paint.Style.FILL);
        blobPaint.setColor(FORCE_DARK_TINT_COLOR);
        blobPaint.setAlpha(FORCE_DARK_TINT_ALPHA);
        canvas.drawRect(0, 0, offW, offH, blobPaint);
        blobPaint.setColor(oldColor);
        blobPaint.setAlpha(oldAlpha);
        blobPaint.setStyle(oldStyle);
        blobPaint.setShader(oldShader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap current, prev;
        long tStart;
        synchronized (lock) {
            current = renderedBitmap;
            prev = previousBitmap;
            tStart = transitionStartMs;
        }

        if (current == null || current.isRecycled()) {
            canvas.drawColor(baseColor);
            if (debugOverlayEnabled) drawDebugOverlay(canvas);
            return;
        }

        Rect dst = new Rect(0, 0, getWidth(), getHeight());

        if (isTransitioning && prev != null && !prev.isRecycled()) {
            float p = Math.min(
                    (SystemClock.elapsedRealtime() - tStart) / (float) TRANSITION_DURATION_MS,
                    1f
            );
            drawPaint.setAlpha((int) ((1f - p) * 255));
            canvas.drawBitmap(prev, null, dst, drawPaint);
            drawPaint.setAlpha((int) (p * 255));
            canvas.drawBitmap(current, null, dst, drawPaint);
            if (p >= 1f) {
                isTransitioning = false;
                synchronized (lock) {
                    previousBitmap = null;
                }
            }
        } else {
            drawPaint.setAlpha(255);
            canvas.drawBitmap(current, null, dst, drawPaint);
        }

        if (debugOverlayEnabled) {
            drawDebugOverlay(canvas);
        }
    }

    // === HELPERS ===

    private int calculateAverageColor(Bitmap b) {
        long r = 0, g = 0, blue = 0, count = 0;
        int w = b.getWidth(), h = b.getHeight();
        for (int x = 0; x < w; x += 10) {
            for (int y = 0; y < h; y += 10) {
                int c = b.getPixel(x, y);
                r += Color.red(c);
                g += Color.green(c);
                blue += Color.blue(c);
                count++;
            }
        }
        if (count == 0) return 0xFF101010;
        return Color.rgb((int) (r / count), (int) (g / count), (int) (blue / count));
    }

    private void fastBoxBlurOpaque(Bitmap srcDst, int radius, int passes) {
        if (blurBufA == null || blurBufA.length < offW * offH) return;
        srcDst.getPixels(blurBufA, 0, offW, 0, 0, offW, offH);
        for (int i = 0; i < passes; i++) {
            boxBlurHorizontal(blurBufA, blurBufB, offW, offH, radius);
            boxBlurVertical(blurBufB, blurBufA, offW, offH, radius);
        }
        srcDst.setPixels(blurBufA, 0, offW, 0, 0, offW, offH);
    }

    private static void boxBlurHorizontal(int[] src, int[] dst, int w, int h, int r) {
        final int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int tr = 0, tg = 0, tb = 0;
            int yi = y * w;
            for (int x = -r; x <= r; x++) {
                int c = src[yi + clamp(x, 0, w - 1)];
                tr += (c >> 16) & 0xFF;
                tg += (c >> 8) & 0xFF;
                tb += (c & 0xFF);
            }
            for (int x = 0; x < w; x++) {
                dst[yi + x] = 0xFF000000
                        | ((tr / div) << 16)
                        | ((tg / div) << 8)
                        | (tb / div);
                int cOut = src[yi + clamp(x - r, 0, w - 1)];
                int cIn = src[yi + clamp(x + r + 1, 0, w - 1)];
                tr += (((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF));
                tg += (((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF));
                tb += (((cIn) & 0xFF) - ((cOut) & 0xFF));
            }
        }
    }

    private static void boxBlurVertical(int[] src, int[] dst, int w, int h, int r) {
        final int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int tr = 0, tg = 0, tb = 0;
            for (int y = -r; y <= r; y++) {
                int c = src[clamp(y, 0, h - 1) * w + x];
                tr += (c >> 16) & 0xFF;
                tg += (c >> 8) & 0xFF;
                tb += (c & 0xFF);
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = 0xFF000000
                        | ((tr / div) << 16)
                        | ((tg / div) << 8)
                        | (tb / div);
                int cOut = src[clamp(y - r, 0, h - 1) * w + x];
                int cIn = src[clamp(y + r + 1, 0, h - 1) * w + x];
                tr += (((cIn >> 16) & 0xFF) - ((cOut >> 16) & 0xFF));
                tg += (((cIn >> 8) & 0xFF) - ((cOut >> 8) & 0xFF));
                tb += (((cIn) & 0xFF) - ((cOut) & 0xFF));
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }

    private static float clampFloat(float v, float lo, float hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private boolean debugOverlayEnabled = false;

    private float uiFps = 0f;
    private float renderFps = 0f;
    private float avgRenderMs = 0f;
    private float worstRenderMs = 0f;
    private int jankyUiFrames = 0;
    private int totalUiFrames = 0;
    private long lastMetricsSecondMs = 0;
    private int renderCountThisSecond = 0;
    private final Paint overlayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawDebugOverlay(Canvas canvas) {
        String line1 = String.format("UI %.1f fps", uiFps);
        String line2 = String.format("BG %.1f fps", renderFps);
        String line3 = String.format("Render %.2f ms", avgRenderMs);
        String line4 = String.format("Jank %.1f%%", getUiJankPercent());

        float pad = dp(8);
        float lineH = dp(14);
        float boxW = dp(120);
        float boxH = pad * 2 + lineH * 4;
        float margin = dp(8);

        float right = canvas.getWidth() - margin;
        float bottom = canvas.getHeight() - margin;
        float left = right - boxW;
        float top = bottom - boxH;

        canvas.drawRoundRect(left, top, right, bottom, dp(10), dp(10), overlayBgPaint);

        float tx = left + pad;
        float ty = top + pad + lineH - dp(2);

        canvas.drawText(line1, tx, ty, overlayTextPaint);
        canvas.drawText(line2, tx, ty + lineH, overlayTextPaint);
        canvas.drawText(line3, tx, ty + lineH * 2, overlayTextPaint);
        canvas.drawText(line4, tx, ty + lineH * 3, overlayTextPaint);
    }

    private void updateUiMetrics(long dtNs) {
        if (dtNs <= 0) return;

        float instantFps = 1_000_000_000f / dtNs;
        uiFps = (uiFps == 0f) ? instantFps : (uiFps * 0.9f + instantFps * 0.1f);

        totalUiFrames++;
        if (dtNs > 20_000_000L) { // >20ms
            jankyUiFrames++;
        }
    }

    private void updateRenderMetrics(float renderMs) {
        avgRenderMs = (avgRenderMs == 0f) ? renderMs : (avgRenderMs * 0.9f + renderMs * 0.1f);
        if (renderMs > worstRenderMs) worstRenderMs = renderMs;

        renderCountThisSecond++;

        long nowMs = SystemClock.elapsedRealtime();
        long dt = nowMs - lastMetricsSecondMs;
        if (dt >= 1000L) {
            renderFps = renderCountThisSecond * (1000f / dt);
            renderCountThisSecond = 0;
            lastMetricsSecondMs = nowMs;
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public float getUiJankPercent() {
        if (totalUiFrames == 0) return 0f;
        return (jankyUiFrames * 100f) / totalUiFrames;
    }

    private static class Blob {
        float x, y, vx, vy, radius;
        int color;

        Blob(float x, float y, float r, int c, float vx, float vy) {
            this.x = x;
            this.y = y;
            radius = r;
            color = c;
            this.vx = vx;
            this.vy = vy;
        }
    }
}