package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.LiveLyricCardView;

import java.util.ArrayDeque;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/** Owns injection and lifecycle for the now-playing live lyric card. */
final class NowPlayingInjector {
    private static final int TAG_LIVE_CARD = 0x53504C43; // SPLC

    private final NativeSpicyLyricsHook hook;
    private final WeakHashMap<Activity, NowPlayingLyricController> controllers = new WeakHashMap<>();
    private final WeakHashMap<Activity, RetryState> retries = new WeakHashMap<>();
    private final WeakHashMap<Activity, AlignmentRetry> alignments = new WeakHashMap<>();
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

            NowPlayingLyricController controller = new NowPlayingLyricController(hook, activity, card);
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
