package com.eza.spicyex;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.eza.spicyex.lyrics.ai.AiCredentialStore;
import com.eza.spicyex.lyrics.ai.AiLastProbe;
import com.eza.spicyex.lyrics.ai.AiEndpoint;
import com.eza.spicyex.lyrics.ai.AiGeminiProvider;
import com.eza.spicyex.lyrics.ai.AiModelDescriptor;
import com.eza.spicyex.lyrics.ai.AiModelLiveState;
import com.eza.spicyex.lyrics.ai.AiModelListResult;
import com.eza.spicyex.lyrics.ai.AiProviderFailure;
import com.eza.spicyex.lyrics.ai.AiModelProbe;
import com.eza.spicyex.lyrics.ai.AiRuntimeFailureLog;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.PanelDialog;
import com.eza.spicyex.ui.PanelPickerPopup;

import java.util.List;

import com.eza.spicyex.xposed.XpLog;

/**
 * The AI section's rows: the key, the endpoint, and the model.
 *
 * <p>Split out of {@link SettingsPanel} because these rows behave unlike every other setting there.
 * A key must never persist as it is typed, an endpoint has to be validated before it is accepted,
 * and a model list has to be fetched before it can be offered. The rest of the panel is
 * declarative; this is not.
 *
 * <p>Labels carry their own meaning and no row explains itself, so a value row shows state rather
 * than prose: "Not set" is the answer to "API key", not a sentence about what an API key is for.
 */
final class AiSettingsRows {

    static final class IconAction {
        final ActionIconDrawable.Kind icon;
        final String contentDescription;
        final View.OnClickListener listener;

        IconAction(ActionIconDrawable.Kind icon, String contentDescription,
                   View.OnClickListener listener) {
            this.icon = icon;
            this.contentDescription = contentDescription;
            this.listener = listener;
        }
    }

    interface Host {
        void info(LinearLayout content, String label, String value);

        /** Probe results only tint the AI header badge; patch it instead of a panel rebuild. */
        void updateAiBadge(boolean live);

        void field(LinearLayout content, String label, String value,
                   View.OnClickListener listener, IconAction... actions);

        void selector(LinearLayout content, String label, String value,
                      View.OnClickListener listener, IconAction... actions);

        void rebuild();

        String string(String name, String fallback);
    }

    private final Context context;
    private final Host host;
    private final SettingsStore store;
    private final AiSettings settings;

    AiSettingsRows(Context context, Host host, SettingsStore store) {
        this.context = context;
        this.host = host;
        this.store = store;
        this.settings = new AiSettings(store, AiCredentialStore.create(context));
    }

    void render(LinearLayout content) {
        // Everything below the master switch is unreachable while it is off, so it is not shown.
        if (!settings.isEnabled()) return;

        host.info(content, host.string("settings_ai_status", "AI status"),
                statusLabel(settings.readiness()));

        if (settings.isCustomEndpoint()) {
            String endpoint = settings.endpoint();
            host.field(content, host.string("settings_ai_endpoint", "Endpoint"),
                    endpoint.isEmpty()
                            ? host.string("settings_ai_endpoint_absent", "Not set") : endpoint,
                    v -> promptForEndpoint(),
                    icon(ActionIconDrawable.Kind.EDIT, "settings_ai_action_edit_endpoint", "Edit endpoint",
                            v -> promptForEndpoint()));
        }

        List<IconAction> keyActions = new java.util.ArrayList<>();
        keyActions.add(icon(ActionIconDrawable.Kind.EDIT, "settings_ai_action_edit_key", "Edit API key",
                v -> promptForKey()));
        if (settings.hasCredential()) {
            keyActions.add(icon(ActionIconDrawable.Kind.VISIBILITY,
                    "settings_ai_action_reveal_key", "Reveal API key",
                    v -> revealKeySecurely()));
            keyActions.add(icon(ActionIconDrawable.Kind.DELETE,
                    "settings_ai_action_delete_key", "Delete API key",
                    v -> {
                        settings.credentials().delete(settings.credentialScope());
                        AiModelLiveState.invalidate();
                        host.rebuild();
                    }));
        }
        host.field(content, host.string("settings_ai_api_key", "API key"),
                settings.hasCredential()
                        ? AiCredentialStore.mask(
                        settings.credentials().load(settings.credentialScope()))
                        : host.string("settings_ai_key_absent", "Not set"),
                v -> promptForKey(), keyActions.toArray(new IconAction[0]));

        String model = settings.modelName();
        host.selector(content, host.string("settings_ai_model", "Model"),
                model.isEmpty() ? host.string("settings_ai_model_absent", "Not chosen") : model,
                v -> chooseModel(v),
                icon(ActionIconDrawable.Kind.TEST,
                        "settings_ai_action_test_model", "Test selected model",
                        v -> testModel()));
    }

