package com.eza.spicyex.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

/** Dark anchored picker used by AI preset and model rows instead of platform popup styling. */
public final class PanelPickerPopup {
    private PanelPickerPopup() {}
    public static void show(Context context, View anchor, List<String> choices, String selected,
                            Consumer<String> onPick) {
        if (context == null || anchor == null || choices == null || choices.isEmpty()) return;
        float density = context.getResources().getDisplayMetrics().density;
        int maxWidth = context.getResources().getDisplayMetrics().widthPixels - dp(density, 32);
        int width = anchor.getWidth() > dp(density, 180)
                ? Math.min(anchor.getWidth(), maxWidth)
                : Math.min(dp(density, 360), maxWidth);
        int estimatedHeight = dp(density, 16 + choices.size() * 54);
        int height = Math.min(estimatedHeight,
                (int) (context.getResources().getDisplayMetrics().heightPixels * 0.52f));

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(density, 8), dp(density, 8), dp(density, 8), dp(density, 8));

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PopupWindow popup = new PopupWindow(scroll, width, height, true);
        GradientDrawable popupCard = new GradientDrawable();
        popupCard.setColor(0xFF25252B);
        popupCard.setCornerRadius(dp(density, 16));
        popupCard.setStroke(Math.max(1, dp(density, 1)), 0x30FFFFFF);
        popup.setBackgroundDrawable(popupCard);
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(density, 14));
        scroll.setFocusableInTouchMode(true);
        scroll.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                Motion.exitCardThen(scroll, popup::isShowing, popup::dismiss);
                return true;
            }
            return false;
        });

        for (String choice : choices) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(density, 14), dp(density, 12), dp(density, 10), dp(density, 12));
            row.setBackground(optionRipple(density));
            TextView label = new TextView(context);
            label.setText(choice);
            label.setTextSize(16f);
            label.setTextColor(choice.equals(selected) ? PanelDialog.COL_ACCENT : PanelDialog.COL_TITLE);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (choice.equals(selected)) {
                ImageView check = new ImageView(context);
                check.setImageDrawable(new ActionIconDrawable(ActionIconDrawable.Kind.CHECK,
                        PanelDialog.COL_ACCENT, density));
                row.addView(check, new LinearLayout.LayoutParams(dp(density, 30), dp(density, 30)));
            }
            row.setOnClickListener(v -> {
                // Keep original ordering: dismissal strictly before the pick's side effects.
                Motion.exitCardThen(scroll, popup::isShowing,
                        () -> {
                            popup.dismiss();
                            if (onPick != null) onPick.accept(choice);
                        });
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        popup.showAsDropDown(anchor, 0, dp(density, 6));
        scroll.requestFocus();
        Motion.enterCard(scroll);
    }

    private static int dp(float density, int value) {
        return (int) (density * value + 0.5f);
    }

    private static Drawable optionRipple(float density) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(0x00000000);
        content.setCornerRadius(dp(density, 12));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(density, 12));
        return new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), content, mask);
    }
}
