package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;

import android.app.Activity;
import android.graphics.Rect;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.ArtworkLyricsOverlayView;
import com.eza.spicyex.lyrics.LiveLyricCardView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/** Owns injection and lifecycle for the now-playing live lyric card. */
final class NowPlayingInjector {
    private static final int TAG_LIVE_CARD = 0x53504C43; // SPLC
    private static final int TAG_ARTWORK_OVERLAY = 0x53504C41; // SPLA

    private final NativeSpicyLyricsHook hook;
    private final WeakHashMap<Activity, NowPlayingLyricController> controllers = new WeakHashMap<>();
    private final WeakHashMap<Activity, RetryState> retries = new WeakHashMap<>();
    private final WeakHashMap<Activity, AlignmentRetry> alignments = new WeakHashMap<>();
    private final WeakHashMap<Activity, CachedArtworkTarget> artworkTargets = new WeakHashMap<>();
    private static final long[] RETRY_DELAYS_MS = {700L, 1100L, 1700L};
    private static final long[] ALIGNMENT_DELAYS_MS = {500L, 700L, 1400L};

    NowPlayingInjector(NativeSpicyLyricsHook hook) {
        this.hook = hook;
    }

    void schedule(Activity activity) {
        if (activity == null) return;
        try {
            if (!hook.isNativeSpicyEnabled(activity)) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            cancelRetry(activity);
            RetryState state = new RetryState(activity, decor);
            synchronized (retries) {
                retries.put(activity, state);
            }
            state.postNext();
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " schedule live card injection failed: " + t);
        }
    }

    void stop(Activity activity) {
        if (activity == null) return;
        cancelRetry(activity);
        cancelAlignment(activity);
        NowPlayingLyricController controller;
        synchronized (controllers) {
            controller = controllers.get(activity);
        }
        if (controller != null) controller.stop();
    }

    void destroy(Activity activity) {
        stop(activity);
        synchronized (controllers) {
            controllers.remove(activity);
        }
        synchronized (artworkTargets) {
            CachedArtworkTarget cached = artworkTargets.remove(activity);
            if (cached != null) cached.detach();
        }
    }

    boolean consumeArtworkBack(Activity activity) {
        NowPlayingLyricController controller;
        synchronized (controllers) {
            controller = controllers.get(activity);
        }
        return controller != null && controller.consumeArtworkBack();
    }

    private boolean inject(Activity activity) {
        try {
            if (activity == null || !hook.isNativeSpicyEnabled(activity)) return true;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return false;
            if (content.findViewWithTag(TAG_LIVE_CARD) != null) {
                NowPlayingLyricController controller;
                synchronized (controllers) {
                    controller = controllers.get(activity);
                }
                if (controller != null) controller.start();
                return true;
            }
            View lyricsElement = findViewByResourceEntryName(content, "lyrics_element");
            if (lyricsElement == null || !(lyricsElement.getParent() instanceof ViewGroup)) return false;
            ViewGroup parent = (ViewGroup) lyricsElement.getParent();
            if (parent.findViewWithTag(TAG_LIVE_CARD) != null) return true;

            int index = parent.indexOfChild(lyricsElement);
            ViewGroup.LayoutParams layoutParams = lyricsElement.getLayoutParams();
            LiveLyricCardView card = new LiveLyricCardView(activity);
            card.setTag(TAG_LIVE_CARD);
            parent.removeView(lyricsElement);
            card.setLayoutParams(layoutParams);
            parent.addView(card, index);

            ArtworkLyricsOverlayView artworkOverlay = new ArtworkLyricsOverlayView(activity);
            artworkOverlay.setTag(TAG_ARTWORK_OVERLAY);
            content.addView(artworkOverlay, new FrameLayout.LayoutParams(1, 1));

            NowPlayingLyricController controller = new NowPlayingLyricController(
                    hook, activity, card, artworkOverlay, new NowPlayingLyricController.ArtworkTargetHost() {
                private NowPlayingLyricController.TargetInvalidationListener invalidationListener;

                @Override
                public NowPlayingArtworkTargetResolver.Resolution resolve(boolean applyBounds) {
                    return resolveArtworkTarget(
                            activity, artworkOverlay, applyBounds, invalidationListener);
                }

                @Override
                public void invalidate() {
                    invalidateArtworkTarget(activity);
                }

                @Override
                public void setInvalidationListener(
                        NowPlayingLyricController.TargetInvalidationListener listener
                ) {
                    invalidationListener = listener;
                }
            });
            synchronized (controllers) {
                controllers.put(activity, controller);
            }
            controller.start();

            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) {
                AlignmentRetry alignment = new AlignmentRetry(activity, decor, card);
                synchronized (alignments) {
                    alignments.put(activity, alignment);
                }
                alignment.postNext();
            }
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " live lyric card injected in " + activity.getClass().getName());
            Diagnostics.event("renderer", "mount_state",
                    Diagnostics.context("surface", "now_playing", "mounted", "true"));
            return true;
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " live card inject failed: " + t);
            Diagnostics.event("renderer", "mount_state", t,
                    Diagnostics.context("surface", "now_playing", "mounted", "false"));
            return false;
        }
    }

    private NowPlayingArtworkTargetResolver.Resolution resolveArtworkTarget(
            Activity activity,
            ArtworkLyricsOverlayView overlay,
            boolean applyBounds,
            NowPlayingLyricController.TargetInvalidationListener invalidationListener
    ) {
        try {
            FrameLayout content = activity == null ? null : activity.findViewById(android.R.id.content);
            if (content == null) return new NowPlayingArtworkTargetResolver.Resolution(
                    NowPlayingArtworkTargetResolver.Kind.NONE, null);
            CachedArtworkTarget cached;
            synchronized (artworkTargets) {
                cached = artworkTargets.get(activity);
            }
            if (cached != null && cached.isValid(content)) {
                NowPlayingArtworkTargetResolver.Resolution resolution = cached.currentResolution();
                if (applyBounds) applyArtworkBounds(content, overlay, resolution);
                return resolution;
            }
            View mediaRoot = findCenteredMusicContainer(content);
            if (mediaRoot == null) return new NowPlayingArtworkTargetResolver.Resolution(
                    NowPlayingArtworkTargetResolver.Kind.NONE, null);
            List<DetectedCandidate> detected = new ArrayList<>();
            List<View> potentialVideos = new ArrayList<>();
            List<ViewGroup> observedGroups = new ArrayList<>();
            collectArtworkCandidates(mediaRoot, detected, potentialVideos, observedGroups);
            List<NowPlayingArtworkTargetResolver.Candidate> candidates = new ArrayList<>();
            for (DetectedCandidate candidate : detected) candidates.add(candidate.candidate);
            NowPlayingArtworkTargetResolver.Resolution resolution =
                    NowPlayingArtworkTargetResolver.resolve(candidates);
            View targetView = null;
            for (DetectedCandidate candidate : detected) {
                if (resolution.kind == NowPlayingArtworkTargetResolver.Kind.CANVAS && candidate.candidate.video) {
                    targetView = candidate.view;
                    break;
                }
                if (resolution.kind == NowPlayingArtworkTargetResolver.Kind.COVER
                        && sameBounds(candidate.candidate, resolution.cover)) {
                    targetView = candidate.view;
                    break;
                }
            }
            CachedArtworkTarget next = new CachedArtworkTarget(
                    mediaRoot, targetView, potentialVideos, observedGroups, resolution.kind,
                    invalidationListener);
            synchronized (artworkTargets) {
                CachedArtworkTarget previous = artworkTargets.put(activity, next);
                if (previous != null) previous.detach();
            }
            if (applyBounds) applyArtworkBounds(content, overlay, resolution);
            return resolution;
        } catch (Throwable ignored) {
            return new NowPlayingArtworkTargetResolver.Resolution(
                    NowPlayingArtworkTargetResolver.Kind.AMBIGUOUS, null);
        }
    }

    private void invalidateArtworkTarget(Activity activity) {
        synchronized (artworkTargets) {
            CachedArtworkTarget cached = artworkTargets.get(activity);
            if (cached != null) cached.invalidated = true;
        }
    }

    private void applyArtworkBounds(
            FrameLayout content,
            ArtworkLyricsOverlayView overlay,
            NowPlayingArtworkTargetResolver.Resolution resolution
    ) {
        if (resolution == null || resolution.kind != NowPlayingArtworkTargetResolver.Kind.COVER
                || resolution.cover == null) return;
        int[] contentLocation = new int[2];
        content.getLocationInWindow(contentLocation);
        FrameLayout.LayoutParams lp = overlay.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) overlay.getLayoutParams()
                : new FrameLayout.LayoutParams(1, 1);
        lp.width = resolution.cover.right - resolution.cover.left;
        lp.height = resolution.cover.bottom - resolution.cover.top;
        lp.leftMargin = resolution.cover.left - contentLocation[0];
        lp.topMargin = resolution.cover.top - contentLocation[1];
        overlay.setLayoutParams(lp);
        overlay.bringToFront();
    }

    private View findCenteredMusicContainer(FrameLayout content) {
        View carousel = findVisibleTrackCarousel(content);
        if (carousel == null) return null;
        List<View> roots = findViewsByResourceEntryName(carousel, "music_container");
        Rect contentRect = new Rect();
        if (!carousel.getGlobalVisibleRect(contentRect)) return null;
        float centerX = contentRect.exactCenterX();
        float centerY = contentRect.exactCenterY();
        View best = null;
        double bestDistance = Double.MAX_VALUE;
        for (View root : roots) {
            Rect visible = new Rect();
            if (root == null || root.getVisibility() != View.VISIBLE || root.getAlpha() <= 0.01f
                    || !root.getGlobalVisibleRect(visible) || root.getWidth() <= 0 || root.getHeight() <= 0
                    || visible.width() < root.getWidth() * 0.80f
                    || visible.height() < root.getHeight() * 0.80f) continue;
            double dx = visible.exactCenterX() - centerX;
            double dy = visible.exactCenterY() - centerY;
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                best = root;
                bestDistance = distance;
            }
        }
        return best;
    }

    private View findVisibleTrackCarousel(FrameLayout content) {
        List<View> carousels = findViewsByResourceEntryName(content, "track_carousel");
        View best = null;
        int bestArea = 0;
        for (View carousel : carousels) {
            Rect visible = new Rect();
            if (carousel == null || carousel.getVisibility() != View.VISIBLE
                    || carousel.getAlpha() <= 0.01f || !carousel.getGlobalVisibleRect(visible)) continue;
            int area = visible.width() * visible.height();
            if (area > bestArea) {
                best = carousel;
                bestArea = area;
            }
        }
        return best;
    }

    private void collectArtworkCandidates(
            View root,
            List<DetectedCandidate> out,
            List<View> potentialVideos,
            List<ViewGroup> observedGroups
    ) {
        if (root == null || out == null) return;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            String entry = resourceEntryName(view);
            String className = view.getClass().getName().toLowerCase(Locale.ROOT);
            boolean video = "video_surface".equals(entry)
                    || "surface_view".equals(entry)
                    || view instanceof SurfaceView
                    || view instanceof TextureView
                    || className.contains("videosurface");
            if (video) potentialVideos.add(view);
            Rect visible = new Rect();
            if (view.getVisibility() == View.VISIBLE && view.getAlpha() > 0.01f
                    && view.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0) {
                if (video && visible.width() >= dp(80) && visible.height() >= dp(80)) {
                    out.add(new DetectedCandidate(view, new NowPlayingArtworkTargetResolver.Candidate(
                            true, visible.left, visible.top, visible.right, visible.bottom)));
                } else if (view instanceof ImageView && isPlausibleCover(view, entry, visible)) {
                    out.add(new DetectedCandidate(view, new NowPlayingArtworkTargetResolver.Candidate(
                            false, visible.left, visible.top, visible.right, visible.bottom)));
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                observedGroups.add(group);
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
    }

    private boolean sameBounds(
            NowPlayingArtworkTargetResolver.Candidate left,
            NowPlayingArtworkTargetResolver.Candidate right
    ) {
        return left != null && right != null && left.left == right.left && left.top == right.top
                && left.right == right.right && left.bottom == right.bottom;
    }

    private static final class DetectedCandidate {
        final View view;
        final NowPlayingArtworkTargetResolver.Candidate candidate;

        DetectedCandidate(View view, NowPlayingArtworkTargetResolver.Candidate candidate) {
            this.view = view;
            this.candidate = candidate;
        }
    }

    private final class CachedArtworkTarget implements View.OnLayoutChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> mediaRoot;
        final WeakReference<View> target;
        final List<WeakReference<View>> potentialVideos = new ArrayList<>();
        final List<ObservedGroup> observedGroups = new ArrayList<>();
        final NowPlayingArtworkTargetResolver.Kind kind;
        final NowPlayingLyricController.TargetInvalidationListener invalidationListener;
        boolean invalidated;
        boolean invalidationNotified;
        ViewTreeObserver registeredObserver;

        CachedArtworkTarget(View mediaRoot, View target, List<View> potentialVideos,
                            List<ViewGroup> observedGroups,
                            NowPlayingArtworkTargetResolver.Kind kind,
                            NowPlayingLyricController.TargetInvalidationListener invalidationListener) {
            this.mediaRoot = new WeakReference<>(mediaRoot);
            this.target = new WeakReference<>(target);
            this.kind = kind;
            this.invalidationListener = invalidationListener;
            if (potentialVideos != null) {
                for (View video : potentialVideos) this.potentialVideos.add(new WeakReference<>(video));
            }
            if (observedGroups != null) {
                for (ViewGroup group : observedGroups) this.observedGroups.add(new ObservedGroup(group));
            }
            if (mediaRoot != null) {
                mediaRoot.addOnLayoutChangeListener(this);
                registeredObserver = mediaRoot.getViewTreeObserver();
                registeredObserver.addOnGlobalLayoutListener(this);
                registeredObserver.addOnPreDrawListener(this);
            }
        }

        boolean isValid(FrameLayout content) {
            View root = mediaRoot.get();
            View targetView = target.get();
            if (invalidated || content == null || root == null || !root.isAttachedToWindow() || !root.isShown()
                    || targetView == null || !targetView.isAttachedToWindow() || !targetView.isShown()) return false;
            if (kind == NowPlayingArtworkTargetResolver.Kind.COVER && hasActivePotentialVideo()) return false;
            Rect contentRect = new Rect();
            Rect rootRect = new Rect();
            Rect targetRect = new Rect();
            if (!content.getGlobalVisibleRect(contentRect) || !root.getGlobalVisibleRect(rootRect)
                    || !targetView.getGlobalVisibleRect(targetRect)) return false;
            if (rootRect.width() < root.getWidth() * 0.80f || rootRect.height() < root.getHeight() * 0.80f
                    || targetRect.width() < targetView.getWidth() * 0.80f
                    || targetRect.height() < targetView.getHeight() * 0.80f) return false;
            return Math.abs(rootRect.exactCenterX() - contentRect.exactCenterX())
                    <= Math.max(dp(24), contentRect.width() * 0.20f);
        }

        private boolean hasActivePotentialVideo() {
            for (WeakReference<View> reference : potentialVideos) {
                View video = reference.get();
                Rect visible = new Rect();
                if (video != null && video.getVisibility() == View.VISIBLE && video.getAlpha() > 0.01f
                        && video.isShown() && video.getGlobalVisibleRect(visible)
                        && visible.width() >= dp(80) && visible.height() >= dp(80)) return true;
            }
            return false;
        }

        NowPlayingArtworkTargetResolver.Resolution currentResolution() {
            View targetView = target.get();
            Rect visible = new Rect();
            if (targetView == null || !targetView.getGlobalVisibleRect(visible)) {
                return new NowPlayingArtworkTargetResolver.Resolution(
                        NowPlayingArtworkTargetResolver.Kind.NONE, null);
            }
            NowPlayingArtworkTargetResolver.Candidate candidate =
                    new NowPlayingArtworkTargetResolver.Candidate(
                            kind == NowPlayingArtworkTargetResolver.Kind.CANVAS,
                            visible.left, visible.top, visible.right, visible.bottom);
            return new NowPlayingArtworkTargetResolver.Resolution(
                    kind, kind == NowPlayingArtworkTargetResolver.Kind.COVER ? candidate : null);
        }

        void detach() {
            View root = mediaRoot.get();
            if (root != null) root.removeOnLayoutChangeListener(this);
            ViewTreeObserver observer = registeredObserver;
            if (observer != null && observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(this);
                observer.removeOnPreDrawListener(this);
            }
            registeredObserver = null;
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                invalidated = true;
            }
        }

        @Override
        public void onGlobalLayout() {
            if (kind == NowPlayingArtworkTargetResolver.Kind.COVER && hasActivePotentialVideo()) {
                invalidated = true;
                notifyInvalidation(NowPlayingArtworkTargetResolver.Kind.CANVAS);
                return;
            }
            for (ObservedGroup observed : observedGroups) {
                ViewGroup group = observed.group.get();
                if (group == null || group.getChildCount() != observed.childCount) {
                    invalidated = true;
                    View root = mediaRoot.get();
                    if (hasActiveVideoInSubtree(root)) {
                        notifyInvalidation(NowPlayingArtworkTargetResolver.Kind.CANVAS);
                    }
                    return;
                }
            }
        }

        @Override
        public boolean onPreDraw() {
            if (kind == NowPlayingArtworkTargetResolver.Kind.COVER && hasActivePotentialVideo()) {
                invalidated = true;
                notifyInvalidation(NowPlayingArtworkTargetResolver.Kind.CANVAS);
            }
            return true;
        }

        private void notifyInvalidation(NowPlayingArtworkTargetResolver.Kind invalidKind) {
            if (invalidationNotified || invalidationListener == null) return;
            invalidationNotified = true;
            invalidationListener.onTargetInvalidated(invalidKind);
        }
    }

    private boolean hasActiveVideoInSubtree(View root) {
        if (root == null) return false;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            String entry = resourceEntryName(view);
            String className = view.getClass().getName().toLowerCase(Locale.ROOT);
            boolean video = "video_surface".equals(entry) || "surface_view".equals(entry)
                    || view instanceof SurfaceView || view instanceof TextureView
                    || className.contains("videosurface");
            Rect visible = new Rect();
            if (video && view.getVisibility() == View.VISIBLE && view.getAlpha() > 0.01f
                    && view.isShown() && view.getGlobalVisibleRect(visible)
                    && visible.width() >= dp(80) && visible.height() >= dp(80)) return true;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return false;
    }

    private static final class ObservedGroup {
        final WeakReference<ViewGroup> group;
        final int childCount;

        ObservedGroup(ViewGroup group) {
            this.group = new WeakReference<>(group);
            this.childCount = group == null ? -1 : group.getChildCount();
        }
    }

    private boolean isPlausibleCover(View view, String entry, Rect visible) {
        String name = entry == null ? "" : entry.toLowerCase(Locale.ROOT);
        boolean namedCover = "image".equals(name) || name.contains("cover")
                || name.contains("album") || name.contains("artwork");
        if (!namedCover || visible.width() < dp(120) || visible.height() < dp(120)) return false;
        float ratio = visible.width() / (float) Math.max(1, visible.height());
        if (ratio < 0.80f || ratio > 1.20f) return false;
        return visible.width() >= view.getWidth() * 0.80f && visible.height() >= view.getHeight() * 0.80f;
    }

    private List<View> findViewsByResourceEntryName(View root, String entryName) {
        List<View> matches = new ArrayList<>();
        if (root == null || isBlank(entryName)) return matches;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            if (entryName.equals(resourceEntryName(view))) matches.add(view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return matches;
    }

    private String resourceEntryName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "";
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void cancelRetry(Activity activity) {
        RetryState state;
        synchronized (retries) {
            state = retries.remove(activity);
        }
        if (state != null) state.cancel();
    }

    private void cancelAlignment(Activity activity) {
        AlignmentRetry retry;
        synchronized (alignments) {
            retry = alignments.remove(activity);
        }
        if (retry != null) retry.cancel();
    }

    private final class RetryState implements Runnable {
        private final Activity activity;
        private final View decor;
        private int attempt;
        private boolean cancelled;

        RetryState(Activity activity, View decor) {
            this.activity = activity;
            this.decor = decor;
        }

        void postNext() {
            if (cancelled || attempt >= RETRY_DELAYS_MS.length) return;
            decor.postDelayed(this, RETRY_DELAYS_MS[attempt++]);
        }

        void cancel() {
            cancelled = true;
            decor.removeCallbacks(this);
        }

        @Override
        public void run() {
            if (cancelled) return;
            if (inject(activity)) {
                synchronized (retries) {
                    if (retries.get(activity) == this) retries.remove(activity);
                }
                return;
            }
            postNext();
        }
    }

    private final class AlignmentRetry implements Runnable {
        private final Activity activity;
        private final View decor;
        private final View card;
        private int attempt;
        private boolean cancelled;

        AlignmentRetry(Activity activity, View decor, View card) {
            this.activity = activity;
            this.decor = decor;
            this.card = card;
        }

        void postNext() {
            if (cancelled || attempt >= ALIGNMENT_DELAYS_MS.length) return;
            decor.postDelayed(this, ALIGNMENT_DELAYS_MS[attempt++]);
        }

        void cancel() {
            cancelled = true;
            decor.removeCallbacks(this);
        }

        @Override
        public void run() {
            if (cancelled) return;
            if (alignCardLeftToContent(activity, card)) {
                synchronized (alignments) {
                    if (alignments.get(activity) == this) alignments.remove(activity);
                }
                return;
            }
            postNext();
        }
    }

    private boolean alignCardLeftToContent(Activity activity, View card) {
        try {
            if (card == null || card.getWidth() <= 0) return false;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return false;
            View reference = findViewByResourceEntryName(content, "position_text");
            if (reference == null || reference.getWidth() <= 0) return false;
            int[] cardLocation = new int[2];
            int[] referenceLocation = new int[2];
            card.getLocationInWindow(cardLocation);
            reference.getLocationInWindow(referenceLocation);
            int delta = referenceLocation[0] - (cardLocation[0] + card.getPaddingLeft());
            if (delta > 0 && delta < dp(48)) {
                card.setPadding(card.getPaddingLeft() + delta, card.getPaddingTop(),
                        card.getPaddingRight(), card.getPaddingBottom());
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private View findViewByResourceEntryName(View root, String entryName) {
        if (root == null || isBlank(entryName)) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            int id = view.getId();
            if (id != View.NO_ID) {
                try {
                    String name = view.getResources().getResourceEntryName(id);
                    if (entryName.equals(name)) return view;
                } catch (Throwable ignored) {
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return null;
    }
}
