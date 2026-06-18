package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.eza.spicyex.lyrics.LiveLyricCardView;

import java.util.ArrayDeque;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/** Owns injection and lifecycle for the now-playing live lyric card. */
final class NowPlayingInjector {
    private static final int TAG_LIVE_CARD = 0x53504C43; // SPLC

    private final NativeSpicyLyricsHook hook;
    private final WeakHashMap<Activity, NowPlayingLyricController> controllers = new WeakHashMap<>();

    NowPlayingInjector(NativeSpicyLyricsHook hook) {
        this.hook = hook;
    }

    void schedule(Activity activity) {
        if (activity == null) return;
        try {
            if (!hook.isNativeSpicyEnabled(activity)) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            decor.postDelayed(() -> inject(activity), 700);
            decor.postDelayed(() -> inject(activity), 1800);
            decor.postDelayed(() -> inject(activity), 3500);
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " schedule live card injection failed: " + t);
        }
    }

    void stop(Activity activity) {
        if (activity == null) return;
        NowPlayingLyricController controller;
        synchronized (controllers) {
            controller = controllers.get(activity);
        }
        if (controller != null) controller.stop();
    }

    private void inject(Activity activity) {
        try {
            if (activity == null || !hook.isNativeSpicyEnabled(activity)) return;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            if (content.findViewWithTag(TAG_LIVE_CARD) != null) {
                NowPlayingLyricController controller;
                synchronized (controllers) {
                    controller = controllers.get(activity);
                }
                if (controller != null) controller.start();
                return;
            }
            View lyricsElement = findViewByResourceEntryName(content, "lyrics_element");
            if (lyricsElement == null || !(lyricsElement.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) lyricsElement.getParent();
            if (parent.findViewWithTag(TAG_LIVE_CARD) != null) return;

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
                Runnable align = () -> alignCardLeftToContent(activity, card);
                decor.postDelayed(align, 500);
                decor.postDelayed(align, 1200);
                decor.postDelayed(align, 2600);
            }
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " live lyric card injected in " + activity.getClass().getName());
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " live card inject failed: " + t);
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