    /**
     * Whether the AI family is set up end to end: enabled, a key stored for this provider's own
     * scope, a model chosen, and an address to send to.
     *
     * <p>State, not an event. The structured-output probe deliberately does not enter this: it runs
     * once per configuration, it can fail for reasons that say nothing about setup — a rate limit,
     * a momentary outage — and a one-shot result cannot answer "is AI ready" ten minutes later. A
     * failing probe is reported where it is actionable, on the model row, not by unlighting the
     * indicator for a setup that is complete.
     */
    boolean isReady() {
        return settings.readiness() == AiSettings.Readiness.READY;
    }

    /** Runs one tiny liveness probe per provider/model configuration in this Spotify process. */
    void ensureInitialModelCheck() {
        if (!AiModelLiveState.begin(settings)) return;
        final Handler handler = new Handler(context.getMainLooper());
        new Thread(() -> {
            AiModelProbe.Result outcome = null;
            try {
                outcome = AiModelProbe.probe(settings, null);
            } catch (Throwable failure) {
                XpLog.log("[SpotifyPlusAiSettings] initial model probe failed: "
                        + AiRuntimeFailureLog.describe(failure));
            }
            if (outcome != null) {
                AiLastProbe.record(outcome);
                settings.recordProbeOutcome(outcome);
            }
            final boolean result = outcome != null && outcome.ok;
            handler.post(() -> {
                AiModelLiveState.finish(settings, result);
                host.updateAiBadge(result);
            });
        }, "ai-model-initial-probe").start();
    }

    /**
     * Asks for the key in a panel-styled dialog.
     *
     * <p>A dialog rather than an inline field: the panel's text rows persist on every keystroke,
     * which for a secret would write a dozen partial keys to disk on the way to the real one.
     * Nothing is stored until the owner confirms.
     */
    private void promptForKey() {
        PanelDialog dialog = new PanelDialog(context,
                host.string("settings_ai_api_key", "API key"));
        final EditText field = dialog.field(true, "");
        dialog.primary(host.string("settings_ai_save", "Save"), () -> {
            boolean saved = settings.credentials()
                    .save(settings.credentialScope(), field.getText().toString().trim());
            if (saved) AiModelLiveState.invalidate();
            toast(saved
                    ? host.string("settings_ai_key_saved", "API key saved")
                    : host.string("settings_ai_key_rejected", "Key not saved"));
            host.rebuild();
        });
        dialog.secondary(host.string("settings_ai_cancel", "Cancel"), null);
        dialog.show();
    }

    /** Plaintext exists only in a secure, non-selectable, accessibility-hidden transient dialog. */
    private void revealKeySecurely() {
        String secret = settings.credentials().load(settings.credentialScope());
        if (secret.isEmpty()) return;
        PanelDialog dialog = new PanelDialog(context,
                host.string("settings_ai_api_key", "API key"))
                .secure()
                .closeIcon(host.string("lyrics_ai_close", "Close"));
        dialog.secretValue(secret);
        dialog.show();
    }

