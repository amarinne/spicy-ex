package com.eza.spicyex.diagnostics;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import com.eza.spicyex.R;
import com.eza.spicyex.ui.SettingsUiStrings;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Opens the real receipt owner with disposable values; it performs no upload or store write. */
public final class DiagnosticReceiptInstrumentation {
    public static void run(Instrumentation host) {
        List<String> failures = new ArrayList<>();
        String[] languages = {"en", "ja", "ko", "ru", "zh-CN"};
        Activity activity = null;
        int completed = 0;
        try {
            Intent intent = new Intent();
            intent.setClassName(host.getTargetContext(), "com.eza.spicyex.auto.AutoRenderingTestActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = host.startActivitySync(intent);
            Activity fixture = activity;
            host.runOnMainSync(() -> fixture.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            String id = "R1-00000000000000000000000001";
            SpicyDiagnosticReportFactory.Draft draft = new SpicyDiagnosticReportFactory.Draft(
                    id, "other", "Disposable receipt preview", "{}", "", "", SpicySetupCheckPolicy.Result.unknown());
            DiagnosticUploader.Receipt receipt = new DiagnosticUploader.Receipt(id, "2026-10-12T00:00:00Z", null, "indefinite");
            Method show = DiagnosticReportingDialog.class.getDeclaredMethod("showReceipt", android.content.Context.class,
                    SettingsUiStrings.class, SpicyDiagnosticReportFactory.Draft.class, DiagnosticUploader.Receipt.class);
            show.setAccessible(true);
            for (String language : languages) {
                try {
                    SettingsUiStrings strings = new SettingsUiStrings(fixture, language);
                    String expected = strings.resource(R.string.diagnostic_follow_up_notice, "");
                    if (expected.isEmpty()) throw new AssertionError("missing notice resource: " + language);
                    host.runOnMainSync(() -> {
                        try { show.invoke(null, fixture, strings, draft, receipt); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    });
                    host.waitForIdleSync();
                    List<AccessibilityNodeInfo> nodes = new ArrayList<>();
                    long deadline = android.os.SystemClock.uptimeMillis() + 3000;
                    while (nodes.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) {
                        AccessibilityNodeInfo root = host.getUiAutomation().getRootInActiveWindow();
                        if (root != null) nodes = root.findAccessibilityNodeInfosByText(expected);
                        if (nodes.isEmpty()) Thread.sleep(100);
                    }
                    if (nodes.size() != 1 || !expected.contentEquals(nodes.get(0).getText()))
                        throw new AssertionError("receipt notice missing or duplicated: " + language);
                    Rect bounds = new Rect(); nodes.get(0).getBoundsInScreen(bounds);
                    if (bounds.isEmpty() || !nodes.get(0).isVisibleToUser()) throw new AssertionError("notice is hidden");
                    File directory = new File(host.getTargetContext().getCacheDir(), "behavior/diagnostic-receipt");
                    if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("evidence directory unavailable");
                    host.getUiAutomation().waitForIdle(300, 3000);
                    Bitmap screenshot = host.getUiAutomation().takeScreenshot();
                    if (screenshot == null) throw new AssertionError("screenshot unavailable");
                    try (FileOutputStream output = new FileOutputStream(new File(directory, language + ".png"))) {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
                    } finally { screenshot.recycle(); }
                    completed++;
                } catch (Throwable error) { failures.add(language + ": " + error); }
                finally { host.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); host.waitForIdleSync(); }
            }
        } catch (Throwable error) { failures.add(error.toString()); }
        finally { if (activity != null) { Activity fixture = activity; host.runOnMainSync(fixture::finish); } }
        Bundle result = new Bundle();
        result.putInt("expectedCases", languages.length); result.putInt("completedCases", completed);
        result.putString("stream", failures.isEmpty() ? "PASS: 5 diagnostic receipt cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        host.finish(failures.isEmpty() ? -1 : 0, result);
    }
}
