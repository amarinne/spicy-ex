package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.View.MeasureSpec;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.eza.spicyex.SpotifyPlusConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * In-player live-lyric renderer.
 *
 * This intentionally mounts one normal fullscreen lyric row and drives it through
 * {@link LyricsFrameRenderer}. The now-playing card has different sizing and host layout, but the
 * text/word animation path should stay the same as fullscreen instead of carrying a second renderer.
 */
public final class LiveLyricCardView extends LinearLayout {
    private static final Set<Integer> ACTIVE_ROW = Collections.singleton(0);

    private final OverflowViewport stage;
    private final FrameStyleBatcher styleBatcher;
    private final LyricsFrameRenderer frameRenderer;
    private final LyricsDocument oneRowDocument = new LyricsDocument();

    private LinearLayout rowHost;
    private AppliedLine mountedSourceLine;
    private AppliedLine mountedLine;
    private String mountedConfigKey = "";
    private String mountedOverflowMode = "Wrap";

    public LiveLyricCardView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(64));
        setClipToPadding(false);
        setClipChildren(false);

        styleBatcher = new FrameStyleBatcher(context);
        frameRenderer = new LyricsFrameRenderer(context, styleBatcher);

        stage = new OverflowViewport(context);
        stage.setClipToPadding(false);
        stage.setClipChildren(false);
        rowHost = newRowHost(context);
        stage.addView(rowHost, rowHostLayoutParams(mountedOverflowMode));
        addView(stage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void applyConfig(LyricsRenderConfig config) {
        String key = configKey(config);
        if (!key.equals(mountedConfigKey)) {
            mountedLine = null;
            mountedConfigKey = key;
        }
        requestLayout();
        invalidateForFrame();
    }

    public void renderLine(Activity activity, AppliedLine line, LyricsRenderConfig config,
                           long positionMs, float deltaSeconds,
                           LyricsDocument document,
                           LyricsRowViewFactory.RomanizedWordProvider romanizedWordProvider,
                           boolean animateMount) {
        if (activity == null || line == null || config == null) {
            clear();
            return;
        }
        LyricsRenderConfig liveConfig = config.forLiveCard();
        LyricsSurfaceRowPlanner.RowPlan rowPlan = LyricsSurfaceRowPlanner.plan(
                line, document, LyricsSurfaceRowPlanner.SurfacePolicy.liveCard(liveConfig), romanizedWordProvider);
        String key = configKey(liveConfig)
                + "|" + rowPlan.options.showJapaneseFurigana
                + "|" + rowPlan.options.showJapaneseRomaji
                + "|" + rowPlan.options.attachTransliterationToWords
                + "|" + rowPlan.options.documentText
                + "|" + (rowPlan.line != null && rowPlan.line.oppositeAligned);
        if (mountedSourceLine != line || !key.equals(mountedConfigKey)) {
            mountLine(activity, line, rowPlan, liveConfig, animateMount);
            mountedConfigKey = key;
        }
        AppliedLine renderLine = mountedLine == null ? rowPlan.line : mountedLine;
        if (renderLine == null) {
            clear();
            return;
        }
        oneRowDocument.appliedLines.clear();
        oneRowDocument.appliedLines.add(renderLine);
        frameRenderer.applySynced(
                oneRowDocument,
                ACTIVE_ROW,
                rowHost,
                liveConfig,
                positionMs,
                0,
                deltaSeconds,
                false);
        updateOverflowScroll(renderLine, liveConfig, positionMs);
        invalidateForFrame();
    }

    public void setInterlude(boolean note) {
        AppliedLine line = new AppliedLine();
        line.dotLine = true;
        line.text = note ? "♪" : "• • •";
        line.startMs = 0;
        line.endMs = 3000;
        mountSynthetic(line, note);
    }

    public void clear() {
        rowHost.removeAllViews();
        stage.removeAllViews();
        styleBatcher.clearPendingWrites();
        rowHost = newRowHost(getContext());
        stage.addView(rowHost, rowHostLayoutParams(mountedOverflowMode));
        mountedSourceLine = null;
        mountedLine = null;
        mountedConfigKey = "";
        oneRowDocument.appliedLines.clear();
        invalidateForFrame();
    }

    private void mountLine(Activity activity, AppliedLine sourceLine, LyricsSurfaceRowPlanner.RowPlan rowPlan,
                           LyricsRenderConfig config, boolean animateMount) {
        if (rowPlan == null || rowPlan.line == null) return;
        mountedOverflowMode = config.liveCardOverflowMode;
        LinearLayout nextHost = replaceRowHost(animateMount, config.liveCardTransitionMode);
        clearLineState(rowPlan.line);
        LyricsTextFactory textFactory = new LyricsTextFactory(activity, SpotifyPlusConfig.from(activity));
        LyricsRowViewFactory factory = new LyricsRowViewFactory(activity, textFactory);
        View row = factory.build(rowPlan.line, rowPlan.options, rowPlan.romanizedWordProvider, null);
        installLineOverflowViewports(row, rowPlan.line, rowPlan.options.wrapLongLines);
        nextHost.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        if (animateMount) animateIn(nextHost, config.liveCardTransitionMode);
        mountedSourceLine = sourceLine;
        mountedLine = rowPlan.line;
    }

    private void mountSynthetic(AppliedLine line, boolean note) {
        Activity activity = getContext() instanceof Activity ? (Activity) getContext() : null;
        if (activity == null) {
            clear();
            return;
        }
        LyricsRenderConfig config = LyricsRenderConfig.read(getContext(), null);
        config = config == null ? LyricsRenderConfig.read(getContext(), null) : config;
        line.endMs = Math.max(line.startMs + 1, line.endMs);
        config = config.forLiveCard();
        mountedOverflowMode = config.liveCardOverflowMode;
        LinearLayout nextHost = replaceRowHost(false, config.liveCardTransitionMode);
        clearLineState(line);
        LyricsSurfaceRowPlanner.RowPlan rowPlan = LyricsSurfaceRowPlanner.plan(
                line, null, LyricsSurfaceRowPlanner.SurfacePolicy.liveCard(config), null);
        rowPlan.options.interludeNoteIcon = note;
        LyricsTextFactory textFactory = new LyricsTextFactory(activity, SpotifyPlusConfig.from(activity));
        LyricsRowViewFactory factory = new LyricsRowViewFactory(activity, textFactory);
        View row = factory.build(rowPlan.line, rowPlan.options, rowPlan.romanizedWordProvider, null);
        installLineOverflowViewports(row, rowPlan.line, rowPlan.options.wrapLongLines);
        nextHost.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        mountedSourceLine = null;
        mountedLine = rowPlan.line;
        oneRowDocument.appliedLines.clear();
        oneRowDocument.appliedLines.add(rowPlan.line);
        frameRenderer.applySynced(oneRowDocument, ACTIVE_ROW, rowHost, config, 0, 0, 1f / 60f, false);
        updateOverflowScroll(rowPlan.line, config, 0);
        animateIn(nextHost, config.liveCardTransitionMode);
    }

    private LinearLayout replaceRowHost(boolean animateExit, String transitionMode) {
        LinearLayout oldHost = rowHost;
        LinearLayout nextHost = newRowHost(getContext());
        rowHost = nextHost;
        if (animateExit && !"None".equals(transitionMode)
                && oldHost != null && oldHost.getChildCount() > 0 && oldHost.getParent() == stage) {
            oldHost.animate().cancel();
            oldHost.setTranslationY(0f);
            oldHost.setAlpha(1f);
            android.view.ViewPropertyAnimator animator = oldHost.animate()
                    .alpha(0f)
                    .setDuration(130)
                    .withEndAction(() -> {
                        styleBatcher.invalidateRecursive(oldHost);
                        stage.removeView(oldHost);
                    });
            if ("Fade up".equals(transitionMode)) animator.translationY(-dp(14));
            animator.start();
            stage.addView(nextHost, rowHostLayoutParams(mountedOverflowMode));
            return nextHost;
        }
        stage.removeAllViews();
        if (oldHost != null) styleBatcher.invalidateRecursive(oldHost);
        stage.addView(nextHost, rowHostLayoutParams(mountedOverflowMode));
        return nextHost;
    }

    private LinearLayout newRowHost(Context context) {
        LinearLayout host = new LinearLayout(context);
        host.setOrientation(VERTICAL);
        host.setGravity(Gravity.CENTER_VERTICAL);
        host.setClipToPadding(false);
        host.setClipChildren(false);
        return host;
    }

    private void animateIn(View host, String transitionMode) {
        if (host == null) return;
        if ("None".equals(transitionMode)) {
            host.animate().cancel();
            host.setTranslationY(0f);
            host.setAlpha(1f);
            return;
        }
        host.animate().cancel();
        host.setTranslationY("Fade up".equals(transitionMode) ? dp(14) : 0f);
        host.setAlpha(0f);
        host.animate().translationY(0f).alpha(1f).setDuration(210).start();
    }

    private void clearLineState(AppliedLine line) {
        if (line == null) return;
        LyricsLineViewState.clearMainView(line);
        LyricsLineViewState.setRowView(line, null);
        LyricsLineViewState.setRomanView(line, null);
        LyricsLineViewState.setTranslationView(line, null);
        if (line.words == null) return;
        for (SyllableSegment seg : line.words) {
            LyricsSyllableViewState.clear(seg);
        }
    }

    private FrameLayout.LayoutParams rowHostLayoutParams(String overflowMode) {
        return new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
    }

    private void updateOverflowScroll(AppliedLine line, LyricsRenderConfig config, long positionMs) {
        if (line == null || config == null || rowHost == null || stage == null) return;
        mountedOverflowMode = config.liveCardOverflowMode;
        boolean wrap = "Wrap".equals(config.liveCardOverflowMode);
        stage.setWrapMode(wrap);
        stage.setClipToPadding(!wrap);
        stage.setClipChildren(!wrap);
        ViewGroup.LayoutParams lp = rowHost.getLayoutParams();
        if (lp != null && lp.width != LayoutParams.MATCH_PARENT) {
            lp.width = LayoutParams.MATCH_PARENT;
            rowHost.setLayoutParams(lp);
        }
        rowHost.setTranslationX(0f);
        updateLineOverflowViewports(line, config, positionMs, rowHost.getAlpha() < 0.98f);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void installLineOverflowViewports(View row, AppliedLine line, boolean wrapLongLines) {
        if (wrapLongLines || line == null || line.dotLine || !(row instanceof LinearLayout)) return;
        LinearLayout group = (LinearLayout) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof LineOverflowViewport) continue;
            ViewGroup.LayoutParams rawLp = child.getLayoutParams();
            LinearLayout.LayoutParams oldLp = rawLp instanceof LinearLayout.LayoutParams
                    ? (LinearLayout.LayoutParams) rawLp
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            group.removeViewAt(i);
            LineOverflowViewport viewport = new LineOverflowViewport(getContext(), line.oppositeAligned);
            LinearLayout.LayoutParams viewportLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            viewportLp.topMargin = oldLp.topMargin;
            viewportLp.bottomMargin = oldLp.bottomMargin;
            viewportLp.leftMargin = oldLp.leftMargin;
            viewportLp.rightMargin = oldLp.rightMargin;
            FrameLayout.LayoutParams childLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    line.oppositeAligned ? Gravity.END : Gravity.START);
            viewport.addView(child, childLp);
            group.addView(viewport, i, viewportLp);
        }
    }

    private void updateLineOverflowViewports(AppliedLine line, LyricsRenderConfig config,
                                             long positionMs, boolean transitionActive) {
        List<LineOverflowViewport> viewports = new ArrayList<>();
        collectLineOverflowViewports(rowHost, viewports);
        if (viewports.isEmpty()) return;
        boolean grouped = config != null && "Grouped".equals(config.liveCardScrollScope);
        float groupTarget = grouped ? groupedScrollTarget(viewports, line, config, positionMs, transitionActive) : 0f;
        for (LineOverflowViewport viewport : viewports) {
            viewport.update(line, config, positionMs, transitionActive, grouped, groupTarget);
        }
    }

    private void collectLineOverflowViewports(View view, List<LineOverflowViewport> out) {
        if (view == null || out == null) return;
        if (view instanceof LineOverflowViewport) out.add((LineOverflowViewport) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectLineOverflowViewports(group.getChildAt(i), out);
        }
    }

    private float groupedScrollTarget(List<LineOverflowViewport> viewports, AppliedLine line,
                                      LyricsRenderConfig config, long positionMs, boolean transitionActive) {
        if (!canScroll(line, config, transitionActive)) return 0f;
        float maxScroll = 0f;
        for (LineOverflowViewport viewport : viewports) {
            if (viewport != null) maxScroll = Math.max(maxScroll, viewport.maxScrollPx());
        }
        if (maxScroll <= 0f) return 0f;
        return -maxScroll * scrollProgressAfterTransition(line, config, positionMs);
    }

    private String configKey(LyricsRenderConfig config) {
        if (config == null) return "";
        return config.liveCardTextSizeMode
                + "|" + config.liveCardWeight
                + "|" + config.lyricsFont
                + "|" + config.liveCardSecondaryMode
                + "|" + config.liveCardShowTransliteration
                + "|" + config.liveCardShowTranslation
                + "|" + config.liveCardMinimalAnimation
                + "|" + config.liveCardAnimationMode
                + "|" + config.liveCardGlowMode
                + "|" + config.liveCardLineSyncFillMode
                + "|" + config.liveCardTransitionMode
                + "|" + config.liveCardOverflowMode
                + "|" + config.liveCardScrollScope
                + "|" + config.spotlight
                + "|" + config.lineSyncFillMode
                + "|" + config.glowBlurEnabled
                + "|" + config.interludeNoteIcon
                + "|" + config.translationBright;
    }

    private void invalidateForFrame() {
        if (android.os.Build.VERSION.SDK_INT >= 16) postInvalidateOnAnimation();
        else invalidate();
    }

    private static final class OverflowViewport extends FrameLayout {
        private boolean wrapMode = true;

        OverflowViewport(Context context) {
            super(context);
        }

        void setWrapMode(boolean wrapMode) {
            if (this.wrapMode == wrapMode) return;
            this.wrapMode = wrapMode;
            requestLayout();
        }
    }

    private final class LineOverflowViewport extends FrameLayout {
        private final boolean oppositeAligned;

        LineOverflowViewport(Context context, boolean oppositeAligned) {
            super(context);
            this.oppositeAligned = oppositeAligned;
            setClipToPadding(true);
            setClipChildren(true);
        }

        void update(AppliedLine line, LyricsRenderConfig config, long positionMs, boolean transitionActive,
                    boolean grouped, float groupTarget) {
            View child = getChildCount() == 0 ? null : getChildAt(0);
            if (child == null) return;
            float maxScroll = maxScrollPx();
            if (!canScroll(line, config, transitionActive) || maxScroll <= 0f) {
                child.setTranslationX(0f);
                return;
            }
            float target = grouped
                    ? clamp(groupTarget, -maxScroll, 0f)
                    : -maxScroll * scrollProgressAfterTransition(line, config, positionMs);
            child.setTranslationX(target);
        }

        float maxScrollPx() {
            View child = getChildCount() == 0 ? null : getChildAt(0);
            if (child == null) return 0f;
            int viewportWidth = getWidth() - getPaddingLeft() - getPaddingRight();
            int contentWidth = child.getMeasuredWidth();
            if (viewportWidth <= 0 || contentWidth <= 0 || contentWidth <= viewportWidth + dp(2)) return 0f;
            return Math.max(0f, contentWidth - viewportWidth + dp(8));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            View child = getChildCount() == 0 ? null : getChildAt(0);
            if (child == null || child.getVisibility() == GONE) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            child.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    getChildMeasureSpec(heightMeasureSpec, 0, child.getLayoutParams().height));
            int requestedWidth = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? child.getMeasuredWidth()
                    : MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(resolveSize(requestedWidth, widthMeasureSpec),
                    resolveSize(child.getMeasuredHeight(), heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            View child = getChildCount() == 0 ? null : getChildAt(0);
            if (child == null || child.getVisibility() == GONE) return;
            int width = right - left;
            int childWidth = child.getMeasuredWidth();
            int childLeft = oppositeAligned ? Math.max(0, width - childWidth) : 0;
            child.layout(childLeft, 0, childLeft + childWidth, child.getMeasuredHeight());
        }
    }

    private long lineDurationMs(AppliedLine line) {
        return Math.max(1L, LyricTimeline.fillEndMs(line) - (line == null ? 0L : line.startMs));
    }

    private long transitionScrollReserveMs(LyricsRenderConfig config) {
        return config != null && "None".equals(config.liveCardTransitionMode) ? 0L : 210L;
    }

    private boolean canScroll(AppliedLine line, LyricsRenderConfig config, boolean transitionActive) {
        boolean scroll = config != null && "Scroll with lyric".equals(config.liveCardOverflowMode);
        return scroll
                && line != null
                && !line.dotLine
                && !transitionActive
                && lineDurationMs(line) > transitionScrollReserveMs(config) + 220L;
    }

    private float scrollProgressAfterTransition(AppliedLine line, LyricsRenderConfig config, long positionMs) {
        long start = line == null ? 0L : line.startMs;
        long end = line == null ? start + 1L : Math.max(start + 1L, LyricTimeline.fillEndMs(line));
        long reserve = transitionScrollReserveMs(config);
        if (end - start <= reserve + 220L) return 0f;
        long scrollStart = start + reserve;
        return clamp((positionMs - scrollStart) / (float) Math.max(1L, end - scrollStart), 0f, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
