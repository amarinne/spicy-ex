package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.DisplayLayoutGroup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.FrameStyleBatcher;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsFrameRenderer;
import com.eza.spicyex.lyrics.LyricsLineViewState;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.LyricsRowViewFactory;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.ui.Motion;
import com.google.android.flexbox.FlexboxLayout;
import com.google.android.flexbox.JustifyContent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Android Auto sizing and cadence adapter for Spicy's existing mounted-row engine. */
public final class AutoLyricsView extends FrameLayout implements Choreographer.FrameCallback {
    private static final Set<Integer> SINGLE_INDEX = Collections.singleton(0);

    private final FrameLayout stage;
    private final SectionSlot[] slots;
    private final List<DepartingRow> departing = new ArrayList<>();
    private boolean creditOutro;
    private LinearLayout rowHost;
    private long transitions;
    private Bitmap artwork;
    private long artworkFadeStarted, artworkFades;
    private long entranceStarted;
    private final Paint artworkPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final LyricsRowViewFactory factory;
    private final FrameStyleBatcher styles;
    private final LyricsFrameRenderer renderer;
    private final LyricsRenderConfig config;
    private AppliedLine source, companion;
    private long companionEntranceStarted;
    private int fittedWidth, fittedHeight, fittedSettings;
    private Bundle sample, timelineSample;
    private int mountedGeneration = Integer.MIN_VALUE;
    private String contentKey = "";
    private List<int[]> pages = new ArrayList<>();
    private int page = -1, fontSp, primaryIndex;
    private float secondaryTextSp = 24f;
    private boolean active, scheduled, stress, planned, needsSync, showSecondary = true;
    private boolean duet;
    private int secondaryCap = AutoSurfaceFit.secondaryLineCap(false);
    private List<AutoDuetLayout.DuetSectionId> duetSlots = new ArrayList<>();
    private boolean interlude;
    private AppliedLine dotTiming;
    private boolean musicNote;
    private Typeface nativeTypeface = Typeface.DEFAULT;
    private String fontFamily = "System fallback";
    private long localBoundarySwitches, lastBoundaryLateMs, repeatReuses;
    private long previousFrame, frames, totalNanos, maxNanos;

    public AutoLyricsView(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        stage = new FrameLayout(context);
        addView(stage, new LayoutParams(-1, -1));
        slots = new SectionSlot[]{new SectionSlot(context), new SectionSlot(context)};
        factory = new LyricsRowViewFactory(context, new LyricsTextFactory(context, null));
        styles = new FrameStyleBatcher(context);
        renderer = new LyricsFrameRenderer(context, styles);
        config = LyricsRenderConfig.read(context, null).forAndroidAuto();
        int font = context.getResources().getIdentifier("google_sans", "font", context.getPackageName());
        if (font != 0) try {
            nativeTypeface = Typeface.create(context.getResources().getFont(font), 400, false);
            fontFamily = "Android Auto Google Sans";
        } catch (android.content.res.Resources.NotFoundException ignored) { }
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void update(Bundle value) {
        timelineSample = value;
        Bundle previousSample = sample;
        boolean previousCreditOutro = creditOutro, previousInterlude = interlude, previousStress = stress;
        Bundle next = value.getBundle("next");
        long nextAt = value.getLong("nextAtMs");
        long position = AutoPrototypePolicy.position(value.getLong("positionMs"), value.getLong("sampledAt"),
                value.getBoolean("playing"), value.getDouble("playbackRate", 1d), SystemClock.elapsedRealtime());
        creditOutro = value.getBoolean("creditOutroVisible");
        boolean advance = !creditOutro && !value.getBoolean("stress") && next != null && position >= nextAt;
        if (advance) {
            value = selectNext(value, next);
        }
        sample = value;
        if (immediateEntrance()) { artwork = null; entranceStarted = 0; }
        stress = value.getBoolean("stress");
        CharSequence creditLabels = creditText(value, " · ");
        musicNote = !creditOutro && !stress && "note".equals(value.getString("presentation", "note"));
        interlude = !creditOutro && !stress && (musicNote || value.getString("line", "").isEmpty());
        AppliedLine decoded = decode(value, stress);
        AppliedLine decodedCompanion = interlude ? null
                : duetCompanion(value, stress, String.valueOf(getTag()));
        if (creditOutro) {
            decoded = new AppliedLine();
            decoded.text = creditText(value, "\n").toString();
            decoded.syntheticWords = true;
            decoded.startMs = value.getLong("lastVocalEndMs");
            decoded.endMs = Long.MAX_VALUE;
            decodedCompanion = null;
        }
        String key = value.getInt("generation") + (interlude ? ":interlude:" + musicNote + ":" + decoded.startMs + ":" + decoded.endMs : ":" + value.getInt("lineIndex") + ":" + stress + ":" + contentSignature(decoded)) + ":config:" + AutoDisplaySettings.signature(value) + ":credit:" + creditOutro + ":" + creditLabels + ":" + value.getStringArrayList("creditUrls") + ":duet:" + (decodedCompanion == null ? "" : contentSignature(decodedCompanion));
        boolean changed = !key.equals(contentKey);
        if (changed) {
            if (advance) { localBoundarySwitches++; lastBoundaryLateMs = position - nextAt; }
            contentKey = key;
            boolean lyricTransition = !previousCreditOutro && !creditOutro
                    && !previousInterlude && !interlude
                    && !previousStress && !stress;
            boolean reused = lyricTransition
                    && tryFastUpdateMountedSlots(previousSample, value, decoded, decodedCompanion, position);
            if (!reused) {
                source = decoded; companion = decodedCompanion;
                dotTiming = interlude && !musicNote
                        ? com.eza.spicyex.lyrics.LyricTimeline.createAppliedDotRow(decoded.startMs, decoded.endMs, false) : null;
                pages = new ArrayList<>(); page = -1; planned = false; needsSync = true;
            }
        }
        active = true;
        if (changed || !hasMountedPrimary()) render(1f / 30f);
        schedule();
    }

    /**
     * Fast path for transitions where both mounted slots keep their exact geometry: role swaps
     * (such as a duet line finishing before the main line) and consecutive repeated lines that only
     * need their timing rebound in place.
     */
    private boolean tryFastUpdateMountedSlots(Bundle previousSample, Bundle currentSample,
                                              AppliedLine decoded, AppliedLine decodedCompanion, long position) {
        if (previousSample == null || source == null || !hasMountedPrimary()
                || !planned || pages.isEmpty() || page < 0 || page >= pages.size()) return false;
        int generation = currentSample.getInt("generation", Integer.MIN_VALUE);
        if (previousSample.getInt("generation", Integer.MIN_VALUE) != generation
                || mountedGeneration != generation) return false;
        if (fittedSettings != AutoDisplaySettings.signature(currentSample)) return false;
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0 || width != fittedWidth || height != fittedHeight) return false;
        int mountedCount = activeSlotCount();
        boolean incomingDuet = decodedCompanion != null;
        if ((mountedCount == 2) != incomingDuet) return false;

        boolean secondaryAllowed = AutoSurfaceFit.secondaryAllowed(String.valueOf(getTag()));
        boolean checkReading = secondaryAllowed && AutoDisplaySettings.showReading(currentSample);
        boolean checkTranslation = secondaryAllowed && AutoDisplaySettings.showTranslation(currentSample);
        AutoDuetLayout.DuetSectionId primaryId = sectionId(generation, currentSample,
                currentSample.getInt("lineIndex", -1));

        if (!incomingDuet) {
            SectionSlot slot = slots[0];
            if (slot.nonLyric || !AutoRepeatedLine.sameRenderedLine(slot.source, decoded, checkReading, checkTranslation))
                return false;
            int nextPage = AutoSurfaceFit.pageAt(pages, sourceOffset(decoded, position));
            if (nextPage != page) return false;
            if (!AutoRepeatedLine.rebindTiming(slot.mounted, decoded, slot.pageStart, slot.pageEnd)) return false;
            slot.source = decoded;
            slot.id = primaryId;
            slot.signature = contentSignature(slot.mounted);
            source = decoded;
            companion = null;
            duetSlots = primaryId == null ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(primaryId));
            LyricsLineViewState.requestFrame(slot.mounted);
            repeatReuses++;
            return true;
        }

