package com.eza.spicyex.sharecard;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Line icons drawn on a 24-unit grid (2-unit round strokes), in place of text glyphs whose
 * look depended on the font.
 */
final class ShareCardIcon extends android.graphics.drawable.Drawable {
    enum Kind { CLOSE, LINK, DOWNLOAD, MORE, CHECK, ALIGN_START, ALIGN_CENTER, ALIGN_END,
        POS_TOP, POS_MIDDLE, POS_BOTTOM, CODE, GLOBE, LYRICS, ARROW_UP }

    final Kind kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 0..1 through a one-shot motion (CODE, GLOBE); 0 and 1 both draw the still icon. */
    private float phase;

    void setPhase(float value) {
        phase = value;
        invalidateSelf();
    }

    ShareCardIcon(Kind kind, int color) {
        this.kind = kind;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(2f);
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        android.graphics.Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
        c.scale(s, s);
        Paint p = paint;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        switch (kind) {
            case CLOSE:
                c.drawLine(6, 6, 18, 18, p);
                c.drawLine(18, 6, 6, 18, p);
                break;
            case LINK: {
                Path path = new Path();
                path.moveTo(10, 7);
                path.lineTo(7.5f, 7);
                path.arcTo(new RectF(2.5f, 7, 12.5f, 17), 270, -180);
                path.lineTo(10, 17);
                path.moveTo(14, 7);
                path.lineTo(16.5f, 7);
                path.arcTo(new RectF(11.5f, 7, 21.5f, 17), 270, 180);
                path.lineTo(14, 17);
                c.drawPath(path, p);
                c.drawLine(8.5f, 12, 15.5f, 12, p);
                break;
            }
            case DOWNLOAD:
                c.drawLine(12, 4, 12, 15, p);
                c.drawLine(7, 10, 12, 15, p);
                c.drawLine(17, 10, 12, 15, p);
                c.drawLine(5, 20, 19, 20, p);
                break;
            case MORE:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(5.5f, 12, 1.9f, p);
                c.drawCircle(12, 12, 1.9f, p);
                c.drawCircle(18.5f, 12, 1.9f, p);
                break;
            case CHECK: {
                Path path = new Path();
                path.moveTo(5, 12.5f);
                path.lineTo(10, 17.5f);
                path.lineTo(19, 7);
                c.drawPath(path, p);
                break;
            }
            case ALIGN_START:
            case ALIGN_CENTER:
            case ALIGN_END: {
                float[] ys = {6, 10, 14, 18};
                for (int i = 0; i < ys.length; i++) {
                    boolean full = i % 2 == 0;
                    float len = full ? 16 : 10;
                    float x0 = kind == Kind.ALIGN_START ? 4 : kind == Kind.ALIGN_END ? 20 - len : 12 - len / 2f;
                    c.drawLine(x0, ys[i], x0 + len, ys[i], p);
                }
                break;
            }
            case POS_TOP:
            case POS_MIDDLE:
            case POS_BOTTOM: {
                p.setStrokeWidth(1.6f);
                c.drawRoundRect(new RectF(3.5f, 3.5f, 20.5f, 20.5f), 3.5f, 3.5f, p);
                p.setStrokeWidth(2f);
                float y = kind == Kind.POS_TOP ? 8 : kind == Kind.POS_MIDDLE ? 10.5f : 13;
                c.drawLine(8, y, 16, y, p);
                c.drawLine(8, y + 3, 14, y + 3, p);
                break;
            }
            case CODE: {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(5, 12, 2.6f, p);
                p.setStyle(Paint.Style.STROKE);
                float[] heights = {5, 10, 6, 12, 7, 4};
                // While playing, each bar bounces like a level meter, eased in and out of
                // the still shape so the motion starts and ends on the icon itself.
                float blend = phase <= 0f || phase >= 1f ? 0f
                        : (float) Math.sin(Math.PI * phase);
                for (int i = 0; i < heights.length; i++) {
                    float x = 10 + i * 2.2f;
                    float h = heights[i];
                    if (blend > 0f) {
                        double beat = phase * 5.5 + i * 0.37;
                        float level = 3f + 10f * (float) Math.abs(Math.sin(Math.PI * beat)
                                * (0.65 + 0.35 * Math.sin(2.3 * Math.PI * beat + i)));
                        h = h + (level - h) * blend;
                    }
                    c.drawLine(x, 12 - h / 2f, x, 12 + h / 2f, p);
                }
                break;
            }
            case ARROW_UP:
                c.drawLine(12, 19, 12, 5.5f, p);
                c.drawLine(6.5f, 11, 12, 5.5f, p);
                c.drawLine(17.5f, 11, 12, 5.5f, p);
                break;
            case LYRICS: {
                // A list with the first rows ticked: pick lines.
                float[] ys = {6.5f, 12, 17.5f};
                for (int i = 0; i < ys.length; i++) {
                    c.drawLine(10, ys[i], i == 2 ? 16 : 20, ys[i], p);
                }
                p.setStrokeWidth(1.8f);
                c.drawLine(3.5f, 6.5f, 5, 8, p);
                c.drawLine(5, 8, 7.5f, 5, p);
                c.drawLine(3.5f, 12, 5, 13.5f, p);
                c.drawLine(5, 13.5f, 7.5f, 10.5f, p);
                break;
            }
            case GLOBE:
                p.setStrokeWidth(1.7f);
                c.drawCircle(12, 12, 9, p);
                c.drawLine(3, 12, 21, 12, p);
                if (phase <= 0f || phase >= 1f) {
                    c.drawOval(new RectF(8, 3, 16, 21), p);
                } else {
                    // One eased turn: meridians sweep across, narrowing toward the rim, the
                    // way a spinning globe's lines of longitude do.
                    float e = phase < 0.5f ? 4f * phase * phase * phase
                            : 1f - (float) Math.pow(-2f * phase + 2f, 3) / 2f;
                    double base = Math.asin(4.0 / 9.0) + e * Math.PI;
                    int alpha = p.getAlpha();
                    float presence = (float) Math.sin(Math.PI * phase);
                    for (int k = 0; k < 2; k++) {
                        double a = base + k * Math.PI / 2;
                        float half = 9f * (float) Math.abs(Math.sin(a));
                        // The second meridian fades in and out with the turn, so the first
                        // and last frames are the still icon.
                        if (k == 1) p.setAlpha(Math.round(alpha * presence));
                        if (half < 0.4f) {
                            c.drawLine(12, 3, 12, 21, p);
                        } else {
                            c.drawOval(new RectF(12 - half, 3, 12 + half, 21), p);
                        }
                    }
                    p.setAlpha(alpha);
                }
                break;
            default:
                break;
        }
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
