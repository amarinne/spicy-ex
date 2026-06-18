package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.widget.ImageButton;
import android.widget.ImageView;

import com.eza.spicyex.References;

import de.robv.android.xposed.XposedBridge;

final class NativeIconButtons {
    private NativeIconButtons() {
    }

    static ImageButton createRoundIconButton(
            Context context,
            int drawableRes,
            String description,
            int sizeDp,
            int paddingDp
    ) {
        ImageButton button = new ImageButton(context);
        button.setContentDescription(description);
        setModuleIcon(button, context, drawableRes);
        button.setColorFilter(Color.rgb(232, 232, 238));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        button.setMinimumWidth(dp(sizeDp));
        button.setMinimumHeight(dp(sizeDp));
        button.setClickable(true);
        button.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(48, 255, 255, 255));
        bg.setStroke(dp(1), Color.argb(52, 255, 255, 255));
        button.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) button.setElevation(dp(8));
        return button;
    }

    static void setModuleIcon(ImageButton button, Context context, int drawableRes) {
        if (button == null) return;
        try {
            Drawable drawable = References.modResources == null ? null : References.modResources.getDrawable(drawableRes);
            if (drawable == null && context != null) {
                drawable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                        ? context.getResources().getDrawable(drawableRes, context.getTheme())
                        : context.getResources().getDrawable(drawableRes);
            }
            button.setImageDrawable(drawable);
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " failed to load module icon " + drawableRes + ": " + t);
            button.setImageDrawable(null);
        }
    }
}
