package com.eza.spicyex.sharecard;

import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;

import java.util.ArrayList;
import java.util.List;

/** The user's lyric alignment and position on the card; AUTO keeps the design's own. */
enum TextAlign { AUTO, START, CENTER, END }
enum TextPos { AUTO, TOP, MIDDLE, BOTTOM }

final class TextStyle {
    final TextAlign align;
    final TextPos pos;

    TextStyle(TextAlign align, TextPos pos) {
        this.align = align;
        this.pos = pos;
    }
}

/** Where the lyrics go on a design, and in which colours. */
final class TextBox {
    final float left, top, width, height;
    final int color, subColor;
    final Layout.Alignment align;
    /** The lyric's typeface; null for the default bold. */
    final Typeface face;
    /** The largest the lyric is set; a caption (Polaroid) stays a caption. */
    final float maxSize;

    TextBox(float left, float top, float width, float height, int color, int subColor, boolean center) {
        this(left, top, width, height, color, subColor, center, null);
    }

    TextBox(float left, float top, float width, float height, int color, int subColor, boolean center,
            Typeface face) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.color = color;
        this.subColor = subColor;
        this.align = center ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
        this.face = face;
        this.maxSize = LyricsShareCardController.MAX_TEXT;
    }

    private TextBox(TextBox from, float left, float top, float height, Layout.Alignment align,
                    float maxSize) {
        this.left = left;
        this.top = top;
        this.width = from.width;
        this.height = height;
        this.color = from.color;
        this.subColor = from.subColor;
        this.align = align;
        this.face = from.face;
        this.maxSize = maxSize;
    }

    TextBox moved(float left, float top, float height) {
        return new TextBox(this, left, top, height, align, maxSize);
    }

    TextBox aligned(Layout.Alignment align) {
        return new TextBox(this, left, top, height, align, maxSize);
    }

    TextBox capped(float size) {
        return new TextBox(this, left, top, height, align, Math.max(LyricsShareCardController.MIN_TEXT, size));
    }
}

final class Fitted {
    final List<StaticLayout> main = new ArrayList<>();
    final List<StaticLayout> sub = new ArrayList<>();
    float height;
}
