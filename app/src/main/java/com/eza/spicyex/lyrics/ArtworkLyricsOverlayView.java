package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.R;
import com.eza.spicyex.SpotifyPlusConfig;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Bounded display-only lyric overlay mounted above Spotify's unchanged album cover. */
public final class ArtworkLyricsOverlayView extends FrameLayout {
    private static final int TIMED_CANDIDATE_RADIUS = 6;
    private static final int STATIC_CANDIDATE_LIMIT = 12;
    private final LinearLayout rowsHost;
    private final FrameStyleBatcher styleBatcher;
    private final LyricsFrameRenderer frameRenderer;
    private final Set<Integer> mountedIndices = new LinkedHashSet<>();

    private LyricsDocument document;
    private LyricsRenderConfig renderConfig;
    private int[] rowHeights;
    private int measuredAnchor = Integer.MIN_VALUE;
    private int mountedStart = -1;
    private int mountedEnd = -1;
    private int mountedAnchor = -1;

    public ArtworkLyricsOverlayView(Context context) {
        super(context);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClipChildren(true);
        setClipToPadding(true);

        View scrim = new View(context);
        GradientDrawable scrimBackground = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xA6000000, 0xBF000000, 0xD9000000});
        scrim.setBackground(scrimBackground);
        scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        rowsHost = new LinearLayout(context);
        rowsHost.setOrientation(LinearLayout.VERTICAL);
        rowsHost.setGravity(Gravity.TOP);
        rowsHost.setClipChildren(false);
        rowsHost.setClipToPadding(false);
        LayoutParams rowsLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        int horizontal = dp(14);
        rowsHost.setPadding(horizontal, dp(54), horizontal, dp(12));
        addView(rowsHost, rowsLp);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(8), dp(6), dp(8), 0);
        LayoutParams actionsLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP);
        addView(actions, actionsLp);

        TextView expand = actionButton(context, "↗", context.getString(R.string.artwork_lyrics_expand));
        TextView close = actionButton(context, "×", context.getString(R.string.artwork_lyrics_close));
        actions.addView(expand, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        closeLp.leftMargin = dp(4);
        actions.addView(close, closeLp);
        expand.setTag(ActionTag.EXPAND);
        close.setTag(ActionTag.CLOSE);

        styleBatcher = new FrameStyleBatcher(context);
        frameRenderer = new LyricsFrameRenderer(context, styleBatcher);
    }

    public void setActions(Runnable onClose, Runnable onExpand) {
        View close = findViewWithTag(ActionTag.CLOSE);
        View expand = findViewWithTag(ActionTag.EXPAND);
        if (close != null) close.setOnClickListener(v -> { if (onClose != null) onClose.run(); });
        if (expand != null) expand.setOnClickListener(v -> { if (onExpand != null) onExpand.run(); });
    }

    public void showOverlay() {
        if (getVisibility() == VISIBLE) return;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        setVisibility(VISIBLE);
        invalidateMeasurements();
    }

    public void hideOverlay() {
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        clearMountedRows();
    }

    public boolean isOverlayVisible() {
        return getVisibility() == VISIBLE;
    }

    public void setDocument(LyricsDocument nextDocument, LyricsRenderConfig nextConfig) {
        document = nextDocument;
        renderConfig = nextConfig;
        invalidateMeasurements();
        if (isOverlayVisible()) post(this::requestLayout);
    }

    public void applyConfig(LyricsRenderConfig nextConfig) {
        renderConfig = nextConfig;
        invalidateMeasurements();
    }

    public void clearDocument() {
        document = null;
        invalidateMeasurements();
    }

    public void renderFrame(long positionMs, float deltaSeconds) {
        if (!isOverlayVisible() || document == null || renderConfig == null
                || document.appliedLines == null || document.appliedLines.isEmpty()
                || getWidth() <= 0 || getHeight() <= 0) return;
        int available = Math.max(1, getHeight()
                - rowsHost.getPaddingTop() - rowsHost.getPaddingBottom());
        ensureMeasurements(positionMs, available);
        if (rowHeights == null) return;
        ArtworkLyricWindowPlanner.Window window = ArtworkLyricWindowPlanner.select(
                document, positionMs, available, rowHeights);
        if (window.isEmpty()) {
            clearMountedRows();
            return;
        }
        if (window.startInclusive != mountedStart || window.endExclusive != mountedEnd
                || window.anchorIndex != mountedAnchor) {
            mountWindow(window);
        }
        if (mountedIndices.isEmpty()) return;
        if ("Static".equalsIgnoreCase(document.type)) {
            frameRenderer.applyStatic(document, mountedIndices, rowsHost);
        } else {
            frameRenderer.applySynced(document, mountedIndices, rowsHost, renderConfig,
                    positionMs, window.anchorIndex, deltaSeconds, false,
                    window.startInclusive, window.endExclusive - 1);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) invalidateMeasurements();
    }

    private void ensureMeasurements(long positionMs, int availableHeight) {
        if (document == null) return;
        boolean staticDocument = "Static".equalsIgnoreCase(document.type);
        int anchor = staticDocument ? 0
                : LyricTimeline.findPrimaryActiveRow(document.appliedLines, positionMs);
        if (anchor < 0) return;
        if (rowHeights != null && measuredAnchor == anchor) return;
        Activity activity = getContext() instanceof Activity ? (Activity) getContext() : null;
        if (activity == null) return;
        int width = Math.max(1, getWidth() - rowsHost.getPaddingLeft() - rowsHost.getPaddingRight());
        rowHeights = new int[document.appliedLines.size()];
        Arrays.fill(rowHeights, availableHeight + 1);
        int start = staticDocument ? 0 : Math.max(0, anchor - TIMED_CANDIDATE_RADIUS);
        int end = staticDocument
                ? Math.min(document.appliedLines.size(), STATIC_CANDIDATE_LIMIT)
                : Math.min(document.appliedLines.size(), anchor + TIMED_CANDIDATE_RADIUS + 1);
        LyricsTextFactory textFactory = new LyricsTextFactory(activity, SpotifyPlusConfig.from(activity));
        LyricsRowViewFactory factory = new LyricsRowViewFactory(activity, textFactory);
        LyricsSurfaceRowPlanner.SurfacePolicy policy = LyricsSurfaceRowPlanner.SurfacePolicy.artwork(renderConfig);
        for (int i = start; i < end; i++) {
            AppliedLine line = document.appliedLines.get(i);
            LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, document, policy);
            View row = factory.build(plan.line, plan.options, null);
            row.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            rowHeights[i] = Math.max(1, row.getMeasuredHeight());
            clearLineViewState(plan.line);
        }
        measuredAnchor = anchor;
    }

    private void mountWindow(ArtworkLyricWindowPlanner.Window window) {
        clearMountedRows();
        Activity activity = getContext() instanceof Activity ? (Activity) getContext() : null;
        if (activity == null) return;
        LyricsTextFactory textFactory = new LyricsTextFactory(activity, SpotifyPlusConfig.from(activity));
        LyricsRowViewFactory factory = new LyricsRowViewFactory(activity, textFactory);
        LyricsSurfaceRowPlanner.SurfacePolicy policy = LyricsSurfaceRowPlanner.SurfacePolicy.artwork(renderConfig);
        for (int i = window.startInclusive; i < window.endExclusive; i++) {
            AppliedLine line = document.appliedLines.get(i);
            LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, document, policy);
            View row = factory.build(plan.line, plan.options, null);
            rowsHost.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mountedIndices.add(i);
        }
        mountedStart = window.startInclusive;
        mountedEnd = window.endExclusive;
        mountedAnchor = window.anchorIndex;
        rowsHost.post(() -> centerActiveRow(window.anchorIndex));
    }

    private void invalidateMeasurements() {
        rowHeights = null;
        measuredAnchor = Integer.MIN_VALUE;
        clearMountedRows();
    }

    private void clearMountedRows() {
        if (document != null && document.appliedLines != null) {
            for (int index : mountedIndices) {
                if (index >= 0 && index < document.appliedLines.size()) {
                    clearLineViewState(document.appliedLines.get(index));
                }
            }
        }
        styleBatcher.clearPendingWrites();
        rowsHost.removeAllViews();
        rowsHost.setTranslationY(0f);
        mountedIndices.clear();
        mountedStart = -1;
        mountedEnd = -1;
        mountedAnchor = -1;
    }

    private void clearLineViewState(AppliedLine line) {
        LyricsLineViewState.clear(line, rowsHost,
                target -> LyricsLineViewState.invalidate(target, styleBatcher));
    }

    private void centerActiveRow(int anchorIndex) {
        if (document == null || anchorIndex < 0 || anchorIndex >= document.appliedLines.size()
                || rowsHost.getChildCount() == 0) return;
        View active = LyricsLineViewState.attachedRowView(
                document.appliedLines.get(anchorIndex), rowsHost);
        View first = rowsHost.getChildAt(0);
        View last = rowsHost.getChildAt(rowsHost.getChildCount() - 1);
        if (active == null || first == null || last == null) return;
        float contentCenter = (rowsHost.getPaddingTop()
                + rowsHost.getHeight() - rowsHost.getPaddingBottom()) * 0.5f;
        float desired = contentCenter - (active.getTop() + active.getBottom()) * 0.5f;
        float min = rowsHost.getPaddingTop() - first.getTop();
        float max = rowsHost.getHeight() - rowsHost.getPaddingBottom() - last.getBottom();
        float bounded = min <= max
                ? Math.max(min, Math.min(max, desired))
                : Math.max(-rowsHost.getHeight() * 0.5f,
                        Math.min(rowsHost.getHeight() * 0.5f, desired));
        rowsHost.setTranslationY(bounded);
    }

    private static TextView actionButton(Context context, String glyph, String description) {
        TextView button = new TextView(context);
        button.setText(glyph);
        button.setTextColor(Color.WHITE);
        button.setTextSize(25f);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x66000000);
        background.setShape(GradientDrawable.OVAL);
        button.setBackground(background);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum ActionTag { CLOSE, EXPAND }
}
