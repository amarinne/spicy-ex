package com.eza.spicyex.hooks;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Reads visible text separately from the row's full-width layout box. */
final class LyricRowProbe {
    static void record(LayoutProbeReport report, View row) {
        int[] bounds = LyricsLayoutEditController.screenRectOf(row);
        if (bounds == null) return;
        int[] content = {bounds[0] + row.getPaddingLeft(), bounds[1] + row.getPaddingTop(),
                bounds[2] - row.getPaddingRight(), bounds[3] - row.getPaddingBottom()};
        if (content[2] <= content[0] || content[3] <= content[1]) content = bounds;
        List<int[]> text = new ArrayList<>();
        collectText(row, text);
        report.lyricRow(content, text);
    }

    private static void collectText(View view, List<int[]> out) {
        if (LyricsLayoutEditController.screenRectOf(view) == null) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Layout layout = text.getLayout();
            Rect visible = new Rect();
            if (layout == null || !text.getGlobalVisibleRect(visible)) return;
            // Android returns clipping in root coordinates, including for a floating window.
            int[] rootOrigin = new int[2];
            text.getRootView().getLocationOnScreen(rootOrigin);
            visible.offset(rootOrigin[0], rootOrigin[1]);
            Matrix matrix = new Matrix();
            toScreen(text, matrix);
            for (int i = 0; i < layout.getLineCount(); i++) {
                float left = layout.getLineLeft(i), right = layout.getLineRight(i);
                if (right <= left) continue;
                RectF line = new RectF(left + text.getTotalPaddingLeft() - text.getScrollX(),
                        layout.getLineTop(i) + text.getTotalPaddingTop() - text.getScrollY(),
                        right + text.getTotalPaddingLeft() - text.getScrollX(),
                        layout.getLineBottom(i) + text.getTotalPaddingTop() - text.getScrollY());
                matrix.mapRect(line);
                if (line.intersect(visible.left, visible.top, visible.right, visible.bottom)) {
                    out.add(new int[]{(int) Math.floor(line.left), (int) Math.floor(line.top),
                            (int) Math.ceil(line.right), (int) Math.ceil(line.bottom)});
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), out);
        }
    }

    /** Includes ancestor scroll and scale without APIs newer than the minimum Android version. */
    private static void toScreen(View view, Matrix matrix) {
        if (view.getParent() instanceof View) {
            View parent = (View) view.getParent();
            toScreen(parent, matrix);
            matrix.preTranslate(-parent.getScrollX(), -parent.getScrollY());
            matrix.preTranslate(view.getLeft(), view.getTop());
        } else {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            float[] origin = {0f, 0f};
            view.getMatrix().mapPoints(origin);
            matrix.preTranslate(location[0] - origin[0], location[1] - origin[1]);
        }
        matrix.preConcat(view.getMatrix());
    }

    private LyricRowProbe() { }
}