    /** Endpoints are validated before they are stored, so a bad one fails here and not mid-request. */
    private void promptForEndpoint() {
        PanelDialog dialog = new PanelDialog(context,
                host.string("settings_ai_endpoint", "Endpoint"));
        final EditText field = dialog.field(false, settings.endpoint());
        dialog.primary(host.string("settings_ai_save", "Save"), () -> {
            AiEndpoint.Validated validated = AiEndpoint.validate(field.getText().toString());
            if (!validated.ok()) {
                toast(endpointProblem(validated.problem));
                return;
            }
            store.put(Settings.AI_ENDPOINT, validated.normalized);
            host.rebuild();
        });
        dialog.secondary(host.string("settings_ai_cancel", "Cancel"), null);
        dialog.show();
    }

    /**
     * Fetches the models this key can actually use, then lets the owner pick one.
     *
     * <p>Never auto-selected, even when only one comes back: the choice is part of cache identity,
     * and one made silently is one the owner cannot remember making when the results change.
     */
    private void chooseModel(View anchor) {
        if (!settings.hasCredential()) {
            toast(host.string("settings_ai_needs_key", "Set an API key first"));
            return;
        }
        if (settings.usesOpenAiWire() && settings.endpoint().isEmpty()) {
            toast(host.string("settings_ai_needs_endpoint", "Set an endpoint first"));
            return;
        }
        toast(host.string("settings_ai_checking", "Checking…"));
        final Handler handler = new Handler(context.getMainLooper());
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        final com.eza.spicyex.lyrics.ai.AiSignal discoverySignal = new com.eza.spicyex.lyrics.ai.AiSignal();
        // Backstop only: paged discovery on a slow link (dead-route fallback plus several
        // model-list pages) legitimately exceeds 30s. The transport enforces its own ceiling;
        // this only reclaims a hung settings action so it never spins forever.
        handler.postDelayed(() -> {
            discoverySignal.abort("model_discovery_timeout");
            if (finished.compareAndSet(false, true)) {
                toast(host.string("settings_ai_runtime_unavailable", "AI runtime unavailable"));
            }
        }, 60000L);
        new Thread(() -> {
            try {
                XpLog.log("[SpotifyPlusAiSettings] model discovery started provider="
                        + settings.providerChoice());
                final AiModelListResult result = settings.provider().listModels(discoverySignal);
                XpLog.log("[SpotifyPlusAiSettings] model discovery finished ok=" + result.ok
                        + failureToken(result));
                if (finished.compareAndSet(false, true)) handler.post(() -> showModels(anchor, result));
            } catch (Throwable failure) {
                XpLog.log("[SpotifyPlusAiSettings] model discovery failed: "
                        + AiRuntimeFailureLog.describe(failure));
                if (finished.compareAndSet(false, true)) handler.post(() -> toast(host.string("settings_ai_runtime_unavailable",
                        "AI runtime unavailable")));
            }
        }, "ai-model-discovery").start();
    }

    private void showModels(View anchor, AiModelListResult result) {
        if (!result.ok) {
            toast(failureLabel(result));
            return;
        }
        List<AiModelDescriptor> models = AiGeminiProvider.withSuggestedFirst(result.models);
        if (models.isEmpty()) {
            toast(host.string("settings_ai_no_models", "No usable models for this key"));
            return;
        }
        String current = settings.modelName();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (AiModelDescriptor model : models) names.add(model.name);
        PanelPickerPopup.show(context, anchor, names, current, name -> {
            settings.setModelName(name);
            host.rebuild();
        });
    }

    private void testModel() {
        if (!settings.canRequest()) {
            toast(host.string("settings_ai_not_ready", "Complete AI setup first"));
            return;
        }
        toast(host.string("settings_ai_testing_model", "Testing selected model…"));
        final Handler handler = new Handler(context.getMainLooper());
        new Thread(() -> {
            try {
                AiModelProbe.Result result = AiModelProbe.probe(settings, null);
                AiLastProbe.record(result);
                settings.recordProbeOutcome(result);
                handler.post(() -> {
                    AiModelLiveState.finish(settings, result.ok);
                    if (result.ok) {
                        toast(host.string("settings_ai_model_ready", "Model ready"));
                    } else {
                        String message = host.string("settings_ai_model_failed",
                                "Model failed structured-output test");
                        toast(message + " (" + result.failure + ")");
                    }
                    host.updateAiBadge(result.ok);
                });
            } catch (Throwable failure) {
                XpLog.log("[SpotifyPlusAiSettings] model probe failed: "
                        + AiRuntimeFailureLog.describe(failure));
                handler.post(() -> {
                    AiModelLiveState.finish(settings, false);
                    toast(host.string("settings_ai_runtime_unavailable",
                            "AI runtime unavailable"));
                    host.updateAiBadge(false);
                });
            }
        }, "ai-model-probe").start();
    }