        Bundle duetBundle = currentSample.getBundle("duet");
        AutoDuetLayout.DuetSectionId companionId = sectionId(generation, duetBundle,
                duetBundle == null ? -1 : duetBundle.getInt("lineIndex", -1));
        if (primaryId == null || companionId == null) return false;
        List<AutoDuetLayout.DuetSectionId> order = AutoDuetLayout.assignDuetSlots(
                Arrays.asList(primaryId, companionId), duetSlots);
        boolean companionFirst = !primaryId.equals(order.get(0));
        int targetPrimarySlot = companionFirst ? 1 : 0;
        int targetCompanionSlot = companionFirst ? 0 : 1;

        SectionSlot pSlot = slots[targetPrimarySlot];
        SectionSlot cSlot = slots[targetCompanionSlot];
        if (!pSlot.hasMounted() || !cSlot.hasMounted() || pSlot.nonLyric || cSlot.nonLyric) return false;

        boolean pExact = Objects.equals(pSlot.id, primaryId)
                && pSlot.pageEnd <= decoded.text.length()
                && Objects.equals(pSlot.signature, contentSignature(slice(decoded, pSlot.pageStart, pSlot.pageEnd)));
        boolean cExact = Objects.equals(cSlot.id, companionId)
                && Objects.equals(cSlot.signature, contentSignature(decodedCompanion));
        boolean pRepeat = !pExact && AutoRepeatedLine.sameRenderedLine(
                pSlot.source, decoded, checkReading, checkTranslation);
        boolean cRepeat = !cExact && AutoRepeatedLine.sameRenderedLine(
                cSlot.source, decodedCompanion, checkReading, checkTranslation);
        if ((!pExact && !pRepeat) || (!cExact && !cRepeat)) return false;

        // Only skip planning if the primary slot keeps its current single-page or active page slice.
        if (pSlot.pageStart != 0 || pSlot.pageEnd != decoded.text.length()) {
            if (targetPrimarySlot != primaryIndex) return false;
            int nextPage = AutoSurfaceFit.pageAt(pages, sourceOffset(decoded, position));
            if (nextPage != page) return false;
        }

        if (pRepeat && !AutoRepeatedLine.rebindTiming(pSlot.mounted, decoded, pSlot.pageStart, pSlot.pageEnd))
            return false;
        if (cRepeat && !AutoRepeatedLine.rebindTiming(
                cSlot.mounted, decodedCompanion, 0, decodedCompanion.text.length())) return false;

        if (pRepeat) {
            pSlot.source = decoded;
            pSlot.id = primaryId;
            pSlot.signature = contentSignature(pSlot.mounted);
            LyricsLineViewState.requestFrame(pSlot.mounted);
        }
        if (cRepeat) {
            cSlot.source = decodedCompanion;
            cSlot.id = companionId;
            cSlot.signature = contentSignature(cSlot.mounted);
            LyricsLineViewState.requestFrame(cSlot.mounted);
        }
        if (pRepeat || cRepeat) repeatReuses++;

