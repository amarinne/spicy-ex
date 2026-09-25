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
        // onBackPressed covers the gesture-back path too, where no KEYCODE_BACK is delivered
        // and the platform default would drop the card without its exit animation.
        this.dialog = new Dialog(context) {
            @Override
            public void onBackPressed() {
                PanelDialog.this.dismiss();
            }
        };
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
    /** A filtered list keeps one height while its rows come and go, instead of jumping. */
    private boolean fixedHeight;

    /** Keeps one height (90% of the screen) whatever the content, for lists that change size. */
    public PanelDialog tall() {
        fixedHeight = true;
        return this;
    }

    /** A view pinned between the title and the scrolling body (tabs, filters). */
    public PanelDialog pinned(View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        root.addView(view, root.indexOfChild(scroll), lp);
        return this;
    }

    /** The body's scroller, to reset its position after the content is swapped. */
    public ScrollView scroller() {
        return scroll;
    }

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

    /** One option in a list or confirming selector. */
    public static final class Option {
        public final String value;
        public final String label;
        /** Appended to the label: usage figures, unavailability reasons. */
        public String suffix = "";
        /** Trailing glyph preview for symbol-valued options. */
        public String preview = "";
        /** Leading icon replacing the radio dot and text label (liked-songs style). */
        public ActionIconDrawable.Kind icon;
        /** Dimmed and not tappable, with the reason carried in {@link #suffix}. */
        public boolean unavailable;
        /** Smaller second line under the label (a language's own name, for instance). */
        public String detail = "";
        /** Extra words a search matches besides the label and detail. */
        public String keywords = "";

        private Option(String value, String label) {
            this.value = value == null ? "" : value;
            this.label = label == null ? "" : label;
        }

        public static Option of(String value, String label) {
            return new Option(value, label);
        }
    }

    private static final class OptionRow {
        final LinearLayout row;
        final ImageView dot;
        final TextView label;
        final ImageView icon;
        final Option option;

        OptionRow(LinearLayout row, ImageView dot, TextView label, ImageView icon, Option option) {
            this.row = row;
            this.dot = dot;
            this.label = label;
            this.icon = icon;
            this.option = option;
        }
    }

    /** Small header affordance, e.g. the cache-size help action. */
    public PanelDialog headerAction(ActionIconDrawable.Kind icon, String contentDescription,
                                    final Runnable action) {
        ImageButton button = iconButton(icon, contentDescription, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(38), dp(38));
        params.leftMargin = dp(4);
        header.addView(button, params);
        return this;
    }

    /**
     * Immediate list selector: tapping a row runs {@code onPick} at once, then animates out
     * and dismisses. The returned runnable runs after dismissal (rebuilds, refreshes); null
     * means tapping the already-applied value, which dismisses as a no-op.
     */
    public PanelDialog listOptions(java.util.List<Option> options, String selectedValue,
                                   final java.util.function.Function<String, Runnable> onPick) {
        if (options == null) return this;
        for (final Option option : options) {
            final boolean selected = option.value.equals(selectedValue);
            add(optionRowView(option, selected, option.unavailable ? null : () -> {
                Runnable after = onPick == null ? null : onPick.apply(option.value);
                dismissThen(after);
            }));
        }
        return this;
    }

    /**
     * Confirming selector: tapping a row only moves the pending highlight. Save commits the
     * pending value; Cancel, back, and outside-tap discard it. Nothing ever writes on dismiss.
     *
     * <p>Save stays disabled until the pending highlight actually differs from the stored
     * value. Without this, an exploratory tap (pending armed, invisible) followed by
     * Save-as-close silently commits a change the user never meant to make — the
     * "I opened the language picker and the UI switched language on its own" defect.
     */
    public PanelDialog confirmingOptions(java.util.List<Option> options, String initialValue,
                                         final java.util.function.Consumer<String> onSave,
                                         String saveLabel, String cancelLabel) {
        if (options == null) return this;
        final ConfirmingSelection selection = new ConfirmingSelection(initialValue);
        final java.util.List<OptionRow> rows = new java.util.ArrayList<>();
        final TextView save = button(saveLabel, true, () -> {
            if (onSave != null) onSave.accept(selection.pending());
        });
        for (final Option option : options) {
            final OptionRow built = optionRow(option);
            rows.add(built);
            paintOptionHighlight(built, selection.isHighlighted(option.value));
            if (!option.unavailable) {
                built.row.setOnClickListener(v -> {
                    selection.select(option.value);
                    for (OptionRow other : rows) {
                        paintOptionHighlight(other, selection.isHighlighted(other.option.value));
                    }
                    applySaveArmed(save, selection);
                });
            }
            add(built.row);
        }
        applySaveArmed(save, selection);
        root.addView(save, matchWrap(8));
        secondary(cancelLabel, null);
        return this;
    }

    /**
     * A long confirming selector made findable: a search field pinned above the list, the
     * {@code pinned} values (the current one, the phone's languages) first under their own
     * caption, then every option in the order given. Typing hides the captions and pinned
     * rows and narrows the full list to options whose label, detail, value or keywords
     * contain every typed word. Commits like {@link #confirmingOptions}: Save only.
     */
    public PanelDialog searchableOptions(java.util.List<Option> options, java.util.List<String> pinned,
                                         String initialValue,
                                         final java.util.function.Consumer<String> onSave,
                                         String saveLabel, String cancelLabel, String searchHint,
                                         String pinnedTitle, String allTitle, String emptyText) {
        if (options == null) return this;
        fixedHeight = true;
        final ConfirmingSelection selection = new ConfirmingSelection(initialValue);
        final java.util.List<OptionRow> rows = new java.util.ArrayList<>();
        final TextView save = button(saveLabel, true, () -> {
            if (onSave != null) onSave.accept(selection.pending());
        });
        final Runnable repaint = () -> {
            for (OptionRow other : rows) {
                paintOptionHighlight(other, selection.isHighlighted(other.option.value));
            }
            applySaveArmed(save, selection);
        };

        // The search field sits between the title and the list, so it never scrolls away.
        LinearLayout search = new LinearLayout(context);
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(12), 0, dp(4), 0);
        search.setBackground(rounded(COL_FIELD, COL_CARD_BORDER, 16));
        ImageView glass = new ImageView(context);
        glass.setImageDrawable(new ActionIconDrawable(ActionIconDrawable.Kind.SEARCH, COL_SUMMARY, density()));
        glass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams glassLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        glassLp.rightMargin = dp(8);
        search.addView(glass, glassLp);
        final EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setHint(searchHint);
        field.setTextColor(COL_TITLE);
        field.setHintTextColor(COL_SUMMARY);
        field.setTextSize(15f);
        field.setBackground(null);
        field.setPadding(0, dp(11), 0, dp(11));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard(field);
            return true;
        });
        search.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final ImageButton clear = iconButton(ActionIconDrawable.Kind.CLOSE, cancelLabel, () -> field.setText(""));
        clear.setBackground(null);
        clear.setPadding(dp(8), dp(8), dp(8), dp(8));
        clear.setVisibility(View.GONE);
        search.addView(clear, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(8);
        root.addView(search, root.indexOfChild(scroll), searchLp);

        final java.util.Map<String, Option> byValue = new java.util.HashMap<>();
        for (Option option : options) byValue.put(option.value, option);
        final java.util.List<View> browseOnly = new java.util.ArrayList<>();
        java.util.List<Option> top = new java.util.ArrayList<>();
        if (pinned != null) {
            for (String value : pinned) {
                Option option = byValue.get(value);
                if (option != null && !top.contains(option)) top.add(option);
            }
        }
        if (!top.isEmpty()) {
            TextView caption = caption(pinnedTitle);
            browseOnly.add(caption);
            body.addView(caption);
            for (Option option : top) {
                OptionRow built = selectableRow(option, selection, rows, repaint);
                browseOnly.add(built.row);
                add(built.row);
            }
            TextView all = caption(allTitle);
            browseOnly.add(all);
            body.addView(all);
        }
        final java.util.List<OptionRow> listed = new java.util.ArrayList<>();
        for (Option option : options) listed.add(selectableRow(option, selection, rows, repaint));
        for (OptionRow built : listed) add(built.row);
        final TextView empty = text(emptyText, 14f, COL_SUMMARY);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(12), dp(28), dp(12), dp(28));
        empty.setVisibility(View.GONE);
        body.addView(empty);

        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) {
                String[] words = foldForSearch(text.toString()).trim().split("\\s+");
                boolean browsing = words.length == 1 && words[0].isEmpty();
                clear.setVisibility(browsing ? View.GONE : View.VISIBLE);
                for (View view : browseOnly) view.setVisibility(browsing ? View.VISIBLE : View.GONE);
                int shown = 0;
                for (OptionRow built : listed) {
                    boolean match = browsing || matchesAll(built.option, words);
                    built.row.setVisibility(match ? View.VISIBLE : View.GONE);
                    if (match) shown++;
                }
                empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
                scroll.scrollTo(0, 0);
            }
        });

        repaint.run();
        root.addView(save, matchWrap(8));
        secondary(cancelLabel, null);
        return this;
    }

    private OptionRow selectableRow(final Option option, final ConfirmingSelection selection,
                                    java.util.List<OptionRow> rows, final Runnable repaint) {
        OptionRow built = optionRow(option);
        rows.add(built);
        if (!option.unavailable) {
            built.row.setOnClickListener(v -> {
                hideKeyboard(v);
                selection.select(option.value);
                repaint.run();
            });
        }
        return built;
    }

    private void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        root.requestFocus();
    }

    private TextView caption(String label) {
        TextView view = text(label == null ? "" : label, 13f, COL_SUMMARY);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(8), dp(10), dp(8), dp(4));
        return view;
    }

    /** Every typed word appears somewhere in the option's label, detail, value or keywords. */
    static boolean matchesAll(Option option, String[] words) {
        String haystack = foldForSearch(option.label + " " + option.detail + " "
                + option.value + " " + option.keywords);
        for (String word : words) {
            if (!word.isEmpty() && !haystack.contains(word)) return false;
        }
        return true;
    }

    /** Lower case without accents, so "espanol" finds "Español". */
    static String foldForSearch(String value) {
        if (value == null) return "";
        String decomposed = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Save is an armed action: enabled only while pending differs from stored. */
    private static void applySaveArmed(TextView save, ConfirmingSelection selection) {
        boolean dirty = selection.isDirty();
        save.setEnabled(dirty);
        save.setAlpha(dirty ? 1f : 0.45f);
    }

    /**
     * Pending-selection state for one confirming dialog. A tiny named object instead of
     * parallel holder arrays: it is mutated, never reassigned, so every listener closes
     * over plain finals. The dirty rule is pure and unit-covered.
     */
    static final class ConfirmingSelection {
        private final String initial;
        private String pending;

        ConfirmingSelection(String initial) {
            this.initial = initial;
            this.pending = initial;
        }

        void select(String value) {
            pending = value;
        }

        String pending() {
            return pending;
        }

        boolean isHighlighted(String optionValue) {
            return optionValue != null && optionValue.equals(pending);
        }

        boolean isDirty() {
            return pending != null && !pending.equals(initial);
        }
    }

    /** Static label/value pair for info dialogs (cache details, diagnostics). */
    public PanelDialog infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(9), dp(14), dp(9));
        TextView title = text(label == null ? "" : label, 14f, COL_SUMMARY);
        row.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView detail = text(value == null ? "" : value, 14f, COL_TITLE);
        detail.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(detail, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        add(row);
        return this;
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
        boolean constrained = fixedHeight || root.getMeasuredHeight() > maxHeight;
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

    /** The single option-row vocabulary: radio dot, label + suffix, optional icon/preview. */
    private LinearLayout optionRowView(Option option, boolean highlighted, Runnable onTap) {
        OptionRow built = optionRow(option);
        paintOptionHighlight(built, highlighted);
        if (onTap != null) built.row.setOnClickListener(v -> onTap.run());
        return built.row;
    }

    private OptionRow optionRow(Option option) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(22), dp(8), dp(22), dp(8));
        row.setBackground(ripple(rounded(0x00000000, 0x00000000, 14)));
        row.setFocusable(true);

        ImageView dot = new ImageView(context);
        dot.setPadding(dp(4), dp(4), dp(4), dp(4));
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        dotParams.rightMargin = dp(12);
        row.addView(dot, dotParams);

        ImageView icon = null;
        TextView label;
        if (option.icon != null) {
            icon = new ImageView(context);
            icon.setImageDrawable(new ActionIconDrawable(option.icon, COL_TITLE, density()));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
            row.setContentDescription(option.label + option.suffix);
            label = null;
        } else {
            label = text(option.label + option.suffix, 16f, COL_TITLE);
            if (option.detail.isEmpty()) {
                row.addView(label, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            } else {
                LinearLayout texts = new LinearLayout(context);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(label);
                TextView detail = text(option.detail, 13f, COL_SUMMARY);
                detail.setSingleLine(true);
                detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                texts.addView(detail);
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
        if (!option.preview.isEmpty()) {
            TextView preview = text(option.preview, 18f, COL_TITLE);
            preview.setPadding(dp(12), 0, 0, 0);
            row.addView(preview);
        }
        row.setEnabled(!option.unavailable);
        row.setAlpha(option.unavailable ? 0.48f : 1f);
        return new OptionRow(row, dot, label, icon, option);
    }

    private void paintOptionHighlight(OptionRow built, boolean highlighted) {
        built.dot.setImageDrawable(new ActionIconDrawable(ActionIconDrawable.Kind.CIRCLE,
                highlighted ? COL_ACCENT : COL_SUMMARY, density(), highlighted));
        if (built.label != null) {
            built.label.setTextColor(highlighted ? COL_ACCENT : COL_TITLE);
        }
        if (built.icon != null) {
            built.icon.setImageDrawable(new ActionIconDrawable(built.option.icon,
                    highlighted ? COL_ACCENT : COL_TITLE, density()));
        }
        built.row.setSelected(highlighted);
    }

    private float density() {
        return context.getResources().getDisplayMetrics().density;
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
