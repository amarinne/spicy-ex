package com.eza.spicyex.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A dialog that looks like the settings panel it was opened from.
 *
 * <p>Android's default dialog is a light-themed box with its own typography, and it lands on top of
 * a dark panel looking like a different application. Everything here exists to avoid that: the same
 * card colour, corner radius, accent, and button shapes the panel uses.
 *
 * <p>Built in code rather than XML because this UI is injected into Spotify's process, where our
 * layout resources are not reliably resolvable from the host's context.
 */
public final class PanelDialog {

    public static final int COL_CARD = 0xFA1C1C22;
    public static final int COL_CARD_BORDER = 0x30FFFFFF;
    public static final int COL_TITLE = 0xFFFFFFFF;
    public static final int COL_SUMMARY = 0xA6FFFFFF;
    public static final int COL_ACCENT = 0xFF1ED760;
    public static final int COL_FIELD = 0x0DFFFFFF;

    private final Context context;
    private final Dialog dialog;
    private final LinearLayout body;
    private final LinearLayout header;
    private final ScrollView scroll;
    private ImageButton closeButton;

    public PanelDialog(Context context, String title) {
        this.context = context;
        this.dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(16));
        root.setBackground(rounded(COL_CARD, COL_CARD_BORDER, 26));

        header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text(title, 22f, COL_TITLE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, matchWrap(12));

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, matchWrap(0));

        this.root = root;
        dialog.setContentView(root);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                dismiss();
                return true;
            }
            return false;
        });
    }

    private final LinearLayout root;

    /** Adds a view to the dialog body. */
    public PanelDialog add(View view) {
        body.addView(view, matchWrap(8));
        return this;
    }

    /** Disclosure copy for consent and other irreversible choices. */
    public PanelDialog paragraph(String value) {
        TextView view = text(value, 14f, COL_SUMMARY);
        view.setLineSpacing(dp(3), 1f);
        add(view);
        return this;
    }

    /** Selectable monospace payload block for request/output monitoring. */
    public TextView readOnlyBlock(String value) {
        TextView view = text(value == null ? "" : value, 12f, COL_TITLE);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setHorizontallyScrolling(false);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackground(rounded(COL_FIELD, COL_CARD_BORDER, 14));
        add(view);
        return view;
    }

    /**
     * A titled block that opens on tap and starts folded.
     *
     * <p>For content that is worth keeping but is not what the panel is for — long, and interesting
     * only when something looks wrong. Folded, it costs one row; the panel still leads with its
     * answer and its actions instead of opening on a wall of monospace.
     *
     * @return the body view, so a caller that refreshes live text can set it without re-adding
     */
    public TextView collapsible(String title, String value) {
        final TextView content = text(value == null ? "" : value, 12f, COL_TITLE);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setHorizontallyScrolling(false);
        content.setPadding(dp(12), dp(12), dp(12), dp(12));
        content.setBackground(rounded(COL_FIELD, COL_CARD_BORDER, 14));
        content.setVisibility(View.GONE);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(10), dp(11));
        row.setBackground(ripple(rounded(COL_FIELD, COL_CARD_BORDER, 14)));

        TextView heading = text(title, 14f, COL_TITLE);
        row.addView(heading, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final ImageView chevron = new ImageView(context);
        chevron.setPadding(dp(7), dp(7), dp(7), dp(7));
        chevron.setImageDrawable(disclosure(false));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(32), dp(36)));

        row.setOnClickListener(v -> {
            boolean expanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            chevron.setImageDrawable(disclosure(expanded));
            applyWindowSize();
        });

        add(row);
        body.addView(content, matchWrap(8));
        return content;
    }

    private Drawable disclosure(boolean expanded) {
        return new ActionIconDrawable(
                expanded ? ActionIconDrawable.Kind.CHEVRON_DOWN
                        : ActionIconDrawable.Kind.CHEVRON_RIGHT,
                COL_SUMMARY, context.getResources().getDisplayMetrics().density);
    }

    /** Optional top-right close affordance, matching the injected settings panel header. */
    public PanelDialog closeIcon(String contentDescription) {
        if (closeButton != null) return this;
        closeButton = iconButton(ActionIconDrawable.Kind.CLOSE, contentDescription, this::dismiss);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(36));
        params.leftMargin = dp(4);
        header.addView(closeButton, params);
        return this;
    }

    /** Prevent screenshots/recording while this transient dialog contains a credential. */
    public PanelDialog secure() {
        Window window = dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        return this;
    }

    /** Visible credential text with no accessibility, autofill, selection, or clipboard surface. */
    public TextView secretValue(String value) {
        TextView view = text(value, 15f, COL_TITLE);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setBackground(rounded(COL_FIELD, COL_CARD_BORDER, 14));
        view.setTextIsSelectable(false);
        view.setLongClickable(false);
        view.setFilterTouchesWhenObscured(true);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        add(view);
        return view;
    }

    /** Compact dropdown row. The caller owns fetching choices and opening the picker. */
    public TextView selector(String label, String value, String contentDescription,
                             final Runnable onPick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(10), dp(11));
        row.setBackground(ripple(rounded(COL_FIELD, COL_CARD_BORDER, 14)));
        row.setContentDescription(contentDescription);
        row.setOnClickListener(v -> { if (onPick != null) onPick.run(); });

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(label, 12f, COL_SUMMARY);
        TextView selected = text(value, 16f, COL_ACCENT);
        selected.setPadding(0, dp(2), 0, 0);
        selected.setTag(row);
        labels.addView(title);
        labels.addView(selected);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = new ImageView(context);
        arrow.setPadding(dp(7), dp(7), dp(7), dp(7));
        arrow.setImageDrawable(new ActionIconDrawable(ActionIconDrawable.Kind.CHEVRON_DOWN,
                COL_SUMMARY, context.getResources().getDisplayMetrics().density));
        row.addView(arrow, new LinearLayout.LayoutParams(dp(32), dp(36)));
        add(row);
        return selected;
    }

    /** Full selector row used as the anchor for a same-width dropdown. */
    public View selectorAnchor(TextView selected) {
        Object tagged = selected == null ? null : selected.getTag();
        return tagged instanceof View ? (View) tagged : selected;
    }

    /** Compact action inside a selector row, before its dropdown chevron. */
    public View selectorAction(TextView selected, ActionIconDrawable.Kind icon,
                               String contentDescription, final Runnable action) {
        View anchor = selectorAnchor(selected);
        if (!(anchor instanceof LinearLayout)) return anchor;
        LinearLayout row = (LinearLayout) anchor;
        ImageButton button = iconButton(icon, contentDescription, action);
        button.setBackground(ripple(rounded(0x00000000, 0x00000000, 18)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(36));
        params.leftMargin = dp(4);
        int chevronIndex = Math.max(0, row.getChildCount() - 1);
        row.addView(button, chevronIndex, params);
        return button;
    }

    /** End-aligned icon row for edit/save actions that should not become full-width text buttons. */
    public LinearLayout iconActions() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        add(row);
        return row;
    }

    public View iconAction(LinearLayout row, ActionIconDrawable.Kind icon,
                           String contentDescription, final Runnable action) {
        ImageButton button = iconButton(icon, contentDescription, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(44), dp(44));
        params.leftMargin = dp(6);
        row.addView(button, params);
        return button;
    }

    /** A text field styled like the panel's own inputs. */
    public EditText field(boolean secret, String initial) {
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setText(initial == null ? "" : initial);
        field.setTextColor(COL_TITLE);
        field.setHintTextColor(COL_SUMMARY);
        field.setTextSize(15f);
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setBackground(rounded(COL_FIELD, COL_CARD_BORDER, 14));
        if (secret) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            // Autofill would offer to remember it somewhere we do not control.
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        }
        add(field);
        return field;
    }

    /** Multiline steering field used by the AI request composer. */
    public EditText multilineField(String initial) {
        EditText field = new EditText(context);
        field.setMinLines(4);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setText(initial == null ? "" : initial);
        field.setTextColor(COL_TITLE);
        field.setHintTextColor(COL_SUMMARY);
        field.setTextSize(15f);
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setBackground(rounded(COL_FIELD, COL_CARD_BORDER, 14));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        add(field);
        return field;
    }

    public static final class CheckChoice {
        private final PanelDialog owner;
        private final LinearLayout row;
        private final ImageView indicator;
        private boolean checked;

        CheckChoice(PanelDialog owner, LinearLayout row, ImageView indicator, boolean checked) {
            this.owner = owner;
            this.row = row;
            this.indicator = indicator;
            setChecked(checked);
        }

        public boolean isChecked() {
            return checked;
        }

        private void toggle() {
            setChecked(!checked);
        }

        private void setChecked(boolean value) {
            checked = value;
            indicator.setBackground(owner.rounded(value ? COL_ACCENT : 0x00000000,
                    value ? COL_ACCENT : COL_SUMMARY, 7));
            indicator.setImageDrawable(value
                    ? new ActionIconDrawable(ActionIconDrawable.Kind.CHECK, 0xFF101014,
                    owner.context.getResources().getDisplayMetrics().density)
                    : null);
            row.setSelected(value);
        }
    }

    /** Host-styled tick row; avoids the platform checkbox leaking another app theme. */
    public CheckChoice checkbox(String label, boolean checked) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackground(ripple(rounded(COL_FIELD, COL_CARD_BORDER, 14)));

        ImageView indicator = new ImageView(context);
        indicator.setPadding(dp(3), dp(3), dp(3), dp(3));
        row.addView(indicator, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView text = text(label, 15f, COL_TITLE);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(12);
        row.addView(text, textParams);

        CheckChoice choice = new CheckChoice(this, row, indicator, checked);
        row.setOnClickListener(v -> choice.toggle());
        add(row);
        return choice;
    }

    /** A tappable row, used where a dialog is a list of choices. */
    public TextView option(String label, boolean selected, final Runnable onPick) {
        TextView view = text(label, 16f, selected ? COL_ACCENT : COL_TITLE);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setBackground(ripple(rounded(0x00000000, 0x00000000, 14)));
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismissThen(onPick);
            }
        });
        add(view);
        return view;
    }

    /** A choice row that leaves the composer open after applying its value. */
    public TextView optionKeepOpen(String label, final Runnable onPick) {
        TextView view = text(label, 14f, COL_SUMMARY);
        view.setPadding(dp(14), dp(9), dp(14), dp(9));
        view.setBackground(ripple(rounded(0x00000000, 0x00000000, 14)));
        view.setOnClickListener(v -> { if (onPick != null) onPick.run(); });
        add(view);
        return view;
    }

    /** The confirming action. Accent-filled, like the panel's primary buttons. */
    public PanelDialog primary(String label, final Runnable action) {
        root.addView(button(label, true, action), matchWrap(8));
        return this;
    }

    /** The dismissing action. */
    public PanelDialog secondary(String label, final Runnable action) {
        root.addView(button(label, false, action), matchWrap(0));
        return this;
    }

    public void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        window.setDimAmount(0.62f);
        applyWindowSize();
        Motion.enterCard(root);
    }

    /**
     * Fits the card to its content: wrap while it fits, scroll once it does not.
     *
     * <p>Re-run whenever the body's height changes rather than only at {@link #show()}, because a
     * dialog that wrapped when it opened has no scroll weight, and a section unfolded afterwards
     * would push its own end off the bottom of a window sized for the folded card.
     */
    private void applyWindowSize() {
        Window window = dialog.getWindow();
        if (window == null) return;
        int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
        int maxHeight = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.90f);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        boolean constrained = root.getMeasuredHeight() > maxHeight;
        LinearLayout.LayoutParams scrollParams = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        scrollParams.height = constrained ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        scrollParams.weight = constrained ? 1f : 0f;
        scroll.setLayoutParams(scrollParams);
        window.setLayout(width, constrained ? maxHeight : ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public PanelDialog onDismiss(final Runnable callback) {
        dialog.setOnDismissListener(ignored -> {
            if (callback != null) callback.run();
        });
        return this;
    }

    /** Animated dismissal; ordering-sensitive callers pass work via {@link #onDismiss}. */
    public void dismiss() {
        dismissThen(null);
    }

    private void dismissThen(Runnable action) {
        Motion.exitCardThen(root, dialog::isShowing, () -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
    }

    private TextView button(String label, boolean accent, final Runnable action) {
        TextView view = text(label, 16f, accent ? 0xFF101014 : COL_TITLE);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), dp(13), dp(16), dp(13));
        view.setBackground(ripple(rounded(accent ? COL_ACCENT : COL_FIELD,
                accent ? COL_ACCENT : COL_CARD_BORDER, 22)));
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismissThen(action);
            }
        });
        return view;
    }

    private ImageButton iconButton(ActionIconDrawable.Kind icon, String contentDescription,
                                   final Runnable action) {
        ImageButton view = new ImageButton(context);
        view.setPadding(dp(9), dp(9), dp(9), dp(9));
        view.setImageDrawable(new ActionIconDrawable(icon, COL_TITLE,
                context.getResources().getDisplayMetrics().density));
        view.setContentDescription(contentDescription);
        view.setTooltipText(contentDescription);
        view.setBackground(ripple(rounded(COL_FIELD, COL_CARD_BORDER, 18)));
        view.setOnClickListener(v -> { if (action != null) action.run(); });
        return view;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int fill, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (border != 0) drawable.setStroke(Math.max(1, dp(1) / 2), border);
        return drawable;
    }

    private static RippleDrawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), content, null);
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(bottomMarginDp);
        return params;
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