        pSlot.isCompanion = false;
        cSlot.isCompanion = true;
        companionEntranceStarted = cSlot.deferredEntranceStarted;
        primaryIndex = targetPrimarySlot;
        fontSp = pSlot.fontSp;
        source = decoded;
        companion = decodedCompanion;
        duetSlots = new ArrayList<>(order);
        if (pSlot.pageStart == 0 && pSlot.pageEnd == decoded.text.length()) {
            pages = Collections.singletonList(new int[]{0, decoded.text.length()});
            page = 0;
        }
        return true;
    }

    public void stop() {
        active = false;
        Choreographer.getInstance().removeFrameCallback(this);
        scheduled = false; previousFrame = 0;
        finishFades();
        companionEntranceStarted = 0;
        for (SectionSlot slot : slots) {
            slot.deferredEntranceStarted = 0;
            slot.host.setAlpha(1f);
        }
        artwork = null;
        entranceStarted = 0;
    }

    private void schedule() {
        if (!scheduled && active && isAttachedToWindow() && isShown()) {
            scheduled = true; Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** Gate vsync timestamps, rather than adding a delay after the previous frame's work. */
    static boolean frameDue(long previous, long time) {
        // One millisecond of tolerance keeps 30 Hz on 60/90/120 Hz clocks with timestamp jitter.
        return previous == 0 || time - previous >= 32_333_333L;
    }

    @Override public void doFrame(long time) {
        scheduled = false;
        if (!active || !isAttachedToWindow() || !isShown()) return;
        if (!frameDue(previousFrame, time)) { schedule(); return; }
        float delta = previousFrame == 0 ? 1f / 30f : Math.min(.05f, (time - previousFrame) / 1_000_000_000f);
        previousFrame = time;
        if (timelineSample != null) {
            boolean outro = timelineSample.getBoolean("creditOutroVisible");
            if (outro != creditOutro || (!outro && timelineSample.getBundle("next") != null
                    && position() >= timelineSample.getLong("nextAtMs"))) update(timelineSample);
        }
        render(delta);
        if (artwork != null || entranceStarted != 0 || hasActiveFade() || (interlude && !musicNote)) invalidate();
        if (sample.getBoolean("playing") || hasActiveFade() || hasActiveDeferredEntrance()
                || artwork != null || entranceStarted != 0 || hasPendingAnimation()) schedule();
    }

    private long position() {
        return AutoPrototypePolicy.position(sample.getLong("positionMs"), sample.getLong("sampledAt"),
                sample.getBoolean("playing"), sample.getDouble("playbackRate", 1d), SystemClock.elapsedRealtime());
    }

    private void render(float delta) {
        if (source == null || sample == null || getWidth() <= getPaddingLeft() + getPaddingRight()
                || getHeight() <= getPaddingTop() + getPaddingBottom()) return;
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        // Report the box before fitting: a resize must clear an earlier "this box fits nothing".
        // The weather surface has its own native size observer; mixing both would flap.
        AndroidAutoPrototype.surfaceMeasured(String.valueOf(getTag()), width, height);
        int minimum = creditOutro ? 20 : "player".equals(getTag()) ? 40 : AutoSurfaceFit.MIN_SP;
        int maximum = creditOutro ? 24 : "weather".equals(getTag()) ? 28
                : AutoSurfaceFit.comfortableMaximum(width, getResources().getDisplayMetrics().scaledDensity);
        if (!planned) plan(minimum, maximum, width, height);
        planned = true;
        if (pages.isEmpty()) {
            companionEntranceStarted = 0;
            AndroidAutoPrototype.rejectSurface(String.valueOf(getTag()), sampleKey(sample), width, height, creditOutro);
            finishFades();
            clearAllSlots();
            stage.removeAllViews(); rowHost = null; fontSp = 0; page = -1; needsSync = false;
            duet = false; duetSlots = new ArrayList<>();
            return;
        }
        int offset = sourceOffset(source, position());
        int selected = AutoSurfaceFit.pageAt(pages, offset);
        if (selected != page || needsSync) syncSlots(selected, minimum, maximum, width, height);
        updateDeferredSlots();
        if (!creditOutro && !interlude) {
            long pos = position();
            for (SectionSlot slot : slots) {
                if (!slot.hasMounted() || slot.nonLyric || slot.host.getVisibility() != View.VISIBLE) continue;
                renderer.applySynced(slot.document, SINGLE_INDEX, slot.host, config, pos, 0, delta, false);
            }
        } else if (interlude && !musicNote && slots[0].currentRow instanceof LinearLayout) {
            LinearLayout dots = (LinearLayout) slots[0].currentRow;
            for (int i = 0; i < dots.getChildCount(); i++) dots.getChildAt(i).setAlpha(
                    Motion.animationsEnabled() ? AutoSurfaceFit.dotAlpha(dotTiming, position(), i) : 1f);
        }
        if (hasActiveFade()) stepFades();
    }

    /**
     * Choose the render mode before paging. A duet scene keeps both sung lines first and caps
     * each section's extra rows to one line; only an unfittable scene returns the primary alone,
     * where the extra line resumes its normal budget.
     */
    private void plan(int minimum, int maximum, int width, int height) {
        if (creditOutro) {
            companion = null; duet = false;
            showSecondary = true; secondaryCap = AutoSurfaceFit.secondaryLineCap(false);
            pages = fits(source, minimum, width, height)
                    ? Collections.singletonList(new int[]{0, source.text.length()}) : Collections.emptyList();
            return;
        }
        List<FitAttempt> attempts = new ArrayList<>();
        boolean secondarySurfaces = AutoSurfaceFit.secondaryAllowed(String.valueOf(getTag()));
        if (companion != null) {
            if (secondarySurfaces) attempts.add(new FitAttempt(true, true));
            attempts.add(new FitAttempt(true, false));
        }
        if (secondarySurfaces) attempts.add(new FitAttempt(false, true));
        attempts.add(new FitAttempt(false, false));
        for (FitAttempt attempt : attempts) {
            AppliedLine partner = attempt.duet ? companion : null;
            int cap = AutoSurfaceFit.secondaryLineCap(attempt.duet);
            List<int[]> candidate = AutoSurfaceFit.pages(source.text, source.displayLayoutGroups,
                    (start, end) -> sceneFits(slice(source, start, end), partner, minimum, width, height,
                            attempt.secondary, cap));
            if (candidate.isEmpty()) continue;
            duet = attempt.duet; showSecondary = attempt.secondary;
            secondaryCap = cap; pages = candidate;
            if (!attempt.duet) companion = null;
            return;
        }
        pages = new ArrayList<>();
        duet = false; companion = null;
    }

    /**
     * Reconciles each visual slot independently against the target primary and duet sections so
     * continuing or repeated lines never rebuild or crossfade when their partner changes.
     */
    private void syncSlots(int selected, int minimum, int maximum, int width, int height) {
        page = selected;
        needsSync = false;
        int[] range = pages.get(page);
        boolean nonLyric = creditOutro || interlude || stress;
        AppliedLine primarySlice = slice(source, range[0], range[1]);
        AppliedLine partner = duet && companion != null && !nonLyric ? companion : null;
        int selectedSize = AutoSurfaceFit.largestSize(minimum, maximum,
                size -> sceneFits(primarySlice, partner, size, width, height, showSecondary, secondaryCap));
        selectedSize = Math.max(minimum, selectedSize);
        fontSp = partner == null ? selectedSize : retainedDuetSize(primarySlice, false, selectedSize, width, height);
        int companionSize = partner == null ? selectedSize
                : retainedDuetSize(partner, true, selectedSize, width, height);

        int generation = sample.getInt("generation", Integer.MIN_VALUE);
        AutoDuetLayout.DuetSectionId primaryId = nonLyric ? null
                : sectionId(generation, sample, sample.getInt("lineIndex", -1));
        AutoDuetLayout.DuetSectionId companionId = null;
        boolean companionFirst = false;
        if (partner != null) {
            Bundle duetBundle = sample.getBundle("duet");
            companionId = sectionId(generation, duetBundle, duetBundle == null ? -1 : duetBundle.getInt("lineIndex", -1));
            if (primaryId != null && companionId != null) {
                List<AutoDuetLayout.DuetSectionId> order = AutoDuetLayout.assignDuetSlots(
                        Arrays.asList(primaryId, companionId), duetSlots);
                duetSlots = new ArrayList<>(order);
                companionFirst = !primaryId.equals(order.get(0));
            }
        } else {
            duetSlots = primaryId == null ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(primaryId));
        }

        SectionSpec primarySpec = new SectionSpec(primaryId, primarySlice, source, fontSp,
                showSecondary, secondaryCap, range[0], range[1], false, nonLyric);
        SectionSpec companionSpec = partner == null ? null : new SectionSpec(companionId, partner, companion,
                companionSize, showSecondary, secondaryCap, 0, partner.text.length(), true, false);
        SectionSpec[] targets = new SectionSpec[2];
        if (companionSpec != null && companionFirst) {
            targets[0] = companionSpec;
            targets[1] = primarySpec;
            primaryIndex = 1;
        } else {
            targets[0] = primarySpec;
            targets[1] = companionSpec;
            primaryIndex = 0;
        }

        boolean hadAnyMounted = hasMountedAny();
        boolean sameSurface = width == fittedWidth && height == fittedHeight
                && fittedSettings == AutoDisplaySettings.signature(sample)
                && mountedGeneration == generation;
        boolean secondaryAllowed = AutoSurfaceFit.secondaryAllowed(String.valueOf(getTag()));
        boolean checkReading = secondaryAllowed && AutoDisplaySettings.showReading(sample);
        boolean checkTranslation = secondaryAllowed && AutoDisplaySettings.showTranslation(sample);
        boolean animate = hadAnyMounted && Motion.animationsEnabled() && !immediateEntrance();

        ensureRowHost();
        boolean anyTransitioned = false;
        boolean anyReusedRepeat = false;

        // Detach vacated slot 1 first (preserving its current visual Y as an independent fade-out).
        if (targets[1] == null && slots[1].hasMounted()) {
            anyTransitioned |= vacateSlot(slots[1], animate);
            if (slots[1].host.getParent() == rowHost) rowHost.removeView(slots[1].host);
        }

        // Attach slot 1 host when entering a duet scene.
        if (targets[1] != null && slots[1].host.getParent() != rowHost) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Math.round(4 * getResources().getDisplayMetrics().density);
            rowHost.addView(slots[1].host, lp);
        }

        for (int i = 0; i < 2; i++) {
            SectionSpec target = targets[i];
            if (target == null) continue;
            SectionSlot slot = slots[i];
            if (sameSurface && slot.matchesExact(target)) {
                slot.isCompanion = target.isCompanion;
                if (target.isCompanion) companionEntranceStarted = slot.deferredEntranceStarted;
                continue;
            }
            if (sameSurface && slot.canReuseRepeat(target, checkReading, checkTranslation)
                    && AutoRepeatedLine.rebindTiming(slot.mounted, target.fullSource, target.pageStart, target.pageEnd)) {
                slot.source = target.fullSource;
                slot.id = target.id;
                slot.signature = contentSignature(slot.mounted);
                slot.isCompanion = target.isCompanion;
                if (target.isCompanion) companionEntranceStarted = slot.deferredEntranceStarted;
                LyricsLineViewState.requestFrame(slot.mounted);
                anyReusedRepeat = true;
                continue;
            }
            anyTransitioned |= replaceSlot(slot, target, animate, targets[1] != null);
        }

        if (anyReusedRepeat) repeatReuses++;
        if (anyTransitioned) transitions++;

        fittedWidth = width;
        fittedHeight = height;
        fittedSettings = AutoDisplaySettings.signature(sample);
        mountedGeneration = generation;
        styles.clearPendingWrites();

        // Compose can mount during layout after the first child measurement. Lay out the
        // owned stage now so a paused first row does not wait for another content change.
        stage.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        stage.layout(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + width, getPaddingTop() + height);
        setContentDescription(partner == null ? primarySlice.text
                : primarySlice.text + "\n" + partner.text);
    }

    private void ensureRowHost() {
        if (rowHost != null && rowHost.getParent() == stage) return;
        rowHost = new LinearLayout(getContext());
        rowHost.setOrientation(LinearLayout.VERTICAL);
        rowHost.addView(slots[0].host, new LinearLayout.LayoutParams(-1, -2));
        stage.addView(rowHost, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    private boolean replaceSlot(SectionSlot slot, SectionSpec target, boolean animate, boolean duetScene) {
        boolean startedFade = false;
        boolean wasVisible = slot.hasMounted() && slot.host.getVisibility() == View.VISIBLE;
        if (slot.hasMounted()) {
            startedFade |= detachToDeparting(slot, animate);
        }
        View row = build(target.slice, target.fontSp, target.showSecondary, target.secondaryCap);
        if (!creditOutro && !target.isCompanion && row instanceof LinearLayout) {
            LinearLayout lyrics = (LinearLayout) row;
            for (int i = 1; i < lyrics.getChildCount(); i++) if (lyrics.getChildAt(i) instanceof TextView)
                secondaryTextSp = ((TextView) lyrics.getChildAt(i)).getTextSize()
                        / getResources().getDisplayMetrics().scaledDensity;
        }
        slot.mount(target, row);
        boolean deferred = duetScene && !target.nonLyric && target.slice.startMs > position();
        if (animate && !deferred) {
            slot.fadeInStarted = SystemClock.elapsedRealtime();
            setMainAlpha(row, 0f);
            startedFade = true;
        } else {
            slot.fadeInStarted = 0;
            setMainAlpha(row, 1f);
        }
        if (deferred) {
            slot.host.setVisibility(View.INVISIBLE);
            slot.host.setAlpha(1f);
            slot.deferredEntranceStarted = 0;
            if (target.isCompanion) companionEntranceStarted = 0;
        } else {
            slot.host.setVisibility(View.VISIBLE);
            slot.host.setAlpha(1f);
            slot.deferredEntranceStarted = 0;
            if (target.isCompanion) companionEntranceStarted = 0;
        }
        return startedFade || (animate && wasVisible);
    }

    private boolean vacateSlot(SectionSlot slot, boolean animate) {
        boolean faded = detachToDeparting(slot, animate);
        slot.resetMetadata();
        slot.host.setVisibility(View.VISIBLE);
        slot.host.setAlpha(1f);
        companionEntranceStarted = 0;
        return faded;
    }

    private boolean detachToDeparting(SectionSlot slot, boolean animate) {
        View oldRow = slot.currentRow;
        AppliedLine oldMounted = slot.mounted;
        float currentAlpha = oldRow == null ? 0f : slot.host.getAlpha() * mainAlphaOf(oldRow);
        boolean visible = oldRow != null && slot.host.getVisibility() == View.VISIBLE
                && oldRow.getWidth() > 0 && oldRow.getHeight() > 0 && currentAlpha > .01f;
        int top = oldRow == null ? 0 : (rowHost == null ? 0 : rowHost.getTop()) + slot.host.getTop() + oldRow.getTop();
        int left = oldRow == null ? 0 : (rowHost == null ? 0 : rowHost.getLeft()) + slot.host.getLeft() + oldRow.getLeft();
        int width = oldRow == null ? 0 : oldRow.getWidth();
        int height = oldRow == null ? 0 : oldRow.getHeight();
        if (oldMounted != null) {
            LyricsLineViewState.clear(oldMounted, null, old -> LyricsLineViewState.invalidate(old, styles));
        }
        slot.host.removeAllViews();
        slot.currentRow = null;
        slot.mounted = null;
        slot.document.appliedLines.clear();
        slot.fadeInStarted = 0;
        if (animate && visible) {
            hideSecondaryRow(oldRow);
            setMainAlpha(oldRow, currentAlpha);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.START);
            lp.leftMargin = left;
            lp.topMargin = top;
            // Keep rowHost as the last child of stage so departing overlays sit behind active rows.
            stage.addView(oldRow, 0, lp);
            departing.add(new DepartingRow(oldRow, currentAlpha, SystemClock.elapsedRealtime()));
            return true;
        }
        return false;
    }

    private static AutoDuetLayout.DuetSectionId sectionId(int generation, Bundle row, int ordinal) {
        if (row == null || ordinal < 0) return null;
        return new AutoDuetLayout.DuetSectionId(generation, row.getLong("lineStartMs"),
                row.getLong("lineEndMs"), ordinal);
    }

    private void updateDeferredSlots() {
        boolean duetScene = activeSlotCount() == 2;
        long pos = position();
        for (SectionSlot slot : slots) {
            if (!slot.hasMounted()) continue;
            if (!duetScene || slot.nonLyric) {
                slot.host.setVisibility(View.VISIBLE);
                slot.host.setAlpha(1f);
                slot.deferredEntranceStarted = 0;
                if (slot.isCompanion) companionEntranceStarted = 0;
                continue;
            }
            boolean deferred = slot.mounted.startMs > pos;
            if (deferred) {
                slot.host.setVisibility(View.INVISIBLE);
                slot.host.setAlpha(1f);
                slot.deferredEntranceStarted = 0;
                if (slot.isCompanion) companionEntranceStarted = 0;
                continue;
            }
            long started = slot.isCompanion ? companionEntranceStarted : slot.deferredEntranceStarted;
            if (slot.host.getVisibility() != View.VISIBLE && Motion.animationsEnabled()) {
                started = SystemClock.elapsedRealtime();
            }
            slot.host.setVisibility(View.VISIBLE);
            float alpha = started == 0 || !Motion.animationsEnabled() ? 1f
                    : Math.min(1f, (SystemClock.elapsedRealtime() - started)
                            / (float) Math.max(210, Motion.dur(210)));
            slot.host.setAlpha(alpha);
            if (alpha >= 1f) started = 0;
            slot.deferredEntranceStarted = started;
            if (slot.isCompanion) companionEntranceStarted = started;
        }
    }

    private boolean hasMountedPrimary() {
        return slots[primaryIndex].hasMounted();
    }

    private boolean hasMountedAny() {
        return slots[0].hasMounted() || slots[1].hasMounted();
    }

    private int activeSlotCount() {
        return (slots[0].hasMounted() ? 1 : 0) + (slots[1].hasMounted() ? 1 : 0);
    }

    private SectionSlot companionSlot() {
        if (activeSlotCount() != 2) return null;
        return slots[primaryIndex == 0 ? 1 : 0];
    }

    private boolean hasActiveFade() {
        return slots[0].fadeInStarted != 0 || slots[1].fadeInStarted != 0 || !departing.isEmpty();
    }

    private boolean hasActiveDeferredEntrance() {
        return companionEntranceStarted != 0
                || slots[0].deferredEntranceStarted != 0 || slots[1].deferredEntranceStarted != 0;
    }

    private boolean hasPendingAnimation() {
        for (SectionSlot slot : slots) {
            if (slot.hasMounted() && !slot.nonLyric
                    && renderer.hasPendingAnimation(slot.document, SINGLE_INDEX, slot.host, 0, 0, 1))
                return true;
        }
        return false;
    }

    private void clearAllSlots() {
        for (SectionSlot slot : slots) slot.clear(styles);
    }

    private static View mainOf(View row) {
        return row instanceof LinearLayout && ((LinearLayout) row).getOrientation() == LinearLayout.VERTICAL
                && ((ViewGroup) row).getChildCount() > 0
                ? ((ViewGroup) row).getChildAt(0) : row;
    }

    private View primaryMainView() {
        SectionSlot slot = slots[primaryIndex].hasMounted() ? slots[primaryIndex] : slots[0];
        return slot.currentRow == null ? null : mainOf(slot.currentRow);
    }

    private static float mainAlphaOf(View row) {
        View main = mainOf(row);
        return main == null ? 1f : main.getAlpha();
    }

    private static void setMainAlpha(View row, float alpha) {
        View main = mainOf(row);
        if (main != null) main.setAlpha(alpha);
    }

    private static void hideSecondaryRow(View row) {
        if (row instanceof LinearLayout && ((LinearLayout) row).getOrientation() == LinearLayout.VERTICAL)
            for (int i = 1; i < ((ViewGroup) row).getChildCount(); i++)
                ((ViewGroup) row).getChildAt(i).setVisibility(View.INVISIBLE);
    }

    private void finishFades() {
        for (DepartingRow row : departing) stage.removeView(row.view);
        departing.clear();
        for (SectionSlot slot : slots) {
            slot.fadeInStarted = 0;
            if (slot.currentRow != null) setMainAlpha(slot.currentRow, 1f);
        }
    }

    private void stepFades() {
        long now = SystemClock.elapsedRealtime();
        int inDuration = Math.max(210, Motion.dur(210));
        int outDuration = Math.max(130, Motion.dur(130));
        for (SectionSlot slot : slots) {
            if (slot.fadeInStarted == 0 || slot.currentRow == null) continue;
            long elapsed = now - slot.fadeInStarted;
            float in = Math.min(1f, elapsed / (float) inDuration);
            setMainAlpha(slot.currentRow, in);
            if (elapsed >= inDuration) {
                slot.fadeInStarted = 0;
                setMainAlpha(slot.currentRow, 1f);
            }
        }
        Iterator<DepartingRow> it = departing.iterator();
        while (it.hasNext()) {
            DepartingRow row = it.next();
            long elapsed = now - row.started;
            float out = Math.max(0f, 1f - elapsed / (float) outDuration);
            setMainAlpha(row.view, row.initialAlpha * out);
            if (elapsed >= outDuration) {
                stage.removeView(row.view);
                it.remove();
            }
        }
    }

    private static final class FitAttempt {
        final boolean duet, secondary;
        FitAttempt(boolean duet, boolean secondary) { this.duet = duet; this.secondary = secondary; }
    }

    private static final class DepartingRow {
        final View view;
        final float initialAlpha;
        final long started;
        DepartingRow(View view, float initialAlpha, long started) {
            this.view = view; this.initialAlpha = initialAlpha; this.started = started;
        }
    }

    private static final class SectionSpec {
        final AutoDuetLayout.DuetSectionId id;
        final AppliedLine slice, fullSource;
        final String signature;
        final int fontSp, secondaryCap, pageStart, pageEnd;
        final boolean showSecondary, isCompanion, nonLyric;

        SectionSpec(AutoDuetLayout.DuetSectionId id, AppliedLine slice, AppliedLine fullSource, int fontSp,
                    boolean showSecondary, int secondaryCap, int pageStart, int pageEnd,
                    boolean isCompanion, boolean nonLyric) {
            this.id = id;
            this.slice = slice;
            this.fullSource = fullSource;
            this.signature = slice == null ? null : contentSignature(slice);
            this.fontSp = fontSp;
            this.showSecondary = showSecondary;
            this.secondaryCap = secondaryCap;
            this.pageStart = pageStart;
            this.pageEnd = pageEnd;
            this.isCompanion = isCompanion;
            this.nonLyric = nonLyric;
        }
    }

    /** Independent rendering host and lifecycle state for one lyric section (main or duet). */
    private static final class SectionSlot {
        final FrameLayout host;
        final LyricsDocument document = new LyricsDocument();
        AutoDuetLayout.DuetSectionId id;
        AppliedLine source, mounted;
        String signature;
        View currentRow;
        int fontSp, secondaryCap, pageStart, pageEnd;
        boolean showSecondary, isCompanion, nonLyric;
        long fadeInStarted, deferredEntranceStarted;

        SectionSlot(Context context) {
            host = new FrameLayout(context);
            host.setClipChildren(false);
            host.setClipToPadding(false);
            document.type = "Syllable";
        }

        boolean hasMounted() { return mounted != null && currentRow != null; }

        boolean matchesExact(SectionSpec target) {
            return hasMounted() && !nonLyric && !target.nonLyric
                    && fontSp == target.fontSp
                    && showSecondary == target.showSecondary
                    && secondaryCap == target.secondaryCap
                    && pageStart == target.pageStart && pageEnd == target.pageEnd
                    && Objects.equals(id, target.id)
                    && Objects.equals(signature, target.signature);
        }

        boolean canReuseRepeat(SectionSpec target, boolean checkReading, boolean checkTranslation) {
            return hasMounted() && !nonLyric && !target.nonLyric
                    && fontSp == target.fontSp
                    && showSecondary == target.showSecondary
                    && secondaryCap == target.secondaryCap
                    && pageStart == target.pageStart && pageEnd == target.pageEnd
                    && AutoRepeatedLine.sameRenderedLine(source, target.fullSource, checkReading, checkTranslation);
        }

        void mount(SectionSpec target, View row) {
            host.removeAllViews();
            host.addView(row, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            id = target.id;
            source = target.fullSource;
            mounted = target.slice;
            signature = target.signature;
            currentRow = row;
            fontSp = target.fontSp;
            showSecondary = target.showSecondary;
            secondaryCap = target.secondaryCap;
            pageStart = target.pageStart;
            pageEnd = target.pageEnd;
            isCompanion = target.isCompanion;
            nonLyric = target.nonLyric;
            document.appliedLines.clear();
            document.appliedLines.add(mounted);
        }

        void resetMetadata() {
            id = null;
            source = null;
            mounted = null;
            signature = null;
            currentRow = null;
            fontSp = 0;
            pageStart = 0;
            pageEnd = 0;
            isCompanion = false;
            nonLyric = false;
            fadeInStarted = 0;
            deferredEntranceStarted = 0;
            document.appliedLines.clear();
        }

        void clear(FrameStyleBatcher styles) {
            if (mounted != null) {
                LyricsLineViewState.clear(mounted, host, old -> LyricsLineViewState.invalidate(old, styles));
            }
            host.removeAllViews();
            host.setVisibility(View.VISIBLE);
            host.setAlpha(1f);
            resetMetadata();
        }
    }

    private View build(AppliedLine line, int size) {
        return build(line, size, showSecondary, secondaryCap);
    }

    private View build(AppliedLine line, int size, boolean secondary, int cap) {
        if (creditOutro) {
            TextView credit = new TextView(getContext());
            credit.setText(creditText(sample, "\n"));
            credit.setTextSize(secondaryTextSp); credit.setTextColor(android.graphics.Color.WHITE);
            credit.setTypeface(nativeTypeface); credit.setGravity(Gravity.CENTER);
            return credit;
        }
        if (musicNote) {
            TextView note = new TextView(getContext());
            note.setText("♪"); note.setTextSize(size * 2); note.setTextColor(android.graphics.Color.WHITE);
            note.setTypeface(nativeTypeface); note.setGravity(Gravity.CENTER);
            return note;
        }
        if (interlude) {
            LinearLayout dots = new LinearLayout(getContext());
            dots.setOrientation(LinearLayout.HORIZONTAL); dots.setGravity(Gravity.CENTER);
            for (int i = 0; i < 3; i++) {
                TextView dot = new TextView(getContext());
                dot.setText("•"); dot.setTextSize(size * 2); dot.setTextColor(android.graphics.Color.WHITE);
                dot.setTypeface(nativeTypeface); dot.setGravity(Gravity.CENTER);
                dots.addView(dot, new LinearLayout.LayoutParams(-2, -2));
            }
            return dots;
        }
        LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();
        options.adaptiveTextSizeEnabled = false;
        options.textSizeMultiplier = size / 28f;
        options.lyricWeight = "Regular"; options.lyricsFont = "system";
        options.wordLevelFill = true; options.lineLevelFillTopDown = false;
        options.lineLevelFillSentence = true; options.sequentialLineFill = true;
        // Word-timed rows sweep each word on its own timing (owner, 2026-11-12: the broad
        // sentence wave looked jagged). Line-timed rows keep the soft sentence sweep.
        options.continuousSentenceFill = line.syntheticWords || line.words.isEmpty();
        options.showRomanization = secondary && AutoDisplaySettings.showReading(sample);
        options.showJapaneseRomaji = options.showRomanization;
        options.showTranslation = secondary && AutoDisplaySettings.showTranslation(sample); options.translationBright = true;
        options.staticSecondaryText = true;
        options.attachTransliterationToWords = false;
        options.adaptiveSectioningEnabled = true;
        options.horizontalSafetyPadding = false;
        LinearLayout row = factory.build(line, options, null);
        center(row);
        applyNativeTypeface(row, line.syntheticWords || line.words.isEmpty());
        // Secondary rows have a stable readable size and a bounded reserve on small cards.
        for (int i = 1; i < row.getChildCount(); i++) if (row.getChildAt(i) instanceof TextView) {
            TextView text = (TextView) row.getChildAt(i);
            text.setTextSize(Math.max(20, Math.min(24, size * .72f)));
            text.setMaxLines(cap); text.setEllipsize(TextUtils.TruncateAt.END);
        }
        return row;
    }

    private void applyNativeTypeface(View view, boolean lineLevel) {
        if (view instanceof TextView) ((TextView) view).setTypeface(nativeTypeface);
        if (view instanceof com.eza.spicyex.lyrics.SpicyAnimatedTextView) {
            com.eza.spicyex.lyrics.SpicyAnimatedTextView text =
                    (com.eza.spicyex.lyrics.SpicyAnimatedTextView) view;
            text.setSoftSweep(lineLevel);
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            applyNativeTypeface(((ViewGroup) view).getChildAt(i), lineLevel);
    }

    private static void center(View view) {
        if (view instanceof TextView) ((TextView) view).setGravity(Gravity.CENTER);
        if (view instanceof LinearLayout) ((LinearLayout) view).setGravity(Gravity.CENTER);
        if (view instanceof FlexboxLayout) ((FlexboxLayout) view).setJustifyContent(JustifyContent.CENTER);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) center(((ViewGroup) view).getChildAt(i));
    }

    private boolean fits(AppliedLine value, int size, int width, int height) {
        AppliedLine trial = slice(value, 0, value.text.length());
        View row = build(trial, size);
        int used = measure(row, width);
        boolean fits = used <= height && mainFits(mainOf(row), width);
        LyricsLineViewState.clear(trial, stage, null);
        return fits;
    }

    /** A continuing or repeated duet section retains its size while a newcomer fits remaining space. */
    private int retainedDuetSize(AppliedLine line, boolean partner, int fallback, int width, int height) {
        int generation = sample.getInt("generation", Integer.MIN_VALUE);
        if (activeSlotCount() != 2 || width != fittedWidth || height != fittedHeight
                || fittedSettings != AutoDisplaySettings.signature(sample)
                || mountedGeneration != generation) return fallback;
        Bundle row = partner ? sample.getBundle("duet") : sample;
        AutoDuetLayout.DuetSectionId id = sectionId(generation, row,
                row == null ? -1 : row.getInt("lineIndex", -1));
        String signature = contentSignature(line);
        for (SectionSlot slot : slots) {
            if (!slot.hasMounted() || slot.nonLyric) continue;
            if (id != null && id.equals(slot.id) && signature.equals(slot.signature)) return slot.fontSp;
        }
        boolean secondaryAllowed = AutoSurfaceFit.secondaryAllowed(String.valueOf(getTag()));
        boolean checkReading = secondaryAllowed && AutoDisplaySettings.showReading(sample);
        boolean checkTranslation = secondaryAllowed && AutoDisplaySettings.showTranslation(sample);
        AppliedLine full = partner ? companion : source;
        for (SectionSlot slot : slots) {
            if (!slot.hasMounted() || slot.nonLyric) continue;
            if (AutoRepeatedLine.sameRenderedLine(slot.source, full, checkReading, checkTranslation))
                return slot.fontSp;
        }
        return fallback;
    }

    /** Fit both sections without resizing an unchanged section in an unchanged surface. */
    private boolean sceneFits(AppliedLine page, AppliedLine partner, int size, int width, int height,
                              boolean secondary, int cap) {
        AppliedLine trial = slice(page, 0, page.text.length());
        int primarySize = partner == null ? size : retainedDuetSize(page, false, size, width, height);
        View row = build(trial, primarySize, secondary, cap);
        int used = measure(row, width);
        boolean fits = used <= height && mainFits(mainOf(row), width);
        LyricsLineViewState.clear(trial, stage, null);
        if (!fits) return false;
        if (partner == null) return true;
        AppliedLine other = slice(partner, 0, partner.text.length());
        View companionRow = build(other, retainedDuetSize(partner, true, size, width, height), secondary, cap);
        used += Math.round(4 * getResources().getDisplayMetrics().density) + measure(companionRow, width);
        fits = mainFits(mainOf(companionRow), width);
        LyricsLineViewState.clear(other, stage, null);
        return fits && used <= height;
    }

    private static int measure(View row, int width) {
        row.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, width, row.getMeasuredHeight());
        return row.getMeasuredHeight();
    }

    private boolean mainFits(View view, int width) {
        if (view.getMeasuredWidth() > width) return false;
        if (view instanceof com.eza.spicyex.lyrics.GlowFlexbox
                && ((com.eza.spicyex.lyrics.GlowFlexbox) view).sentenceVisualLineCount() > 3) return false;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.text.Layout layout = text.getLayout();
            if (layout != null && layout.getLineCount() > 0) {
                if (layout.getLineCount() > Math.min(creditOutro ? 12 : 3, text.getMaxLines())) return false;
                if (layout.getLineEnd(layout.getLineCount() - 1) < text.getText().length()) return false;
                for (int i = 0; i < layout.getLineCount(); i++) if (layout.getLineWidth(i) > width + 1) return false;
            }
        }
        if (view instanceof FlexboxLayout && ((FlexboxLayout) view).getFlexLines().size() > 3) return false;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (!mainFits(((ViewGroup) view).getChildAt(i), width)) return false;
        return true;
    }

    static String contentSignature(AppliedLine line) {
        StringBuilder key = new StringBuilder();
        key.append(line.text).append('\0').append(line.romanizedText).append('\0').append(line.translatedText)
                .append(':').append(line.startMs).append(':').append(line.endMs).append(':').append(line.syntheticWords);
        for (SyllableSegment word : line.words) key.append('\0').append(word.text).append('\0').append(word.romanizedText)
                .append(':').append(word.startMs).append(':').append(word.endMs).append(':').append(word.canonicalStartCp)
                .append(':').append(word.canonicalEndCp).append(':').append(word.boundaryAfter);
        if (line.displayLayoutGroups != null) for (DisplayLayoutGroup group : line.displayLayoutGroups)
            key.append("|group:").append(group.start).append(':').append(group.end);
        return key.toString();
    }

    static int sourceOffset(AppliedLine line, long position) {
        int count = line.text.codePointCount(0, line.text.length());
        if (count == 0) return 0;
        float progress = com.eza.spicyex.lyrics.SentenceGradientPlanner.sourceProgress(line, position);
        return line.text.offsetByCodePoints(0, Math.min(count - 1, (int) (count * progress)));
    }

    static AppliedLine slice(AppliedLine source, int start, int end) {
        AppliedLine line = new AppliedLine();
        line.text = source.text.substring(start, end);
        line.startMs = source.startMs; line.endMs = source.endMs; line.totalMs = source.totalMs;
        line.romanizedText = source.romanizedText; line.translatedText = source.translatedText;
        line.syntheticWords = source.syntheticWords;
        line.gradientSource = source.gradientSource == null ? source : source.gradientSource;
        line.smoothSentenceGradient = true;
        int origin = source.gradientSource == null ? 0 : source.gradientStartCp;
        line.gradientStartCp = origin + source.text.codePointCount(0, start);
        line.gradientEndCp = origin + source.text.codePointCount(0, end);
        if (source.displayLayoutGroups != null) {
            line.displayLayoutGroups = new ArrayList<>();
            for (DisplayLayoutGroup group : source.displayLayoutGroups) {
                int from = Math.max(start, group.start), to = Math.min(end, group.end);
                if (from < to) line.displayLayoutGroups.add(new DisplayLayoutGroup(
                        from - start, to - start, group.kind, group.keepTogether, group.confidence));
            }
        }
        for (SyllableSegment original : source.words) {
            int from = source.text.offsetByCodePoints(0, original.canonicalStartCp);
            int to = source.text.offsetByCodePoints(0, original.canonicalEndCp);
            from = Math.max(from, start); to = Math.min(to, end);
            if (from >= to) continue;
            SyllableSegment word = SyllableSegment.copyOf(original);
            word.text = source.text.substring(from, to); word.sourceText = word.text;
            word.canonicalStartCp = source.text.codePointCount(start, from);
            word.canonicalEndCp = source.text.codePointCount(start, to);
            line.words.add(word);
        }
        return line;
    }

    @SuppressWarnings("deprecation")
    private static AppliedLine decode(Bundle value, boolean stress) {
        AppliedLine line = new AppliedLine();
        line.text = value.getString("line", ""); line.romanizedText = value.getString("romanized", "");
        line.translatedText = value.getString("secondary", "");
        line.startMs = value.getLong("lineStartMs"); line.endMs = value.getLong("lineEndMs");
        line.totalMs = Math.max(1, line.endMs - line.startMs);
        int[] groups = value.getIntArray("layoutGroups");
        if (groups != null) {
            AutoSurfaceFit.validateGroups(line.text, groups);
            line.displayLayoutGroups = new ArrayList<>();
            for (int i = 0; i < groups.length; i += 2) line.displayLayoutGroups.add(
                    new DisplayLayoutGroup(groups[i], groups[i + 1], "transport", true, 1));
        }
        line.syntheticWords = value.getBoolean("lineLevelSync");
        if (stress) {
            StringBuilder text = new StringBuilder("SYNTHETIC STRESS · ");
            while (text.length() < 500) text.append("large words 日本語 한국어 العربية हिन्दी tiếng Việt 🎵 ");
            line.text = text.substring(0, 500); line.syntheticWords = true; line.displayLayoutGroups = null;
            line.romanizedText = "Synthetic reading text"; line.translatedText = "Synthetic translation";
            return line;
        }
        if ("note".equals(value.getString("presentation", "note")) || line.text.isEmpty()) {
            line.text = "note".equals(value.getString("presentation", "note")) ? "♪" : "•••"; line.romanizedText = ""; line.translatedText = "";
            if ("note".equals(value.getString("presentation", "note"))) {
                line.startMs = 0; line.endMs = 1; line.totalMs = 1;
            }
            return line;
        }
        ArrayList<Bundle> words = value.getParcelableArrayList("words");
        int cursor = 0;
        if (words != null && AutoWordCoverage.withinLimit(words.size())) for (Bundle item : words) {
            String text = item.getString("text", "");
            int from = item.getInt("sourceStart", -1), to = item.getInt("sourceEnd", -1);
            if (from < 0 || to <= from || to > line.text.length()) { from = line.text.indexOf(text, cursor); to = from + text.length(); }
            if (from < 0 || to <= from || to > line.text.length()) continue;
            if (from > 0 && Character.isLowSurrogate(line.text.charAt(from))
                    || to < line.text.length() && Character.isLowSurrogate(line.text.charAt(to))) continue;
            SyllableSegment word = new SyllableSegment(); word.text = text; word.sourceText = text;
            word.romanizedText = item.getString("romanized", "");
            word.startMs = item.getLong("startMs"); word.endMs = item.getLong("endMs"); word.totalMs = Math.max(1, word.endMs - word.startMs);
            word.boundaryAfter = item.getBoolean("boundaryAfter"); word.partOfWord = !word.boundaryAfter;
            word.canonicalStartCp = line.text.codePointCount(0, from); word.canonicalEndCp = line.text.codePointCount(0, to);
            line.words.add(word); cursor = to;
        }
        AutoWordCoverage.fallbackIfIncomplete(line);
        return line;
    }

    /**
     * The duet companion for this row: enabled, a lyrics surface, a real lyric row, and text to
     * show. A provider companion that is a note, dots, or blank never joins the scene.
     */
    static AppliedLine duetCompanion(Bundle value, boolean stress, String target) {
        if (value == null || stress || !AutoSurfaceFit.duetAllowed(target)
                || !AutoDisplaySettings.duet(value)) return null;
        Bundle duet = value.getBundle("duet");
        if (duet == null || !"lyric".equals(duet.getString("presentation", "note"))
                || duet.getString("line", "").trim().isEmpty()) return null;
        AppliedLine companion = decode(duet, false);
        return companion.text.trim().isEmpty() ? null : companion;
    }

    static Bundle selectNext(Bundle value, Bundle next) {
        Bundle selected = new Bundle(value);
        // Accept row keys only, including when a host supplies an older full-default next packet.
        Bundle row = new Bundle(next);
        for (String key : new ArrayList<>(row.keySet()))
            if (!AutoPrototypeProvider.isRowKey(key)) row.remove(key);
        selected.putAll(row);
        // The prefetched companion replaces the current one; a solo next row clears a stale duet.
        Bundle duet = next.getBundle("duet");
        if (duet != null) selected.putBundle("duet", new Bundle(duet));
        else selected.remove("duet");
        return selected;
    }

    static String sampleKey(Bundle value) {
        if (value == null) return "";
        Bundle row = value;
        Bundle next = value.getBundle("next");
        if (!value.getBoolean("stress") && next != null
                && AutoPrototypePolicy.position(value.getLong("positionMs"), value.getLong("sampledAt"),
                        value.getBoolean("playing"), value.getDouble("playbackRate", 1d), SystemClock.elapsedRealtime()) >= value.getLong("nextAtMs")) row = next;
        return value.getInt("generation") + ":" + value.getString("trackUri", "")
                + ":" + row.getInt("lineIndex", -1) + ":" + contentSignature(decode(row, value.getBoolean("stress")))
                + ":" + row.getString("presentation", "") + ":" + value.getBoolean("stress")
                + ":" + AutoDisplaySettings.signature(value) + ":credit:" + value.getStringArrayList("creditLabels")
                + ":" + value.getStringArrayList("creditUrls") + ":writers:" + value.getString("creditWriters", "")
                + ":outro:" + value.getBoolean("creditOutroVisible")
                + ":duet:" + (!AutoDisplaySettings.duet(value) || row.getBundle("duet") == null ? ""
                        : contentSignature(decode(row.getBundle("duet"), false)));
    }

    private static CharSequence creditText(Bundle value, String separator) {
        return AutoResponseCredit.text(value.getString("creditWriters", ""), value.getStringArrayList("creditLabels"), separator);
    }

    boolean creditVisibleFor(int generation, String trackUri) {
        View content = primaryMainView();
        return active && creditOutro && getAlpha() > 0f && content != null && content.getAlpha() > 0f
                && content.getWidth() > 0 && content.getHeight() > 0
                && sample != null && AutoPrototypePolicy.sameSession(generation, trackUri,
                        sample.getInt("generation"), sample.getString("trackUri", ""));
    }

    public Bundle metrics() {
        Bundle result = new Bundle(); result.putString("renderer", "Spicy mounted row");
        result.putLong("transitionCount", transitions); result.putInt("mountedHosts", stage.getChildCount());
        result.putString("transition", "Fade 130ms out / 210ms in"); result.putBoolean("transitionActive", hasActiveFade());
        result.putInt("targetFps", 30); result.putBoolean("bounce", config.wordBounceEnabled || config.appleLift);
        result.putInt("fontSp", fontSp); result.putInt("page", page + 1); result.putInt("pages", pages.size());
        result.putLong("artworkFades", artworkFades); result.putBoolean("artworkFadeActive", artwork != null);
        result.putBoolean("entranceActive", entranceStarted != 0);
        result.putString("lineGradient", "Left to right through visual lines");
        result.putBoolean("interlude", interlude);
        result.putBoolean("creditVisible", creditOutro);
        result.putBoolean("creditOutro", creditOutro);
        result.putString("presentation", creditOutro ? "Response credit" : musicNote ? "Static music note" : interlude ? "Interlude dots" : "Synced lyric");
        result.putString("fontFamily", fontFamily); result.putInt("fontWeight", 400);
        result.putLong("localBoundarySwitches", localBoundarySwitches);
        result.putLong("lastBoundaryLateMs", lastBoundaryLateMs);
        result.putLong("repeatReuses", repeatReuses);
        result.putBoolean("lineSynced", source != null && source.syntheticWords);
        result.putBoolean("noFit", planned && pages.isEmpty());
        View main = primaryMainView();
        result.putBoolean("mainMounted", main != null);
        result.putFloat("mainAlpha", main == null ? 0f : main.getAlpha());
        result.putInt("mainWidth", main == null ? 0 : main.getWidth());
        result.putInt("mainHeight", main == null ? 0 : main.getHeight());
        result.putInt("sourceCharacters", source == null ? 0 : source.text.length());
        result.putInt("sourceWords", source == null ? 0 : source.words.size());
        result.putBoolean("secondaryRows", showSecondary);
        result.putInt("secondaryLineCap", secondaryCap);
        int sections = activeSlotCount();
        SectionSlot companionSlot = companionSlot();
        result.putBoolean("duet", duet && sections == 2);
        result.putInt("duetSections", sections);
        result.putInt("duetPrimaryIndex", primaryIndex);
        result.putBoolean("duetDeferred", companionSlot != null && companionSlot.mounted != null
                && companionSlot.mounted.startMs > position());
        result.putString("duetPolicy", duet ? secondaryCap == 1 ? "Two sections, one secondary line each" : "Two sections"
                : companion == null ? "Solo" : "Solo fallback, duet did not fit");
        result.putString("displayPolicy", AutoSurfaceFit.secondaryAllowed(String.valueOf(getTag())) ? "Full with fitting secondary rows" : "Main only");
        result.putLong("frameCount", frames); result.putBoolean("syntheticStress", stress);
        result.putDouble("drawMeanMs", frames == 0 ? 0 : totalNanos / 1_000_000.0 / frames); result.putDouble("drawMaxMs", maxNanos / 1_000_000.0);
        return result;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // A replacement view also starts at 0x0. Only the owner's measured box can rearm a fit.
        pages = new ArrayList<>(); page = -1; planned = false; needsSync = true;
        render(1f / 30f); schedule();
    }
    @Override protected void onMeasure(int width, int height) {
        super.onMeasure(fill(width), fill(height));
    }
    private static int fill(int spec) {
        return MeasureSpec.getMode(spec) == MeasureSpec.AT_MOST ? MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(spec), MeasureSpec.EXACTLY) : spec;
    }
    private boolean immediateEntrance() {
        return sample != null && AutoLyricPresentation.immediateEntrance(sample.getBoolean("hasSyncedLyrics"),
                sample.getLong("firstVocalStartMs", Long.MAX_VALUE), position());
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Motion.animationsEnabled() && !immediateEntrance()) entranceStarted = -1;
        schedule();
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!immediateEntrance() && artworkFadeStarted == 0 && "player-full".equals(getTag()) && Motion.animationsEnabled()) {
            artwork = AutoArtworkTransition.take(this);
            if (artwork != null) { artworkFadeStarted = SystemClock.elapsedRealtime(); artworkFades++; schedule(); }
        }
    }
    @Override protected void onDetachedFromWindow() {
        stop();
        clearAllSlots();
        companion = null; duet = false; duetSlots = new ArrayList<>();
        stage.removeAllViews(); rowHost = null; page = -1; needsSync = false;
        artwork = null;
        super.onDetachedFromWindow();
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        long start = System.nanoTime();
        if ("player".equals(getTag())) AndroidAutoPrototype.playerDrawn();
        if (artwork == null) {
            if (entranceStarted == -1) entranceStarted = SystemClock.elapsedRealtime();
            float alpha = entranceStarted == 0 ? 1f : Math.min(1f,
                    (SystemClock.elapsedRealtime() - entranceStarted) / (float) Math.max(210, Motion.dur(210)));
            int layer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * alpha));
            super.dispatchDraw(canvas); canvas.restoreToCount(layer);
            if (alpha >= 1f) entranceStarted = 0;
        }
        else {
            long elapsed = SystemClock.elapsedRealtime() - artworkFadeStarted;
            int out = Math.max(300, Motion.dur(300)), in = Math.max(210, Motion.dur(210));
            float incoming = Math.max(0f, Math.min(1f, elapsed / (float) in));
            int layer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * incoming));
            super.dispatchDraw(canvas); canvas.restoreToCount(layer);
            artworkPaint.setAlpha(Math.round(255 * Math.max(0f, 1f - elapsed / (float) out)));
            canvas.drawBitmap(artwork, 0, 0, artworkPaint);
            if (elapsed >= Math.max(out, in)) artwork = null;
        }
        long duration = System.nanoTime() - start; frames++; totalNanos += duration; maxNanos = Math.max(maxNanos, duration);
    }
}
