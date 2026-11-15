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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Android Auto sizing and cadence adapter for Spicy's existing mounted-row engine. */
public final class AutoLyricsView extends FrameLayout implements Choreographer.FrameCallback {
    private static final Set<Integer> DUET_INDICES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(0, 1)));

    private final FrameLayout stage;
    private boolean creditOutro;
    private LinearLayout rowHost, outgoing;
    private long fadeStarted, transitions;
    private Bitmap artwork;
    private long artworkFadeStarted, artworkFades;
    private long entranceStarted;
    private final Paint artworkPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final LyricsRowViewFactory factory;
    private final FrameStyleBatcher styles;
    private final LyricsFrameRenderer renderer;
    private final LyricsRenderConfig config;
    private final LyricsDocument document = new LyricsDocument();
    private AppliedLine source, mounted, companion, mountedCompanion;
    private View mountedCompanionRow;
    private long companionEntranceStarted;
    private int fittedWidth, fittedHeight, fittedSettings;
    private Bundle sample, timelineSample;
    private String contentKey = "";
    private List<int[]> pages = new ArrayList<>();
    private int page = -1, fontSp, primaryIndex;
    private float secondaryTextSp = 24f;
    private boolean active, scheduled, stress, planned, showSecondary = true;
    private boolean duet;
    private int secondaryCap = AutoSurfaceFit.secondaryLineCap(false);
    private List<SectionKey> currentSections = new ArrayList<>(), outgoingSections = new ArrayList<>();
    private List<Float> outgoingAlphas = new ArrayList<>();
    private List<Boolean> outgoingHidden = new ArrayList<>(), currentSurvivors = new ArrayList<>();
    private List<AutoDuetLayout.DuetSectionId> duetSlots = new ArrayList<>();
    private boolean interlude;
    private AppliedLine dotTiming;
    private boolean musicNote;
    private Typeface nativeTypeface = Typeface.DEFAULT;
    private String fontFamily = "System fallback";
    private long localBoundarySwitches, lastBoundaryLateMs;
    private long previousFrame, frames, totalNanos, maxNanos;

    public AutoLyricsView(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        stage = new FrameLayout(context);
        addView(stage, new LayoutParams(-1, -1));
        factory = new LyricsRowViewFactory(context, new LyricsTextFactory(context, null));
        styles = new FrameStyleBatcher(context);
        renderer = new LyricsFrameRenderer(context, styles);
        config = LyricsRenderConfig.read(context, null).forAndroidAuto();
        int font = context.getResources().getIdentifier("google_sans", "font", context.getPackageName());
        if (font != 0) try {
            nativeTypeface = Typeface.create(context.getResources().getFont(font), 400, false);
            fontFamily = "Android Auto Google Sans";
        } catch (android.content.res.Resources.NotFoundException ignored) { }
        document.type = "Syllable";
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void update(Bundle value) {
        timelineSample = value;
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
            contentKey = key; source = decoded; companion = decodedCompanion;
            dotTiming = interlude && !musicNote
                    ? com.eza.spicyex.lyrics.LyricTimeline.createAppliedDotRow(decoded.startMs, decoded.endMs, false) : null;
            pages = new ArrayList<>(); page = -1; planned = false;
        }
        active = true;
        if (changed || mounted == null) render(1f / 30f);
        schedule();
    }

    public void stop() {
        active = false;
        Choreographer.getInstance().removeFrameCallback(this);
        scheduled = false; previousFrame = 0;
        finishFade();
        companionEntranceStarted = 0;
        if (mountedCompanionRow != null) mountedCompanionRow.setAlpha(1f);
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
        if (artwork != null || entranceStarted != 0 || fadeStarted != 0 || (interlude && !musicNote)) invalidate();
        if (sample.getBoolean("playing") || fadeStarted != 0 || companionEntranceStarted != 0 || artwork != null || entranceStarted != 0 || renderer.hasPendingAnimation(document, mountedIndices(), rowHost, primaryIndex, 0, 1)) schedule();
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
            finishFade();
            if (mounted != null) LyricsLineViewState.clear(mounted, rowHost, old -> LyricsLineViewState.invalidate(old, styles));
            if (mountedCompanion != null) LyricsLineViewState.clear(mountedCompanion, rowHost, old -> LyricsLineViewState.invalidate(old, styles));
            mounted = null; mountedCompanion = null; mountedCompanionRow = null;
            stage.removeAllViews(); rowHost = null; document.appliedLines.clear(); fontSp = 0; page = -1;
            currentSections = new ArrayList<>(); duet = false;
            return;
        }
        int offset = sourceOffset(source, position());
        int selected = AutoSurfaceFit.pageAt(pages, offset);
        if (selected != page) mountPage(selected, minimum, maximum, width, height);
        updateDeferredCompanion();
        if (!creditOutro && !interlude) renderer.applySynced(document, mountedIndices(), rowHost, config,
                position(), primaryIndex, delta, false);
        else if (interlude && !musicNote && rowHost != null && rowHost.getChildCount() > 0 && rowHost.getChildAt(0) instanceof LinearLayout) {
            LinearLayout dots = (LinearLayout) rowHost.getChildAt(0);
            for (int i = 0; i < dots.getChildCount(); i++) dots.getChildAt(i).setAlpha(
                    Motion.animationsEnabled() ? AutoSurfaceFit.dotAlpha(dotTiming, position(), i) : 1f);
        }
        if (fadeStarted != 0) stepFade();
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

    private void mountPage(int selected, int minimum, int maximum, int width, int height) {
        page = selected;
        int[] range = pages.get(page);
        if (mounted != null) LyricsLineViewState.clear(mounted, null, old -> LyricsLineViewState.invalidate(old, styles));
        if (mountedCompanion != null) LyricsLineViewState.clear(mountedCompanion, null, old -> LyricsLineViewState.invalidate(old, styles));
        mounted = slice(source, range[0], range[1]);
        mountedCompanion = duet && companion != null ? companion : null;
        final AppliedLine partner = mountedCompanion;
        int selectedSize = AutoSurfaceFit.largestSize(minimum, maximum,
                size -> sceneFits(mounted, partner, size, width, height, showSecondary, secondaryCap));
        selectedSize = Math.max(minimum, selectedSize);
        fontSp = partner == null ? selectedSize : retainedDuetSize(mounted, false, selectedSize, width, height);
        int companionSize = partner == null ? selectedSize
                : retainedDuetSize(partner, true, selectedSize, width, height);
        View primaryRow = build(mounted, fontSp, showSecondary, secondaryCap);
        if (!creditOutro && primaryRow instanceof LinearLayout) {
            LinearLayout lyrics = (LinearLayout) primaryRow;
            for (int i = 1; i < lyrics.getChildCount(); i++) if (lyrics.getChildAt(i) instanceof TextView)
                secondaryTextSp = ((TextView) lyrics.getChildAt(i)).getTextSize()
                        / getResources().getDisplayMetrics().scaledDensity;
        }
        View companionRow = mountedCompanion == null ? null
                : build(mountedCompanion, companionSize, showSecondary, secondaryCap);
        AutoDuetLayout.DuetSectionId primaryId = null, companionId = null;
        boolean companionFirst = false;
        if (mountedCompanion != null) {
            int generation = sample.getInt("generation");
            Bundle duetBundle = sample.getBundle("duet");
            primaryId = sectionId(generation, sample, sample.getInt("lineIndex", -1));
            companionId = sectionId(generation, duetBundle, duetBundle == null ? -1 : duetBundle.getInt("lineIndex", -1));
            if (primaryId != null && companionId != null) {
                List<AutoDuetLayout.DuetSectionId> order = AutoDuetLayout.assignDuetSlots(
                        Arrays.asList(primaryId, companionId), duetSlots);
                duetSlots = new ArrayList<>(order);
                companionFirst = !primaryId.equals(order.get(0));
            }
        }

        List<Float> previousAlphas = captureAlphas(rowHost);
        List<SectionKey> previousSections = currentSections;
        Set<SectionKey> previouslyHidden = new HashSet<>();
        if (rowHost != null) for (int i = 0; i < rowHost.getChildCount() && i < previousSections.size(); i++)
            if (rowHost.getChildAt(i).getVisibility() != View.VISIBLE) previouslyHidden.add(previousSections.get(i));
        finishFade();
        restoreAlphas(rowHost, previousAlphas);
        outgoing = rowHost;
        outgoingSections = previousSections;
        outgoingAlphas = previousAlphas;
        outgoingHidden = new ArrayList<>(Collections.nCopies(
                outgoing == null ? 0 : outgoing.getChildCount(), false));

        rowHost = new LinearLayout(getContext());
        rowHost.setOrientation(LinearLayout.VERTICAL);
        List<View> rows = new ArrayList<>();
        List<SectionKey> sections = new ArrayList<>();
        document.appliedLines.clear();
        primaryIndex = 0;
        if (companionFirst) {
            addSection(rowHost, companionRow, rows, sections, companionId, mountedCompanion, companionSize, false);
            addSection(rowHost, primaryRow, rows, sections, primaryId, mounted, fontSp, true);
            primaryIndex = 1;
            document.appliedLines.add(mountedCompanion);
            document.appliedLines.add(mounted);
        } else {
            addSection(rowHost, primaryRow, rows, sections, primaryId, mounted, fontSp, false);
            document.appliedLines.add(mounted);
            if (companionRow != null) {
                addSection(rowHost, companionRow, rows, sections, companionId, mountedCompanion, companionSize, true);
                document.appliedLines.add(mountedCompanion);
            }
        }
        mountedCompanionRow = companionRow;
        companionEntranceStarted = 0;
        fittedWidth = width; fittedHeight = height; fittedSettings = AutoDisplaySettings.signature(sample);
        stage.addView(rowHost, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        styles.clearPendingWrites();
        currentSections = sections;
        currentSurvivors = survivors(previousSections, sections);
        for (int i = 0; i < sections.size(); i++)
            if (previouslyHidden.contains(sections.get(i))) currentSurvivors.set(i, false);
        if (outgoing != null && Motion.animationsEnabled() && !immediateEntrance()) {
            fadeStarted = SystemClock.elapsedRealtime(); transitions++;
            for (int i = 0; i < rows.size(); i++)
                if (!currentSurvivors.get(i)) setMainAlpha(rows.get(i), 0f);
            hideSecondary(outgoing);
            hideContinuingOutgoing(sections, currentSurvivors);
        } else finishFade();
        // Compose can mount during layout after the first child measurement. Lay out the
        // owned stage now so a paused first row does not wait for another content change.
        stage.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        stage.layout(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + width, getPaddingTop() + height);
        setContentDescription(mountedCompanion == null ? mounted.text
                : mounted.text + "\n" + mountedCompanion.text);
    }

    private static void addSection(LinearLayout host, View row, List<View> rows, List<SectionKey> sections,
                                   AutoDuetLayout.DuetSectionId id, AppliedLine line, int size, boolean gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        if (gap) params.topMargin = Math.round(4 * host.getResources().getDisplayMetrics().density);
        host.addView(row, params);
        rows.add(row);
        sections.add(new SectionKey(id, line == null ? null : contentSignature(line), size));
    }

    private static AutoDuetLayout.DuetSectionId sectionId(int generation, Bundle row, int ordinal) {
        if (row == null || ordinal < 0) return null;
        return new AutoDuetLayout.DuetSectionId(generation, row.getLong("lineStartMs"),
                row.getLong("lineEndMs"), ordinal);
    }

    private static List<Boolean> survivors(List<SectionKey> previous, List<SectionKey> current) {
        List<Boolean> result = new ArrayList<>(current.size());
        boolean eligible = previous.size() > 1 || current.size() > 1;
        for (SectionKey key : current) result.add(eligible && key != null && previous.contains(key));
        return result;
    }

    private void hideContinuingOutgoing(List<SectionKey> sections, List<Boolean> survivors) {
        if (outgoing == null) return;
        Set<SectionKey> surviving = new HashSet<>();
        for (int i = 0; i < sections.size(); i++)
            if (i < survivors.size() && survivors.get(i)) surviving.add(sections.get(i));
        for (int i = 0; i < outgoing.getChildCount(); i++) {
            SectionKey key = i < outgoingSections.size() ? outgoingSections.get(i) : null;
            if (key == null || !surviving.contains(key)) continue;
            outgoing.getChildAt(i).setVisibility(View.INVISIBLE);
            if (i < outgoingHidden.size()) outgoingHidden.set(i, true);
        }
    }

    private void updateDeferredCompanion() {
        if (mountedCompanionRow == null || mountedCompanion == null) return;
        boolean deferred = mountedCompanion.startMs > position();
        if (deferred) {
            mountedCompanionRow.setVisibility(View.INVISIBLE);
            mountedCompanionRow.setAlpha(1f);
            companionEntranceStarted = 0;
            return;
        }
        if (mountedCompanionRow.getVisibility() != View.VISIBLE && Motion.animationsEnabled())
            companionEntranceStarted = SystemClock.elapsedRealtime();
        mountedCompanionRow.setVisibility(View.VISIBLE);
        float alpha = companionEntranceStarted == 0 || !Motion.animationsEnabled() ? 1f
                : Math.min(1f, (SystemClock.elapsedRealtime() - companionEntranceStarted)
                        / (float) Math.max(210, Motion.dur(210)));
        mountedCompanionRow.setAlpha(alpha);
        if (alpha >= 1f) companionEntranceStarted = 0;
    }

    private Set<Integer> mountedIndices() {
        return document.appliedLines.size() > 1 ? DUET_INDICES : Collections.singleton(0);
    }

    private static View mainOf(View row) {
        return row instanceof LinearLayout && ((LinearLayout) row).getOrientation() == LinearLayout.VERTICAL
                && ((ViewGroup) row).getChildCount() > 0
                ? ((ViewGroup) row).getChildAt(0) : row;
    }

    private static View mainContent(LinearLayout host) {
        return host == null || host.getChildCount() == 0 ? null : mainOf(host.getChildAt(0));
    }

    private static void setMainAlpha(View row, float alpha) {
        View main = mainOf(row);
        if (main != null) main.setAlpha(alpha);
    }

    private static List<Float> captureAlphas(LinearLayout host) {
        List<Float> alphas = new ArrayList<>();
        if (host != null) for (int i = 0; i < host.getChildCount(); i++) {
            View main = mainOf(host.getChildAt(i));
            alphas.add(main == null ? 1f : main.getAlpha());
        }
        return alphas;
    }

    private static void restoreAlphas(LinearLayout host, List<Float> alphas) {
        if (host == null) return;
        for (int i = 0; i < host.getChildCount() && i < alphas.size(); i++)
            setMainAlpha(host.getChildAt(i), alphas.get(i));
    }

    private static void hideSecondary(LinearLayout host) {
        if (host == null) return;
        for (int c = 0; c < host.getChildCount(); c++) {
            View row = host.getChildAt(c);
            if (row instanceof LinearLayout && ((LinearLayout) row).getOrientation() == LinearLayout.VERTICAL)
                for (int i = 1; i < ((ViewGroup) row).getChildCount(); i++)
                    ((ViewGroup) row).getChildAt(i).setVisibility(View.INVISIBLE);
        }
    }

    private void finishFade() {
        if (outgoing != null) { stage.removeView(outgoing); outgoing = null; }
        if (rowHost != null) for (int i = 0; i < rowHost.getChildCount(); i++) setMainAlpha(rowHost.getChildAt(i), 1f);
        fadeStarted = 0;
        outgoingSections = new ArrayList<>();
        outgoingAlphas = new ArrayList<>();
        outgoingHidden = new ArrayList<>();
        currentSurvivors = new ArrayList<>();
    }

    private void stepFade() {
        long elapsed = SystemClock.elapsedRealtime() - fadeStarted;
        int inDuration = Math.max(210, Motion.dur(210)), outDuration = Math.max(130, Motion.dur(130));
        float in = Math.min(1f, elapsed / (float) inDuration);
        float out = Math.max(0f, 1f - elapsed / (float) outDuration);
        if (rowHost != null) for (int i = 0; i < rowHost.getChildCount(); i++)
            if (i >= currentSurvivors.size() || !currentSurvivors.get(i))
                setMainAlpha(rowHost.getChildAt(i), in);
        if (outgoing != null) for (int i = 0; i < outgoing.getChildCount(); i++)
            if (i >= outgoingHidden.size() || !outgoingHidden.get(i))
                setMainAlpha(outgoing.getChildAt(i),
                        (i < outgoingAlphas.size() ? outgoingAlphas.get(i) : 1f) * out);
        if (elapsed >= inDuration) finishFade();
    }

    private static final class FitAttempt {
        final boolean duet, secondary;
        FitAttempt(boolean duet, boolean secondary) { this.duet = duet; this.secondary = secondary; }
    }

    private static final class SectionKey {
        final AutoDuetLayout.DuetSectionId id;
        final String signature;
        final int size;

        SectionKey(AutoDuetLayout.DuetSectionId id, String signature, int size) {
            this.id = id; this.signature = signature; this.size = size;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SectionKey)) return false;
            SectionKey key = (SectionKey) other;
            return size == key.size && java.util.Objects.equals(id, key.id)
                    && java.util.Objects.equals(signature, key.signature);
        }

        @Override public int hashCode() {
            return 31 * (31 * java.util.Objects.hashCode(id) + java.util.Objects.hashCode(signature)) + size;
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

    /** A continuing duet section retains its size while a newcomer fits the remaining space. */
    private int retainedDuetSize(AppliedLine line, boolean partner, int fallback, int width, int height) {
        if (currentSections.size() != 2 || width != fittedWidth || height != fittedHeight
                || fittedSettings != AutoDisplaySettings.signature(sample)) return fallback;
        Bundle row = partner ? sample.getBundle("duet") : sample;
        AutoDuetLayout.DuetSectionId id = sectionId(sample.getInt("generation"), row,
                row == null ? -1 : row.getInt("lineIndex", -1));
        String signature = contentSignature(line);
        for (SectionKey previous : currentSections)
            if (id != null && id.equals(previous.id) && signature.equals(previous.signature)) return previous.size;
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
        View companion = build(other, retainedDuetSize(partner, true, size, width, height), secondary, cap);
        used += Math.round(4 * getResources().getDisplayMetrics().density) + measure(companion, width);
        fits = mainFits(mainOf(companion), width);
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
        View content = mainContent(rowHost);
        return active && creditOutro && getAlpha() > 0f && content != null && content.getAlpha() > 0f
                && content.getWidth() > 0 && content.getHeight() > 0
                && sample != null && AutoPrototypePolicy.sameSession(generation, trackUri,
                        sample.getInt("generation"), sample.getString("trackUri", ""));
    }

    public Bundle metrics() {
        Bundle result = new Bundle(); result.putString("renderer", "Spicy mounted row");
        result.putLong("transitionCount", transitions); result.putInt("mountedHosts", stage.getChildCount());
        result.putString("transition", "Fade 130ms out / 210ms in"); result.putBoolean("transitionActive", fadeStarted != 0);
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
        result.putBoolean("lineSynced", source != null && source.syntheticWords);
        result.putBoolean("noFit", planned && pages.isEmpty());
        View main = mainContent(rowHost);
        result.putBoolean("mainMounted", main != null);
        result.putFloat("mainAlpha", main == null ? 0f : main.getAlpha());
        result.putInt("mainWidth", main == null ? 0 : main.getWidth());
        result.putInt("mainHeight", main == null ? 0 : main.getHeight());
        result.putInt("sourceCharacters", source == null ? 0 : source.text.length());
        result.putInt("sourceWords", source == null ? 0 : source.words.size());
        result.putBoolean("secondaryRows", showSecondary);
        result.putInt("secondaryLineCap", secondaryCap);
        result.putBoolean("duet", duet && mountedCompanion != null);
        result.putInt("duetSections", document.appliedLines.size());
        result.putInt("duetPrimaryIndex", primaryIndex);
        result.putBoolean("duetDeferred", mountedCompanion != null && mountedCompanion.startMs > position());
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
        pages = new ArrayList<>(); page = -1; planned = false;
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
        if (mounted != null) LyricsLineViewState.clear(mounted, rowHost, old -> LyricsLineViewState.invalidate(old, styles));
        if (mountedCompanion != null) LyricsLineViewState.clear(mountedCompanion, rowHost, old -> LyricsLineViewState.invalidate(old, styles));
        mounted = null; mountedCompanion = null; mountedCompanionRow = null;
        companion = null; duet = false; currentSections = new ArrayList<>();
        stage.removeAllViews(); rowHost = null; page = -1; document.appliedLines.clear();
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