    /** Failure text from the typed kind only — never a provider body, which can echo the request. */
    private String failureLabel(AiModelListResult result) {
        if (result.failure == null) return "";
        switch (result.failure.kind) {
            case AUTH: return host.string("settings_ai_key_rejected_by_provider", "Key rejected");
            case RATE_LIMITED: return host.string("settings_ai_rate_limited", "Rate limited");
            case QUOTA: return host.string("settings_ai_quota", "Quota exhausted");
            case DELIVERY_UNKNOWN: return withCause(
                    host.string("settings_ai_no_response", "No response"), result.failure);
            // Reaching the endpoint and refusing what it sent is not the same as never reaching it,
            // and saying otherwise sends the owner to check their network and their key.
            case OVERSIZED: return host.string("settings_ai_response_too_large",
                    "Response too large");
            case MODEL_UNAVAILABLE: return host.string("settings_ai_no_models",
                    "No usable models for this key");
            default: return host.string("settings_ai_unreachable", "Could not reach provider");
        }
    }

    /**
     * Names the transport cause behind a "No response" so the next attempt is diagnosable:
     * {@code network} (DNS/refused/reset), {@code timeout}, or {@code server <status>}.
     * Machine tokens only; the failure carries no provider body by design.
     */
    private static String withCause(String base, AiProviderFailure failure) {
        if (failure == null) return base;
        String cause = failure.cause == null ? ""
                : failure.cause.name().toLowerCase(java.util.Locale.ROOT);
        String detail = "none".equals(cause) ? "" : cause;
        if (failure.status > 0) {
            detail = detail.isEmpty() ? "server " + failure.status
                    : detail + " " + failure.status;
        }
        return detail.isEmpty() ? base : base + " (" + detail + ")";
    }

    /** Privacy-safe failure token for the discovery log line. */
    private static String failureToken(AiModelListResult result) {
        if (result == null || result.ok || result.failure == null) return "";
        String kind = result.failure.kind == null ? "unknown"
                : result.failure.kind.name().toLowerCase(java.util.Locale.ROOT);
        String cause = result.failure.cause == null ? ""
                : result.failure.cause.name().toLowerCase(java.util.Locale.ROOT);
        return " failure=" + kind + "/" + cause + ":" + result.failure.status;
    }

    private String endpointProblem(AiEndpoint.Problem problem) {
        switch (problem) {
            case INSECURE:
                return host.string("settings_ai_endpoint_insecure", "HTTPS required");
            case HAS_CREDENTIALS:
                return host.string("settings_ai_endpoint_credentials", "Remove credentials from URL");
            case HAS_QUERY_OR_FRAGMENT:
                return host.string("settings_ai_endpoint_query", "Remove query from URL");
            default:
                return host.string("settings_ai_endpoint_malformed", "Not a valid URL");
        }
    }

    private void toast(String message) {
        if (message != null && !message.isEmpty()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        }
    }

    private IconAction icon(ActionIconDrawable.Kind icon, String stringName, String fallback,
                            View.OnClickListener listener) {
        return new IconAction(icon, host.string(stringName, fallback), listener);
    }

    private String statusLabel(AiSettings.Readiness readiness) {
        switch (readiness) {
            case READY: return host.string("settings_ai_ready", "Ready");
            case NO_CREDENTIAL: return host.string("settings_ai_key_absent", "Not set");
            case NO_MODEL: return host.string("settings_ai_model_absent", "Not chosen");
            case NO_ENDPOINT: return host.string("settings_ai_endpoint_absent", "Not set");
            default: return host.string("settings_ai_disabled", "Off");
        }
    }

}
