package com.eza.spicyex.diagnostics;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.eza.spicyex.BuildConfig;
import com.eza.spicyex.GlossyToggle;
import com.eza.spicyex.R;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SettingsUiStrings;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** User-triggered reporting flow hosted entirely inside Spotify's existing settings dialog. */
public final class DiagnosticReportingDialog {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int COL_CARD = 0xFA1C1C22;
    private static final int COL_CARD_BORDER = 0x30FFFFFF;
    private static final int COL_TITLE = 0xFFFFFFFF;
    private static final int COL_SUMMARY = 0xA6FFFFFF;
    private static final int COL_ACCENT = 0xFF1ED760;
    private static final String[] CATEGORY_WIRES = {
            "missing_wrong_lyrics", "timing", "translation", "transliteration_romanization",
            "fullscreen_renderer", "now_playing_card", "hyperglow_bridge", "crash_restart", "other"
    };
    private static final int[] CATEGORY_LABELS = {
            R.string.diagnostic_category_missing_wrong,
            R.string.diagnostic_category_timing,
            R.string.diagnostic_category_translation,
            R.string.diagnostic_category_transliteration,
            R.string.diagnostic_category_fullscreen,
            R.string.diagnostic_category_now_playing,
            R.string.diagnostic_category_hyperglow,
            R.string.diagnostic_category_crash,
            R.string.diagnostic_category_other
    };

    private DiagnosticReportingDialog() {
    }

    public static String reportProblemLabel(Context context, SettingsStore settings) {
        return strings(context, settings).resource(
                R.string.diagnostic_report_problem, "Report a problem");
    }

    public static void show(Context context, SettingsStore settings) {
        SettingsUiStrings strings = strings(context, settings);
        SpicyDiagnosticReportFactory.Draft draft = DiagnosticDraftStore.load(context);
        if (draft != null) {
            showPreview(context, settings, strings, draft);
            return;
        }
        DiagnosticCaptureStore.CaptureState capture = DiagnosticCaptureStore.state(context);
        if (capture.active) showActiveCapture(context, settings, strings, capture);
        else showNewReport(context, settings, strings);
    }

    private static void showNewReport(Context context, SettingsStore settings,
                                      SettingsUiStrings strings) {
        LinearLayout box = vertical(context);
        box.addView(setupChecklist(context, strings,
                SpicySetupCheckCollector.collect(context, settings)),
                matchWrapWithBottom(context, 14));
        box.addView(sectionLabel(context, text(strings, R.string.diagnostic_category, "Category")));
        String[] categoryLabels = new String[CATEGORY_LABELS.length];
        for (int i = 0; i < categoryLabels.length; i++) {
            categoryLabels[i] = text(strings, CATEGORY_LABELS[i], CATEGORY_WIRES[i]);
        }
        int[] selectedCategory = {0};
        TextView categoryChoice = selector(context, categoryLabels[0]);
        categoryChoice.setOnClickListener(v -> showCategoryPicker(
                context, strings, categoryLabels, selectedCategory[0], index -> {
                    selectedCategory[0] = index;
                    categoryChoice.setText(categoryLabels[index] + "  ›");
                }));
        box.addView(categoryChoice, matchWrapWithBottom(context, 12));

        FrameLayout descriptionBox = new FrameLayout(context);
        EditText description = new EditText(context);
        description.setHint(text(strings, R.string.diagnostic_description_hint,
                "Describe what happened"));
        description.setHintTextColor(COL_SUMMARY);
        description.setTextColor(COL_TITLE);
        description.setTextSize(16f);
        description.setMinLines(4);
        description.setMaxLines(8);
        description.setMinHeight(dp(context, 168));
        description.setSingleLine(false);
        description.setGravity(Gravity.TOP | Gravity.START);
        description.setPadding(dp(context, 14), dp(context, 12),
                dp(context, 14), dp(context, 34));
        description.setBackground(rounded(0x0DFFFFFF, COL_CARD_BORDER, 14, context));
        TextView descriptionCount = bodyText(context, strings.format(
                "diagnostic_description_count", "%1$d/%2$d",
                0, DiagnosticReportContract.DESCRIPTION_BYTES));
        descriptionCount.setTextColor(COL_SUMMARY);
        descriptionCount.setTextSize(12f);
        FrameLayout.LayoutParams countParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.BOTTOM);
        countParams.setMargins(0, 0, dp(context, 12), dp(context, 10));
        descriptionBox.addView(description, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        descriptionBox.addView(descriptionCount, countParams);
        description.addTextChangedListener(new TextWatcher() {
            private boolean changing;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                if (changing) return;
                int bytes = DiagnosticReportContract.utf8Bytes(editable.toString());
                if (bytes > DiagnosticReportContract.DESCRIPTION_BYTES) {
                    changing = true;
                    editable.replace(0, editable.length(), DiagnosticReportContract.utf8Prefix(
                            editable.toString(), DiagnosticReportContract.DESCRIPTION_BYTES));
                    changing = false;
                    bytes = DiagnosticReportContract.utf8Bytes(editable.toString());
                }
                descriptionCount.setText(strings.format(
                        "diagnostic_description_count", "%1$d/%2$d",
                        bytes, DiagnosticReportContract.DESCRIPTION_BYTES));
            }
        });
        box.addView(descriptionBox, matchWrap());

