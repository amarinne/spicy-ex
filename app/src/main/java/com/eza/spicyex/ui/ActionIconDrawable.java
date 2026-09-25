package com.eza.spicyex.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * Host-safe line icons drawn from embedded lucide geometry (see {@link LucideIconData}).
 * No module resource ID is resolved inside Spotify's process, so each Kind carries its own
 * 24-unit absolute-path string, parsed once per process and cached.
 *
 * <p>Geometry is authored on lucide's 24 grid with stroke-width 2; {@link #draw} scales the
 * cached path to the requested bounds, keeping optical weight constant across sizes. The
 * {@code filled} variant renders the same outline as a solid glyph (used by the selected
 * option radio).
 */
public final class ActionIconDrawable extends Drawable {

    public enum Kind {
        CLOSE, EDIT, SAVE, VISIBILITY, VISIBILITY_OFF, DELETE, TEST, CHECK,
        CHEVRON_DOWN, CHEVRON_RIGHT,
        CHEVRONS_UP_DOWN, CHEVRONS_DOWN_UP, CHEVRONS_RIGHT, SPARKLES, MINUS, PLUS, CIRCLE, CIRCLE_HELP,
        AUDIO_LINES, BOOK_OPEN_TEXT, LANGUAGES, DISC_3, FULLSCREEN, ACTIVITY,
        BUG, ERASER, REFRESH, EXTERNAL_LINK,
        GLOBE, POINTER, TIMER, TARGET, BOLD, A_LARGE_SMALL, ROWS_2, CIRCLE_PLAY, PLAY,
        SUN_MEDIUM, ALIGN_VERTICAL_DISTRIBUTE_CENTER, TYPE, ELLIPSIS, WAND_SPARKLES,
        SPARKLE, DROPLETS, IMAGE, WHOLE_WORD, ALIGN_CENTER_VERTICAL, ARROW_RIGHT_LEFT,
        SETTINGS, HEART, STAR, VOLUME_OFF, SEARCH
    }

    public static Kind likedSongsKind(String value) {
        if ("Heart".equals(value)) return Kind.HEART;
        if ("Star".equals(value)) return Kind.STAR;
        return null;
    }

    private static final Map<String, Path> PARSED = new HashMap<>();

    private final Kind kind;
    private final boolean filled;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ActionIconDrawable(Kind kind, int color, float density) {
        this(kind, color, density, false);
    }

    public ActionIconDrawable(Kind kind, int color, float density, boolean filled) {
        this.kind = kind == null ? Kind.CHECK : kind;
        this.filled = filled;
        paint.setColor(color);
        paint.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        // Stroke width lives in 24-unit path coordinates (lucide's native 2), applied after
        // scaling, so optical weight matches at every rendered size.
        paint.setStrokeWidth(2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** An icon's outline in its 24-unit box, for callers that paint it themselves (shaders). */
    public static Path pathOf(Kind kind) {
        return pathFor(kind == null ? Kind.CHECK : kind);
    }

    private static Path pathFor(Kind kind) {
        String key = kind.name();
        Path cached = PARSED.get(key);
        if (cached != null) return cached;
        String data = LucideIconData.get(key);
        if (data == null) data = LucideIconData.get(Kind.CHECK.name());
        Path path = parsePath(data);
        PARSED.put(key, path);
        return path;
    }

    /** Parses the generated M/L/C/Z-only grammar; no arc or relative handling needed. */
    private static Path parsePath(String data) {
        Path path = new Path();
        if (data == null || data.isEmpty()) return path;
        String[] tokens = data.split(" ");
        int i = 0;
        int n = tokens.length;
        while (i < n) {
            char cmd = tokens[i].charAt(0);
            i++;
            switch (cmd) {
                case 'M': {
                    path.moveTo(Float.parseFloat(tokens[i]), Float.parseFloat(tokens[i + 1]));
                    i += 2;
                    break;
                }
                case 'L': {
                    path.lineTo(Float.parseFloat(tokens[i]), Float.parseFloat(tokens[i + 1]));
                    i += 2;
                    break;
                }
                case 'C': {
                    path.cubicTo(Float.parseFloat(tokens[i]), Float.parseFloat(tokens[i + 1]),
                            Float.parseFloat(tokens[i + 2]), Float.parseFloat(tokens[i + 3]),
                            Float.parseFloat(tokens[i + 4]), Float.parseFloat(tokens[i + 5]));
                    i += 6;
                    break;
                }
                case 'Z':
                    path.close();
                    break;
                default:
                    break;
            }
        }
        return path;
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        if (size <= 0f) return;
        canvas.save();
        canvas.translate(b.left + (b.width() - size) * 0.5f,
                b.top + (b.height() - size) * 0.5f);
        canvas.scale(size / 24f, size / 24f);
        canvas.drawPath(pathFor(kind), paint);
        canvas.restore();
    }

    @Override public void setTint(int color) {
        paint.setColor(color);
        invalidateSelf();
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
