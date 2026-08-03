package com.eza.spicyex.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Exact pending payload retained for a bounded manual retry with the same report ID. */
public final class DiagnosticDraftStore {
    private static final String PREFS = "spicy_diagnostic_draft_v1";
    private static final String FILE_NAME = "spicy-diagnostic-report-pending-v1.json";

    private DiagnosticDraftStore() {
    }

    public static boolean save(Context context, SpicyDiagnosticReportFactory.Draft draft) {
        if (draft == null || DiagnosticReportContract.utf8Bytes(draft.json)
                > DiagnosticReportContract.CLIENT_BODY_BYTES) return false;
        File file = new File(context.getCacheDir(), FILE_NAME);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(draft.json.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Throwable ignored) {
            clear(context);
            return false;
        }
        return prefs(context).edit()
                .putLong("created_at", System.currentTimeMillis())
                .putString("report_id", draft.reportId)
                .commit();
    }

    public static SpicyDiagnosticReportFactory.Draft load(Context context) {
        SharedPreferences preferences = prefs(context);
        long createdAt = preferences.getLong("created_at", -1L);
        long now = System.currentTimeMillis();
        if (createdAt < 0L || now < createdAt || now - createdAt >= DiagnosticReportContract.DRAFT_TTL_MS) {
            clear(context);
            return null;
        }
        File file = new File(context.getCacheDir(), FILE_NAME);
        try {
            if (!file.exists() || file.length() <= 0L
                    || file.length() > DiagnosticReportContract.CLIENT_BODY_BYTES) {
                clear(context);
                return null;
            }
            String json = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            SpicyDiagnosticReportFactory.Draft draft = SpicyDiagnosticReportFactory.fromJson(json);
            if (draft == null || !draft.reportId.equals(preferences.getString("report_id", ""))) {
                clear(context);
                return null;
            }
            return draft;
        } catch (Throwable ignored) {
            clear(context);
            return null;
        }
    }

    public static void clear(Context context) {
        new File(context.getCacheDir(), FILE_NAME).delete();
        prefs(context).edit().clear().commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