        PanelDialog dialog = new PanelDialog(
                context,
                text(strings, R.string.diagnostic_report_problem, "Report a problem"),
                box,
                text(strings, R.string.diagnostic_start_capture, "Start capture"),
                text(strings, R.string.diagnostic_preview_now, "Preview"),
                text(strings, android.R.string.cancel, "Cancel"),
                true
        );
        dialog.negativeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.positiveButton.setOnClickListener(v -> {
            String body = description.getText().toString().trim();
            if (!DiagnosticReportContract.validDescription(body)) {
                description.setError(text(strings, R.string.diagnostic_description_required,
                        "A description is required."));
                return;
            }
            DiagnosticCaptureStore.CaptureState state = DiagnosticCaptureStore.start(
                    context, DiagnosticReportContract.newReportId(),
                    CATEGORY_WIRES[selectedCategory[0]], body);
            if (!state.active) {
                toast(context, text(strings, R.string.diagnostic_capture_failed,
                        "Could not start capture."));
                return;
            }
            dialog.dismiss();
            toast(context, text(strings, R.string.diagnostic_capture_started,
                    "Capture started. Reproduce, then reopen About & Diagnostics."));
        });
        dialog.neutralButton.setOnClickListener(v -> {
            String body = description.getText().toString().trim();
            if (!DiagnosticReportContract.validDescription(body)) {
                description.setError(text(strings, R.string.diagnostic_description_required,
                        "A description is required."));
                return;
            }
            try {
                SpicyDiagnosticReportFactory.Draft draft =
                        SpicyDiagnosticReportFactory.createMetadataOnly(
                                context, settings, CATEGORY_WIRES[selectedCategory[0]], body);
                if (!DiagnosticDraftStore.save(context, draft)) throw new IllegalStateException();
                dialog.dismiss();
                showPreview(context, settings, strings, draft);
            } catch (Throwable ignoredError) {
                toast(context, text(strings, R.string.diagnostic_prepare_failed,
                        "Could not prepare the report."));
            }
        });
        dialog.show();
    }

    private static void showCategoryPicker(Context context, SettingsUiStrings strings,
                                           String[] labels, int selected,
                                           java.util.function.IntConsumer onSelected) {
        LinearLayout options = vertical(context);
        PanelDialog picker = new PanelDialog(
                context,
                text(strings, R.string.diagnostic_category, "Category"),
                options,
                null,
                null,
                text(strings, R.string.diagnostic_close, "Close"),
                true
        );
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView option = optionRow(context, (i == selected ? "●  " : "○  ") + labels[i]);
            option.setTextColor(i == selected ? COL_ACCENT : COL_TITLE);
            option.setOnClickListener(v -> {
                onSelected.accept(index);
                picker.dismiss();
            });
            options.addView(option, matchWrapWithBottom(context, 4));
        }
        picker.negativeButton.setOnClickListener(v -> picker.dismiss());
        picker.show();
    }

    private static void showActiveCapture(Context context, SettingsStore settings,
                                          SettingsUiStrings strings,
                                          DiagnosticCaptureStore.CaptureState state) {
        String message = text(strings, R.string.diagnostic_capture_active,
                "Reproduce the problem, then finish here.");
        if (state.interrupted) {
            message += "\n\n" + text(strings, R.string.diagnostic_capture_interrupted,
                    "Capture file missing after restart; report marked interrupted.");
        }
        TextView body = bodyText(context, message);
        PanelDialog dialog = new PanelDialog(
                context,
                text(strings, R.string.diagnostic_capture_title, "Diagnostic capture"),
                body,
                text(strings, R.string.diagnostic_finish_capture, "Finish capture"),
                text(strings, R.string.diagnostic_cancel_capture, "Cancel capture"),
                text(strings, R.string.diagnostic_close, "Close"),
                false
        );
        dialog.negativeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.positiveButton.setOnClickListener(v -> {
            try {
                DiagnosticCaptureStore.FinishedCapture capture = DiagnosticCaptureStore.finish(context);
                SpicyDiagnosticReportFactory.Draft draft =
                        SpicyDiagnosticReportFactory.createCaptured(context, settings, capture);
                if (!DiagnosticDraftStore.save(context, draft)) throw new IllegalStateException();
                dialog.dismiss();
                showPreview(context, settings, strings, draft);
            } catch (Throwable ignoredError) {
                toast(context, text(strings, R.string.diagnostic_prepare_failed,
                        "Could not prepare the report."));
            }
        });
        dialog.neutralButton.setOnClickListener(v -> {
            DiagnosticCaptureStore.cancel(context);
            DiagnosticDraftStore.clear(context);
            dialog.dismiss();
            toast(context, text(strings, R.string.diagnostic_capture_cancelled, "Capture cancelled."));
        });
        dialog.show();
    }

    private static void showPreview(Context context, SettingsStore settings,
                                    SettingsUiStrings strings,
                                    SpicyDiagnosticReportFactory.Draft draft) {
        LinearLayout box = vertical(context);
        box.addView(setupChecklist(context, strings, draft.setupChecks),
                matchWrapWithBottom(context, 14));
        TextView policy = optionRow(context, text(strings, R.string.diagnostic_data_policy,
                "Diagnostic data policy ↗"));
        policy.setOnClickListener(v -> openPolicy(context, strings));
        box.addView(policy, matchWrapWithBottom(context, 10));

        LinearLayout consent = new LinearLayout(context);
        consent.setOrientation(LinearLayout.HORIZONTAL);
        consent.setGravity(Gravity.CENTER_VERTICAL);
        consent.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        consent.setBackground(ripple(rounded(0x0DFFFFFF, 0, 14, context)));
        TextView consentText = bodyText(context, text(strings, R.string.diagnostic_accept_policy,
                "I accept the policy."));
        consentText.setPadding(0, 0, dp(context, 12), 0);
        consent.addView(consentText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        GlossyToggle accept = new GlossyToggle(context);
        accept.setAccent(COL_ACCENT);
        accept.setChecked(false, false);
        consent.addView(accept);
        box.addView(consent, matchWrapWithBottom(context, 16));

        box.addView(sectionLabel(context, text(strings, R.string.diagnostic_included_json,
                "Included JSON")));
        box.addView(diagnosticJsonPreview(context, draft), matchWrap());

        PanelDialog dialog = new PanelDialog(
                context,
                text(strings, R.string.diagnostic_preview, "Report preview"),
                box,
                text(strings, R.string.diagnostic_upload, "Upload"),
                text(strings, R.string.diagnostic_discard, "Discard"),
                text(strings, R.string.diagnostic_close, "Close"),
                true
        );
        dialog.setPrimaryEnabled(false);
        accept.setOnChangeListener(() -> dialog.setPrimaryEnabled(accept.isChecked()));
        consent.setOnClickListener(v -> accept.setChecked(!accept.isChecked(), true));
        dialog.negativeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.neutralButton.setOnClickListener(v -> {
            DiagnosticDraftStore.clear(context);
            dialog.dismiss();
        });
        dialog.positiveButton.setOnClickListener(v -> {
            if (!accept.isChecked()) return;
            dialog.setPrimaryEnabled(false);
            new DiagnosticUploader().upload(BuildConfig.DIAGNOSTIC_INTAKE_URL, draft, result ->
                    MAIN.post(() -> {
                        if (result.successful()) {
                            DiagnosticDraftStore.clear(context);
                            dialog.dismiss();
                            showReceipt(context, strings, draft, result.receipt);
                        } else {
                            dialog.setPrimaryEnabled(true);
                            toast(context, uploadFailure(strings, result.failure));
                        }
                    }));
        });
        dialog.show();
    }

    private static void showReceipt(Context context, SettingsUiStrings strings,
                                    SpicyDiagnosticReportFactory.Draft draft,
                                    DiagnosticUploader.Receipt receipt) {
        LinearLayout box = vertical(context);
        TextView reportId = bodyText(context,
                text(strings, R.string.diagnostic_report_id, "Report ID") + ":\n" + receipt.reportId);
        reportId.setTextIsSelectable(true);
        reportId.setTypeface(Typeface.MONOSPACE);
        box.addView(reportId, matchWrapWithBottom(context, 12));
        TextView policy = optionRow(context, text(strings, R.string.diagnostic_data_policy,
                "Diagnostic data policy ↗"));
        policy.setOnClickListener(v -> openPolicy(context, strings));
        box.addView(policy, matchWrapWithBottom(context, 10));
        TextView viewJson = optionRow(context, text(strings, R.string.diagnostic_view_json,
                "View JSON"));
        viewJson.setOnClickListener(v -> showUploadedJson(context, strings, draft));
        box.addView(viewJson, matchWrapWithBottom(context, 10));
        TextView saveJson = optionRow(context, text(strings, R.string.diagnostic_save_json,
                "Save JSON"));
        saveJson.setOnClickListener(v -> {
            boolean saved = saveJsonToDownloads(context, draft);
            toast(context, text(
                    strings,
                    saved ? R.string.diagnostic_json_saved
                            : R.string.diagnostic_json_save_failed,
                    saved ? "Saved to Downloads/Spicy EX." : "Could not save JSON."
            ));
        });
        box.addView(saveJson, matchWrap());

        PanelDialog dialog = new PanelDialog(
                context,
                text(strings, R.string.diagnostic_uploaded, "Report uploaded"),
                box,
                text(strings, R.string.diagnostic_copy_id, "Copy ID"),
                text(strings, R.string.diagnostic_open_issue, "Open GitHub issue"),
                text(strings, R.string.diagnostic_close, "Close"),
                false
        );
        dialog.negativeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.positiveButton.setOnClickListener(v -> {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(
                    text(strings, R.string.diagnostic_clip_label, "Spicy EX report ID"),
                    receipt.reportId));
            toast(context, text(strings, R.string.diagnostic_id_copied, "Report ID copied."));
        });
        dialog.neutralButton.setOnClickListener(v -> openIssue(context, strings, draft));
        dialog.show();
    }

    private static void showUploadedJson(Context context, SettingsUiStrings strings,
                                         SpicyDiagnosticReportFactory.Draft draft) {
        PanelDialog dialog = new PanelDialog(
                context,
                text(strings, R.string.diagnostic_included_json, "Included JSON"),
                diagnosticJsonPreview(context, draft),
                null,
                null,
                text(strings, R.string.diagnostic_close, "Close"),
                true
        );
        dialog.negativeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private static View diagnosticJsonPreview(
            Context context, SpicyDiagnosticReportFactory.Draft draft) {
        DiagnosticJsonPreviewFormatter.Preview preview =
                DiagnosticJsonPreviewFormatter.format(draft.json);
        if (preview == null) {
            return diagnosticJsonCard(
                    context, SpicyDiagnosticReportFactory.prettyJson(draft.json));
        }

        LinearLayout box = vertical(context);
        box.addView(diagnosticJsonCard(context, preview.reportJson),
                matchWrapWithBottom(context, 14));
        box.addView(sectionLabel(context, "rawDiagnostics"));
        box.addView(diagnosticJsonBlock(
                        context, "diagnosticEventsAndLogs", preview.diagnosticEventsAndLogs),
                matchWrapWithBottom(context, 10));
        box.addView(diagnosticJsonBlock(context, "crashExcerpt", preview.crashExcerpt),
                matchWrapWithBottom(context, 10));
        box.addView(diagnosticJsonBlock(
                        context, "lsposedModuleLines", preview.lsposedModuleLines),
                matchWrapWithBottom(context, 10));
        box.addView(diagnosticJsonBlock(
                context, "runtimeSettings", preview.runtimeSettingsJson), matchWrap());
        return box;
    }

    private static View diagnosticJsonBlock(Context context, String label, String value) {
        LinearLayout box = vertical(context);
        box.addView(sectionLabel(context, label), matchWrap());
        box.addView(diagnosticJsonCard(context, value.isEmpty() ? "(empty)" : value), matchWrap());
        return box;
    }

    private static TextView diagnosticJsonCard(Context context, String value) {
        TextView payload = bodyText(context, value);
        payload.setTextIsSelectable(true);
        payload.setTypeface(Typeface.MONOSPACE);
        payload.setTextSize(11f);
        payload.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        payload.setBackground(rounded(0x0DFFFFFF, COL_CARD_BORDER, 14, context));
        return payload;
    }

    private static boolean saveJsonToDownloads(Context context,
                                               SpicyDiagnosticReportFactory.Draft draft) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        Uri uri = null;
        boolean saved = false;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "SpicyEX-" + draft.reportId + ".json");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Spicy EX");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = context.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    context.getContentResolver().openOutputStream(uri),
                    StandardCharsets.UTF_8)) {
                writer.write(SpicyDiagnosticReportFactory.prettyJson(draft.json));
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, ready, null, null);
            saved = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (!saved && uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {
                    // Best-effort cleanup of an incomplete export.
                }
            }
        }
    }

    private static void openIssue(Context context, SettingsUiStrings strings,
                                  SpicyDiagnosticReportFactory.Draft draft) {
        try {
            Uri uri = Uri.parse("https://github.com/amarinne/spicy-ex/issues/new").buildUpon()
                    .appendQueryParameter("title", draft.issueTitle)
                    .appendQueryParameter("body", draft.issueBody)
                    .build();
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
            toast(context, text(strings, R.string.diagnostic_open_issue_failed,
                    "Could not open GitHub."));
        }
    }

    private static void openPolicy(Context context, SettingsUiStrings strings) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(DiagnosticReportContract.DATA_POLICY_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
            toast(context, text(strings, R.string.diagnostic_open_issue_failed,
                    "Could not open GitHub."));
        }
    }

    private static String uploadFailure(SettingsUiStrings strings, DiagnosticUploader.Kind kind) {
        if (kind == null) return text(strings, R.string.diagnostic_upload_failed, "Upload failed.");
        switch (kind) {
            case INVALID_REPORT: return text(strings, R.string.diagnostic_invalid_report, "The report was rejected as invalid.");
            case REPORT_ID_COLLISION: return text(strings, R.string.diagnostic_id_collision, "The report ID collided with different data.");
            case REQUEST_TOO_LARGE: return text(strings, R.string.diagnostic_too_large, "The report is too large.");
            case RATE_LIMITED: return text(strings, R.string.diagnostic_rate_limited, "Upload rate limit reached. Retry manually later.");
            case STORAGE_UNAVAILABLE: return text(strings, R.string.diagnostic_storage_unavailable, "Private report storage is unavailable.");
            case REDIRECT_REJECTED: return text(strings, R.string.diagnostic_redirect_rejected, "The server returned a rejected redirect.");
            case SERVER_ERROR: return text(strings, R.string.diagnostic_server_error, "The intake server failed. Retry manually later.");
            case TIMEOUT: return text(strings, R.string.diagnostic_timeout, "Upload timed out. Retry manually later.");
            case NETWORK: return text(strings, R.string.diagnostic_network_error, "Network request failed or timed out.");
            default: return text(strings, R.string.diagnostic_invalid_response, "The server returned an invalid response.");
        }
    }

    private static SettingsUiStrings strings(Context context, SettingsStore settings) {
        String language = settings == null ? "system" : settings.get(Settings.UI_LANGUAGE);
        return new SettingsUiStrings(context, language);
    }

    private static String text(SettingsUiStrings strings, int id, String fallback) {
        return strings.resource(id, fallback);
    }

    private static LinearLayout vertical(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private static TextView sectionLabel(Context context, String value) {
        TextView view = bodyText(context, value);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextSize(14f);
        view.setTextColor(COL_SUMMARY);
        view.setPadding(dp(context, 2), 0, 0, dp(context, 8));
        return view;
    }

    private static TextView bodyText(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(COL_TITLE);
        view.setTextSize(15f);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    private static TextView setupChecklist(Context context, SettingsUiStrings strings,
                                           SpicySetupCheckPolicy.Result checks) {
        String state;
        if ("ready".equals(checks.setupState)) {
            state = text(strings, R.string.diagnostic_setup_ready, "Setup: Ready");
        } else if ("failed".equals(checks.setupState)) {
            state = text(strings, R.string.diagnostic_setup_failed, "Setup: Not ready");
        } else {
            state = text(strings, R.string.diagnostic_setup_warning, "Setup: Check needed");
        }
        String installMode = installationModeLabel(checks.installationMode);
        StringBuilder value = new StringBuilder(state)
                .append('\n').append(checkLine(
                        checks.runtimeHookActive && checks.xposedApiAvailable,
                        text(strings, R.string.diagnostic_check_hook_runtime,
                                "Hook runtime / Xposed API"), false))
                .append('\n').append(checkLine(
                        checks.spotifyMainProcess,
                        text(strings, R.string.diagnostic_check_spotify_process,
                                "Spotify main process"), false))
                .append('\n').append(checkLine(
                        checks.internetPermissionGranted,
                        text(strings, R.string.diagnostic_check_internet,
                                "Internet permission"), false))
                .append('\n').append(checkLine(
                        checks.moduleRuntimeAvailable && checks.requiredFeaturesAvailable,
                        text(strings, R.string.diagnostic_check_features,
                                "Module features"), false))
                .append('\n').append(checkLine(
                        true,
                        strings.format("diagnostic_check_installation",
                                "Install: %1$s · Root not checked", installMode), false))
                .append('\n').append(bridgeLine(strings, checks));
        TextView view = bodyText(context, value.toString());
        view.setTextSize(13f);
        view.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        view.setBackground(rounded(0x0DFFFFFF, COL_CARD_BORDER, 14, context));
        return view;
    }

    private static String bridgeLine(SettingsUiStrings strings,
                                     SpicySetupCheckPolicy.Result checks) {
        String label = strings.format("diagnostic_check_hyperglow",
                "HyperGlow bridge: %1$s", checks.hyperGlowBridgeStatus);
        if (!checks.hyperGlowEnabled) return "• " + label;
        return checkLine(SpicySetupCheckPolicy.bridgeReady(checks.hyperGlowBridgeStatus),
                label, true);
    }

    private static String installationModeLabel(String mode) {
        if ("lspatch".equals(mode)) return "LSPatch";
        if ("lsposed".equals(mode)) return "LSPosed";
        return "Xposed";
    }

    private static String checkLine(boolean passed, String label, boolean warning) {
        return (passed ? "✓ " : warning ? "! " : "× ") + label;
    }

    private static TextView selector(Context context, String value) {
        TextView view = optionRow(context, value + (value.endsWith("›") ? "" : "  ›"));
        return view;
    }

    private static TextView optionRow(Context context, String value) {
        TextView view = bodyText(context, value);
        view.setTextColor(COL_ACCENT);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(context, 52));
        view.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        view.setBackground(ripple(rounded(0x0DFFFFFF, 0, 14, context)));
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams matchWrapWithBottom(Context context, int bottomDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(context, bottomDp);
        return params;
    }

    private static GradientDrawable rounded(int fill, int stroke, int radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (stroke != 0) drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    private static RippleDrawable ripple(android.graphics.drawable.Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), content, null);
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private static final class PanelDialog {
        private final Context context;
        private final Dialog dialog;
        private final boolean expanded;
        final TextView positiveButton;
        final TextView neutralButton;
        final TextView negativeButton;

        PanelDialog(Context context, String title, View body, String positive,
                    String neutral, String negative, boolean expanded) {
            this.context = context;
            this.expanded = expanded;
            dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 16));
            root.setBackground(rounded(COL_CARD, COL_CARD_BORDER, 26, context));

            TextView heading = bodyText(context, title);
            heading.setTextSize(26f);
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            root.addView(heading, matchWrapWithBottom(context, 16));

            ScrollView scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(body, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    expanded ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT,
                    expanded ? 1f : 0f);
            root.addView(scroll, bodyParams);

            positiveButton = positive == null ? null : actionButton(context, positive, true);
            neutralButton = neutral == null ? null : actionButton(context, neutral, false);
            negativeButton = negative == null ? null : actionButton(context, negative, false);
            if (positiveButton != null) {
                LinearLayout.LayoutParams primaryParams = matchWrapWithBottom(context, 8);
                primaryParams.topMargin = dp(context, 14);
                root.addView(positiveButton, primaryParams);
            }
            if (neutralButton != null || negativeButton != null) {
                LinearLayout secondary = new LinearLayout(context);
                secondary.setOrientation(LinearLayout.HORIZONTAL);
                if (negativeButton != null) {
                    secondary.addView(negativeButton, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
                if (neutralButton != null) {
                    if (negativeButton != null) {
                        View spacer = new View(context);
                        secondary.addView(spacer, new LinearLayout.LayoutParams(dp(context, 8), 1));
                    }
                    secondary.addView(neutralButton, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
                LinearLayout.LayoutParams secondaryParams = matchWrap();
                if (positiveButton == null) secondaryParams.topMargin = dp(context, 14);
                root.addView(secondary, secondaryParams);
            }
            dialog.setContentView(root);
        }

        void setPrimaryEnabled(boolean enabled) {
            if (positiveButton == null) return;
            positiveButton.setEnabled(enabled);
            positiveButton.setAlpha(enabled ? 1f : 0.42f);
        }

        void show() {
            dialog.show();
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setDimAmount(0.62f);
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
            int height = expanded
                    ? (int) (context.getResources().getDisplayMetrics().heightPixels * 0.82f)
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            window.setLayout(width, height);
        }

        void dismiss() {
            dialog.dismiss();
        }

        private static TextView actionButton(Context context, String label, boolean primary) {
            TextView button = bodyText(context, label);
            button.setGravity(Gravity.CENTER);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextColor(primary ? COL_ACCENT : COL_TITLE);
            button.setMinHeight(dp(context, 48));
            button.setMaxLines(2);
            button.setPadding(dp(context, 10), dp(context, 10),
                    dp(context, 10), dp(context, 10));
            GradientDrawable background = rounded(
                    primary ? 0x241ED760 : 0x0DFFFFFF,
                    primary ? 0x401ED760 : 0,
                    18,
                    context
            );
            button.setBackground(ripple(background));
            return button;
        }
    }
}
