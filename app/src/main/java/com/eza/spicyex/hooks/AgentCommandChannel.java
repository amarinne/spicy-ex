package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.eza.spicyex.BuildConfig;
import com.eza.spicyex.References;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import com.eza.spicyex.lyrics.catalog.LyricsCatalog;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.RankingMode;
import com.eza.spicyex.lyrics.cache.CacheClearKind;
import com.eza.spicyex.settings.SourcePreferencesAdapter;
import com.eza.spicyex.settings.SettingsWriter;
import com.eza.spicyex.xposed.XpLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A debug file channel for semantic UI and session actions without coordinate input.
 *
 * <p>Why this exists: every catalog action is a method on {@link LyricsHost}, which lives in
 * Spotify's process, and the module ships no receiver, service, or provider. Reaching those actions
 * through the UI meant locating a control by coordinate, which is exactly the kind of evidence that
 * fails silently — a scroll-dependent footer, or an accessibility tree that returns an idle-state
 * error, produces a plausible-looking wrong result rather than an obvious failure.
 *
 * <p>Transport: a plain file in Spotify's own files directory, which the module already writes for
 * the catalog, so no IPC and no new manifest component are introduced.
 *
 * <pre>
 *   &lt;files&gt;/spicy-agent/arm      presence arms the channel; without it nothing is polled
 *   &lt;files&gt;/spicy-agent/cmd      one command line, written by the caller
 *   &lt;files&gt;/spicy-agent/out      append-only result lines, one per command
 * </pre>
 *
 * <p>Commands, one per line in {@code cmd}:
 * <ul>
 *   <li>{@code status} — ack with the current track and whether a document is loaded</li>
 *   <li>{@code fullscreen open|close|back|status} — use the native takeover and exit owners</li>
 *   <li>{@code settings open|close|status} — use the settings dialog lifecycle</li>
 *   <li>{@code layer refresh|restore|ai SOUND|MEANING} — use the shared layer scheduler</li>
 *   <li>{@code sources} — commit ranking, order, and enabled flags through the settings adapter</li>
 *   <li>{@code playback} — use the captured Spotify transport</li>
 *   <li>{@code action} — invoke reading, follow, skip, and sync actions on the mounted shell</li>
 *   <li>{@code auto} — drop any manual pin and re-elect the automatic winner</li>
 *   <li>{@code climb} — ask the whole quality chain in order, bypassing the racing chain</li>
 *   <li>{@code check <source>} — ask one source, e.g. {@code check apple}</li>
 *   <li>{@code select-candidate <id>} — pin one exact stored catalog candidate</li>
 *   <li>{@code restore-selection <uri> <mode> [id]} — restore a gate's previous seat</li>
 *   <li>{@code footer} — read the source footer currently rendered by the lyrics surface</li>
 *   <li>{@code picker open|close|status} — open or inspect the source picker</li>
 *   <li>{@code editor open [lyrics|card]} — open the layout editor, no tap needed</li>
 *   <li>{@code editor close} — close it again</li>
 *   <li>{@code editor select <name>} — select one element: {@code artwork},
 *       {@code track_text}, {@code focus}, {@code lyrics}, {@code background}, {@code skip},
 *       {@code follow}, {@code top_bar}, {@code card}</li>
 *   <li>{@code layout} — one JSON line of real on-screen geometry plus the layout rule violations
 *       it implies (see {@link LayoutProbeReport})</li>
 *   <li>{@code setting <key> <value>} — write one {@link Settings.Setting}, replying
 *       {@code old=… new=…} so a caller can put the previous value back</li>
 *   <li>{@code setting-get <key>} — read one setting's current value</li>
 * </ul>
 *
 * <p>Each command appends exactly one {@code SPICY_AGENT} line to {@code out} and to the module
 * log. Results are appended rather than overwritten so a caller can fire several commands and read
 * them back in order.
 *
 * <p>The layout-editor verbs exist because auditing the editor by tapping is unreliable in a way
 * that fails silently: a tap on a lyric line seeks playback, a tap that lands a few pixels off
 * selects the neighbouring element, and both look like a real answer afterwards. Driving the same
 * entry points and reading the same views is both quieter and checkable.
 *
 * <p><b>This cannot run in a released build.</b> Two independent conditions must hold: the variant
 * must be debug (the public release builds {@code :app:assembleRelease}, where
 * {@link BuildConfig#DEBUG} is constant false), and the arm file must exist. A user who never runs
 * the test script never creates the arm file, so the channel never even polls.
 */
final class AgentCommandChannel {

    private static final String TAG = "[SpotifyPlusAgent]";
    private static final String DIR = "spicy-agent";
    private static final String ARM = "arm";
    private static final String CMD = "cmd";
    private static final String OUT = "out";
    private static final String MARK = "SPICY_AGENT";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long POLL_MS = 750L;
    /** A command is consumed at most once, so a hung command cannot become a poll loop. */
    private static final int MAX_LINE = 512;

    private final LyricsHost host;
    private final Context context;
    private final File dir;
    private final Consumer<Runnable> main;
    private ScheduledExecutorService worker;
    /** Last command text dispatched, so an undeletable command file cannot cause a poll loop. */
    private String lastDispatched = "";
    /** Set once the reply file has proven unwritable, so the failure is logged exactly one time. */
    private boolean outWriteFailed;
    private WeakReference<LyricsSourcePickerDialog> agentPicker = new WeakReference<>(null);

    private AgentCommandChannel(LyricsHost host, Context context) {
        this(host, context, new Handler(Looper.getMainLooper())::post);
    }

    AgentCommandChannel(LyricsHost host, Context context, Consumer<Runnable> main) {
        this.host = host;
        this.context = context;
        this.main = main;
        File files = context.getFilesDir();
        this.dir = files == null ? null : new File(files, DIR);
    }

    /**
     * Starts the channel when the build allows it. Safe to call in any process; it returns
     * immediately unless this is the main Spotify process on a debug build.
     */
    static void start(LyricsHost host, Context context) {
        if (host == null || context == null) return;
        if (!BuildConfig.DEBUG) return;
        try {
            AgentCommandChannel channel = new AgentCommandChannel(host, context);
            if (channel.dir == null) return;
            if (!channel.dir.isDirectory() && !channel.dir.mkdirs()) return;
            channel.worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "spicy-agent-channel");
                t.setDaemon(true);
                return t;
            });
            channel.worker.scheduleWithFixedDelay(channel::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
            XpLog.log(TAG + " channel ready dir=" + channel.dir.getAbsolutePath()
                    + " armed=" + new File(channel.dir, ARM).exists());
        } catch (Throwable t) {
            XpLog.log(TAG + " channel start failed: " + t);
        }
    }

    private void poll() {
        try {
            if (!new File(dir, ARM).exists()) return;
            File cmd = new File(dir, CMD);
            if (!cmd.isFile() || cmd.length() == 0L) return;
            String rawLine;
            try (RandomAccessFile in = new RandomAccessFile(cmd, "r")) {
                if (in.length() > MAX_LINE) {
                    // Truncated so the tail of an over-long write is still processed next poll.
                    in.setLength(MAX_LINE);
                }
                byte[] bytes = new byte[(int) Math.min(in.length(), MAX_LINE)];
                in.readFully(bytes);
                rawLine = new String(bytes, UTF8).trim();
            }
            if (rawLine.isEmpty()) return;
            // Consume at most once. Deleting is the normal way, but the directory can end up owned by
            // another uid than this process, in which case the delete fails and the file would be
            // re-read on every poll forever. Deduping on the whole line keeps a failed delete
            // at-most-once instead of a silent hang. The line carries the caller's correlation id, so
            // sending the same command twice in a row is still two commands.
            if (rawLine.equals(lastDispatched)) return;
            lastDispatched = rawLine;
            boolean consumed = cmd.delete();
            if (!consumed) {
                reply("warn", "consume", "could not delete " + CMD
                        + "; this command will not run again until its content changes",
                        id(rawLine));
            }
            dispatch(rawLine);
        } catch (Throwable t) {
            XpLog.log(TAG + " poll failed: " + t);
        }
    }

    /** Trailing {@code #id} written by the caller, used to match a reply to its request. */
    private static String id(String raw) {
        int at = raw.lastIndexOf('#');
        if (at < 0) return "";
        String tail = raw.substring(at + 1).trim();
        return tail.isEmpty() || tail.indexOf(' ') >= 0 ? "" : tail;
    }

    void dispatch(String raw) {
        if (raw.isEmpty()) {
            reply("ignored", "empty", "");
            return;
        }
        String[] parts = raw.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";
        // The correlation id is a trailing token, not part of the command's own argument.
        String correlation = id(raw);
        String argument = rest;
        if (correlation.isEmpty()) {
            argument = rest;
        } else if (rest.endsWith("#" + correlation)) {
            argument = rest.substring(0, rest.length() - correlation.length() - 1).trim();
        }
        String track = trackLabel();
        final String commandArgument = argument;
        try {
            switch (verb) {
                case "status":
                    onMain(verb, correlation, () -> {
                        Activity activity = References.currentActivity();
                        NativeSpicyShellView shell = activity == null || activity.getWindow() == null
                                ? null : findShell(activity.getWindow().getDecorView());
                        reply("ok", verb, "track=" + trackLabel() + " fullscreen=" + (shell != null)
                                + " document=" + (shell != null && shell.hasLyricsDocument()), correlation);
                    });
                    return;
                case "fullscreen":
                    fullscreen(argument, correlation);
                    return;
                case "settings":
                    String settingsAction = argument;
                    onMain(verb, correlation, () -> {
                        if (!"open".equals(settingsAction) && !"close".equals(settingsAction)
                                && !"status".equals(settingsAction)) {
                            reply("error", verb, "expected open, close, or status", correlation);
                            return;
                        }
                        NativeSpicyShellView shell = requireShell(verb, correlation);
                        if (shell == null) return;
                        boolean result = shell.agentSettings(settingsAction);
                        reply(result || "status".equals(settingsAction) ? "ok" : "error", verb,
                                settingsAction + "=" + result, correlation);
                    });
                    return;
                case "auto":
                    host.resetCatalogToAuto(
                            (ok, detail) -> reply(ok ? "ok" : "error", verb,
                                    explain(ok, detail, track), correlation));
                    return;
                case "climb":
                    host.refreshAllCatalogSourcesInOrder(
                            (ok, detail) -> reply(ok ? "ok" : "error", verb,
                                    explain(ok, detail, track), correlation));
                    return;
                case "check":
                    CatalogSource.SourceId source = parseSource(argument);
                    if (source == null) {
                        reply("error", verb, "unknown source '" + argument + "'", correlation);
                        return;
                    }
                    host.refreshCatalogSource(source,
                            (ok, detail) -> reply(ok ? "ok" : "error", verb,
                                    explain(ok, detail, track), correlation));
                    return;
                case "check-other":
                    host.checkOtherCatalogSources((ok, detail) -> reply(ok ? "ok" : "error", verb,
                            explain(ok, detail, track), correlation));
                    return;
                case "reject-candidate":
                case "remove-candidate":
                case "delete-track":
                    catalogDelete(verb, argument, correlation);
                    return;
                case "layer":
                    layer(argument, correlation);
                    return;
                case "clear-cache":
                    String[] clearArgs = argument.split("\\s+");
                    if (clearArgs.length != 2 || !"confirm".equals(clearArgs[1])) {
                        reply("error", verb, "expected KIND confirm", correlation);
                        return;
                    }
                    CacheClearKind kind = CacheClearKind.valueOf(clearArgs[0].toUpperCase(Locale.ROOT));
                    onMain(verb, correlation, () -> {
                        host.clearLyricsCache(kind);
                        reply("ok", verb, "requested kind=" + kind, correlation);
                    });
                    return;
                case "playback":
                    playback(argument, correlation);
                    return;
                case "pip":
                    onMain(verb, correlation, () -> {
                        Activity activity = References.currentActivity();
                        boolean opened = "open".equals(commandArgument) && activity != null
                                && host.openLyricsPip(activity);
                        reply(opened ? "ok" : "error", verb,
                                opened ? "requested" : "expected open on an eligible lyrics screen",
                                correlation);
                    });
                    return;
                case "select-candidate":
                    if (argument.isEmpty()) {
                        reply("error", verb, "missing candidate id", correlation);
                        return;
                    }
                    host.selectCatalogCandidate(argument,
                            (ok, detail) -> reply(ok ? "ok" : "error", verb,
                                    explain(ok, detail, track), correlation));
                    return;
                case "restore-selection":
                    restoreSelection(argument, correlation);
                    return;
                case "footer":
                    readFooter(correlation);
                    return;
                case "picker":
                    picker(argument.isEmpty() ? "open" : argument, correlation);
                    return;
                case "editor":
                    editor(argument, correlation);
                    return;
                case "layout":
                    layout(correlation);
                    return;
                case "setting":
                    writeSetting(argument, correlation);
                    return;
                case "setting-get":
                    readSetting(argument, correlation);
                    return;
                case "sources":
                    sources(argument, correlation);
                    return;
                case "action":
                    onMain(verb, correlation, () -> {
                        NativeSpicyShellView shell = requireShell(verb, correlation);
                        if (shell == null) return;
                        boolean accepted = shell.agentAction(commandArgument);
                        reply(accepted ? "ok" : "error", verb,
                                "accepted=" + accepted + " action=" + commandArgument, correlation);
                    });
                    return;
                default:
                    reply("error", verb, "unknown command", correlation);
            }
        } catch (Throwable t) {
            reply("error", verb, "threw " + t, correlation);
        }
    }

    /**
     * A track action needs the lyrics session attached to a track, and the session only attaches
     * while something holds a polling lease: the lyrics surface open, or the HyperGlow bridge
     * enabled. When the player knows the track but the session does not, the session's own
     * "No current track" is both true and useless, so say what to do about it.
     */
    private String explain(boolean ok, String detail, String track) {
        if (ok || detail == null) return detail;
        if (detail.contains("No current track") && !"none".equals(track) && !"error".equals(track)) {
            return "the lyrics session is not attached to " + track
                    + "; open the Spicy lyrics surface once, or enable Publish lyrics to HyperGlow,"
                    + " so something holds a polling lease";
        }
        return detail;
    }

    private void onMain(String verb, String correlation, Runnable action) {
        main.accept(() -> {
            try { action.run(); }
            catch (Throwable t) { reply("error", verb, "threw " + t, correlation); }
        });
    }

    private void fullscreen(String action, String correlation) {
        onMain("fullscreen", correlation, () -> {
            Activity activity = References.currentActivity();
            if (activity == null || activity.isDestroyed() || activity.isFinishing()) {
                reply("error", "fullscreen", "no current activity", correlation);
                return;
            }
            NativeSpicyShellView shell = activity.getWindow() == null ? null
                    : findShell(activity.getWindow().getDecorView());
            switch (action) {
                case "status":
                    reply("ok", "fullscreen", "mounted=" + (shell != null)
                            + " document=" + (shell != null && shell.hasLyricsDocument()), correlation);
                    return;
                case "open":
                    boolean opened = shell != null || host.launchNativeLyricsFullscreen(activity);
                    reply(opened ? "ok" : "error", "fullscreen",
                            shell != null ? "mounted" : opened ? "requested" : "launch failed",
                            correlation);
                    return;
                case "back":
                case "close":
                    if (!LyricsActivityTakeoverHook.isLyricsFullscreenActivity(activity)) {
                        reply("error", "fullscreen", "no fullscreen activity", correlation);
                        return;
                    }
                    LyricsSourcePickerDialog picker = agentPicker.get();
                    if ("back".equals(action)) {
                        boolean closedOverlay = false;
                        if (picker != null && picker.isOpen()) {
                            picker.dismiss();
                            closedOverlay = true;
                        } else if (shell != null && shell.agentSettings("status")) {
                            closedOverlay = shell.agentSettings("close");
                        } else if (shell != null) {
                            closedOverlay = shell.consumeBack();
                        }
                        if (closedOverlay) {
                            reply("ok", "fullscreen", "closed overlay", correlation);
                            return;
                        }
                    }
                    if (picker != null) picker.dismiss();
                    if (shell != null) shell.agentSettings("close");
                    host.markExplicitLyricsExit(activity);
                    activity.finish();
                    reply("ok", "fullscreen", "exit requested", correlation);
                    return;
                default:
                    reply("error", "fullscreen", "expected open, close, back, or status", correlation);
            }
        });
    }

    private void picker(String action, String correlation) {
        onMain("picker", correlation, () -> {
            LyricsSourcePickerDialog picker = agentPicker.get();
            if ("status".equals(action)) {
                reply("ok", "picker", "showing=" + (picker != null && picker.isShowing()),
                        correlation);
                return;
            }
            if ("close".equals(action)) {
                if (picker != null) picker.dismiss();
                agentPicker.clear();
                reply("ok", "picker", "closed", correlation);
                return;
            }
            if (!"open".equals(action)) {
                reply("error", "picker", "expected open, close, or status", correlation);
                return;
            }
            Activity activity = References.currentActivity();
            if (activity == null || activity.isDestroyed() || activity.isFinishing()) {
                reply("error", "picker", "no current activity", correlation);
                return;
            }
            if (picker != null && picker.isOpen()) {
                reply("ok", "picker", "already open", correlation);
                return;
            }
            agentPicker = new WeakReference<>(LyricsSourcePickerDialog.showForAgent(activity, host,
                    com.eza.spicyex.ui.UiLanguage.strings(activity, null),
                    (ok, detail) -> reply(ok ? "ok" : "error", "picker", detail, correlation)));
        });
    }

    private void catalogDelete(String verb, String argument, String correlation) {
        String[] args = argument.split("\\s+");
        if (args.length != 2 || !"confirm".equals(args[1]) || args[0].isEmpty()) {
            reply("error", verb, "expected " + ("delete-track".equals(verb) ? "TRACK_URI" : "CANDIDATE_ID")
                    + " confirm", correlation);
            return;
        }
        onMain(verb, correlation, () -> {
            LyricsHost.CatalogActionCallback callback = (ok, detail) -> reply(ok ? "ok" : "error",
                    verb, detail, correlation);
            if ("delete-track".equals(verb)) {
                if (!args[0].equals(host.catalogTrackUri())) {
                    reply("error", verb, "current track changed", correlation);
                    return;
                }
                host.deleteCatalogTrack(callback);
            } else if ("reject-candidate".equals(verb)) {
                host.rejectCatalogCandidate(args[0], callback);
            } else {
                host.removeCatalogCandidate(args[0], callback);
            }
        });
    }

    private void layer(String argument, String correlation) {
        String[] args = argument.split("\\s+");
        if (args.length != 2) {
            reply("error", "layer", "expected refresh|restore|ai SOUND|MEANING", correlation);
            return;
        }
        LayerKind layer = LayerKind.valueOf(args[1].toUpperCase(Locale.ROOT));
        onMain("layer", correlation, () -> {
            switch (args[0]) {
                case "refresh": host.refreshLyricsLayer(layer); break;
                case "restore": host.restoreLyricsLayer(layer); break;
                case "ai":
                    com.eza.spicyex.lyrics.ai.AiRequestStartResult result = host.requestAiLyricsLayer(layer);
                    reply(result.started() ? "ok" : "error", "layer", result.token, correlation);
                    return;
                default:
                    reply("error", "layer", "expected refresh, restore, or ai", correlation);
                    return;
            }
            reply("ok", "layer", "requested action=" + args[0] + " layer=" + layer, correlation);
        });
    }

    private void playback(String argument, String correlation) {
        String[] args = argument.split("\\s+");
        onMain("playback", correlation, () -> {
            boolean accepted;
            switch (args[0]) {
                case "toggle": accepted = host.togglePlayPause(); break;
                case "next": accepted = host.skipToNextTrack(); break;
                case "previous": accepted = host.skipToPreviousTrack(); break;
                case "seek":
                    if (args.length != 2) throw new IllegalArgumentException("seek needs milliseconds");
                    long position = Long.parseLong(args[1]);
                    accepted = position >= 0 && host.canSeek() && host.seekSpotifyTo(position);
                    break;
                case "status":
                    SpotifyTrack track = host.getCurrentTrackSafely();
                    boolean playing = host.isPlayerActuallyPlaying();
                    reply("ok", "playback", "playing=" + playing + " position="
                            + host.readBestMeasuredProgressMs(track, playing) + " seekable=" + host.canSeek(),
                            correlation);
                    return;
                default:
                    reply("error", "playback", "expected status, toggle, next, previous, or seek MS", correlation);
                    return;
            }
            reply(accepted ? "ok" : "error", "playback", "accepted=" + accepted, correlation);
        });
    }

    private void sources(String argument, String correlation) {
        onMain("sources", correlation, () -> {
            Activity activity = References.currentActivity();
            if (activity == null || activity.isDestroyed()) {
                reply("error", "sources", "no current activity", correlation);
                return;
            }
            RankingMode mode =
                    LyricsSourcePreferences.rankingMode(activity);
            List<Source> order = new ArrayList<>(
                    LyricsSourcePreferences.sourceOrder(activity));
            java.util.Map<Source, Boolean> enabled =
                    new java.util.EnumMap<>(Source.class);
            for (Source source
                    : Source.values()) {
                enabled.put(source,
                        LyricsSourcePreferences.sourceEnabled(activity, source));
            }
            String before = "mode=" + mode.id + " order=" + order + " enabled=" + enabled;
            String[] args = argument.split("\\s+");
            if ("status".equals(argument)) {
                reply("ok", "sources", before, correlation);
                return;
            }
            if (args.length == 2 && "mode".equals(args[0])
                    && ("auto".equals(args[1]) || "order".equals(args[1]))) {
                mode = RankingMode.parse(args[1]);
            } else if (args.length == 3 && "enable".equals(args[0])
                    && ("true".equals(args[2]) || "false".equals(args[2]))) {
                Source source =
                        Source.parse(args[1]);
                if (source == null || source == Source.SPICY)
                    throw new IllegalArgumentException("unknown or retired source");
                enabled.put(source, Boolean.valueOf(args[2]));
            } else if (args.length == 2 && "order".equals(args[0])) {
                order.clear();
                for (String token : args[1].split(",", -1)) {
                    Source source =
                            Source.parse(token);
                    if (source == null || order.contains(source))
                        throw new IllegalArgumentException("unknown or duplicate source");
                    order.add(source);
                }
            } else {
                reply("error", "sources", "expected status, mode auto|order, enable SOURCE true|false,"
                        + " or order CSV", correlation);
                return;
            }
            SourcePreferencesAdapter adapter =
                    new SourcePreferencesAdapter(
                            new SourcePreferencesAdapter.Sink() {
                        public void setRankingMode(RankingMode value) {
                            LyricsSourcePreferences.setRankingMode(activity, value);
                        }
                        public void setSourceOrder(List<Source> value) {
                            LyricsSourcePreferences.setSourceOrder(activity, value);
                        }
                        public void setSourceEnabled(Source source,
                                                     boolean value) {
                            LyricsSourcePreferences.setSourceEnabled(activity, source, value);
                        }
                    });
            adapter.commit(new SettingsWriter(new SettingsStore(activity)),
                    new SourcePreferencesAdapter.Commit(
                            mode == RankingMode.AUTO
                                    ? "Auto" : "Source order", order, enabled));
            host.reconcileLyricsSources();
            reply("ok", "sources", "old={" + before + "} new={mode=" + mode.id
                    + " order=" + LyricsSourcePreferences.sourceOrder(activity)
                    + " enabled=" + enabled + "}", correlation);
        });
    }

    // -- layout editor probe -------------------------------------------------------
    // All of these post to the main thread: the editor and the views being measured are
    // main-thread only, and every one of them replies exactly once - on success or on the throw.

    private void editor(String argument, String correlation) {
        String[] args = argument.isEmpty() ? new String[0] : argument.split("\\s+");
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        main.accept(() -> {
            try {
                NativeSpicyShellView shell = requireShell("editor", correlation);
                if (shell == null) return;
                switch (action) {
                    case "open": {
                        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "lyrics";
                        if (!"lyrics".equals(mode) && !"card".equals(mode)) {
                            reply("error", "editor", "open takes lyrics or card, not '" + args[1] + "'",
                                    correlation);
                            return;
                        }
                        boolean opened = shell.agentOpenEditor("card".equals(mode));
                        reply(opened ? "ok" : "error", "editor",
                                opened ? "open mode=" + mode : "already open", correlation);
                        return;
                    }
                    case "close": {
                        boolean closed = shell.agentCloseEditor();
                        reply(closed ? "ok" : "error", "editor",
                                closed ? "close" : "not open", correlation);
                        return;
                    }
                    case "select": {
                        if (args.length < 2) {
                            reply("error", "editor", "select needs an element name", correlation);
                            return;
                        }
                        String name = args[1].toLowerCase(Locale.ROOT);
                        boolean selected = shell.agentSelectElement(name);
                        reply(selected ? "ok" : "error", "editor",
                                selected ? "select name=" + name : "cannot select '" + args[1] + "'",
                                correlation);
                        return;
                    }
                    case "back":
                    case "demo":
                    case "tab":
                    case "reset": {
                        boolean reset = "reset".equals(action);
                        if (reset && (args.length != 3 || !"all".equals(args[1])
                                || !"confirm".equals(args[2]))) {
                            reply("error", "editor", "reset needs all confirm", correlation);
                            return;
                        }
                        if (!reset && args.length != ("back".equals(action) ? 1 : 2)) {
                            reply("error", "editor", "invalid action arguments", correlation);
                            return;
                        }
                        boolean accepted = shell.agentEditorAction(action, args.length > 1 ? args[1] : "");
                        reply(accepted ? "ok" : "error", "editor", "accepted=" + accepted,
                                correlation);
                        return;
                    }
                    default:
                        reply("error", "editor", "expected open, close, select, back, demo, tab, or reset", correlation);
                }
            } catch (Throwable t) {
                reply("error", "editor", "threw " + t, correlation);
            }
        });
    }

    private void layout(String correlation) {
        main.accept(() -> {
            try {
                NativeSpicyShellView shell = requireShell("layout", correlation);
                if (shell == null) return;
                String report = shell.agentLayoutReport();
                if (report == null) {
                    reply("error", "layout", "could not read the layout", correlation);
                    return;
                }
                reply("ok", "layout", report, correlation);
            } catch (Throwable t) {
                reply("error", "layout", "threw " + t, correlation);
            }
        });
    }

    private void writeSetting(String argument, String correlation) {
        String[] args = argument.split("\\s+", 2);
        if (args.length < 2 || args[0].isEmpty() || args[1].trim().isEmpty()) {
            reply("error", "setting", "expected a setting key and a value", correlation);
            return;
        }
        main.accept(() -> {
            try {
                Activity activity = References.currentActivity();
                if (activity == null || activity.isDestroyed()) {
                    reply("error", "setting", "no current activity", correlation);
                    return;
                }
                Settings.Setting<?> setting = findSetting(args[0]);
                if (setting == null) {
                    reply("error", "setting", "unknown setting '" + args[0] + "'", correlation);
                    return;
                }
                Object value = coerceTyped(setting, args[1].trim());
                if (value == null) {
                    reply("error", "setting", "'" + args[1].trim() + "' is not a "
                            + setting.defaultValue.getClass().getSimpleName(), correlation);
                    return;
                }
                if (!isAllowed(setting, value)) {
                    reply("error", "setting", "'" + value + "' is not one of "
                            + setting.allowedValues, correlation);
                    return;
                }
                if (setting instanceof Settings.IntegerSetting) {
                    // Bounded settings clamp rather than throw, so the reply names what the module
                    // will actually read back instead of the number that was asked for.
                    value = setting.coerce(value);
                }
                SettingsStore store = new SettingsStore(activity);
                Object previous = store.get(setting);
                SettingsWriter writer = new SettingsWriter(store);
                if (isAdapterOwnedSetting(setting)) {
                    reply("error", "setting", "'" + setting.key + "' is owned by the source"
                            + " selection dialog; change ranking, order, and toggles there so both"
                            + " preference namespaces stay together", correlation);
                    return;
                }
                if (setting instanceof Settings.BooleanSetting) {
                    writer.put((Settings.BooleanSetting) setting, (Boolean) value);
                } else if (setting instanceof Settings.StringSetting) {
                    writer.put((Settings.StringSetting) setting, (String) value);
                } else if (setting instanceof Settings.IntegerSetting) {
                    writer.put((Settings.IntegerSetting) setting, (Integer) value);
                } else {
                    reply("error", "setting", "unsupported setting type", correlation);
                    return;
                }
                if (setting == Settings.LYRICS_SOURCE_MODE) {
                    // The UI mirrors the ranking label into the source namespace on every change
                    // (SettingsPanel.onSettingChanged); CatalogPolicy.read consults that
                    // namespace, so a CLI write must do the same or acquisition keeps the old
                    // mode while the panel shows the new one.
                    LyricsSourcePreferences.setRankingMode(
                            activity,
                            RankingMode
                                    .parse(String.valueOf(value)));
                }
                reply("ok", "setting", "old=" + previous + " new=" + value, correlation);
            } catch (Throwable t) {
                reply("error", "setting", "threw " + t, correlation);
            }
        });
    }

    private void readSetting(String argument, String correlation) {
        String key = argument.trim();
        if (key.isEmpty()) {
            reply("error", "setting-get", "expected a setting key", correlation);
            return;
        }
        main.accept(() -> {
            try {
                Activity activity = References.currentActivity();
                if (activity == null || activity.isDestroyed()) {
                    reply("error", "setting-get", "no current activity", correlation);
                    return;
                }
                Settings.Setting<?> setting = findSetting(key);
                if (setting == null) {
                    reply("error", "setting-get", "unknown setting '" + key + "'", correlation);
                    return;
                }
                reply("ok", "setting-get", "key=" + key + " value="
                        + new SettingsStore(activity).get(setting), correlation);
            } catch (Throwable t) {
                reply("error", "setting-get", "threw " + t, correlation);
            }
        });
    }

    static Settings.Setting<?> findSetting(String key) {
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (setting.key.equals(key)) return setting;
        }
        return null;
    }

    /**
     * Settings committed only through {@link SourcePreferencesAdapter},
     * which writes the ordinary store and the source namespace together. A raw CLI write to one
     * namespace would diverge them, so the channel refuses these keys outright.
     */
    static boolean isAdapterOwnedSetting(Settings.Setting<?> setting) {
        return setting == Settings.LYRICS_SOURCE_OVERRIDE
                || setting == Settings.LYRICS_SOURCE_ORDER;
    }

    /** The value as the setting's own default types it, or null when the token is not that type.
     *  Deliberately strict: {@code Boolean.parseBoolean} turns any misspelling into {@code false},
     *  which would be a write that looks like it worked. */
    static Object coerceTyped(Settings.Setting<?> setting, String raw) {
        Object defaultValue = setting.defaultValue;
        if (defaultValue instanceof Boolean) {
            String token = raw.toLowerCase(Locale.ROOT);
            if ("true".equals(token) || "on".equals(token) || "1".equals(token)) return Boolean.TRUE;
            if ("false".equals(token) || "off".equals(token) || "0".equals(token)) return Boolean.FALSE;
            return null;
        }
        if (defaultValue instanceof Integer) {
            try {
                return Integer.valueOf(raw.trim());
            } catch (NumberFormatException notAnInt) {
                return null;
            }
        }
        return raw;
    }

    private static boolean isAllowed(Settings.Setting<?> setting, Object value) {
        List<?> allowed = setting.allowedValues;
        return allowed == null || allowed.isEmpty() || allowed.contains(value);
    }

    /**
     * The live shell, found by walking the current activity's decor view: the module adds it
     * itself, so there is no static handle to keep honest and nothing to register.
     *
     * @return null after already replying {@code error}, so callers can just return.
     */
    private NativeSpicyShellView requireShell(String verb, String correlation) {
        Activity activity = References.currentActivity();
        if (activity == null) {
            reply("error", verb, "no current activity", correlation);
            return null;
        }
        if (activity.isDestroyed() || activity.getWindow() == null) {
            reply("error", verb, "no current window", correlation);
            return null;
        }
        NativeSpicyShellView shell = findShell(activity.getWindow().getDecorView());
        if (shell == null) {
            reply("error", verb, "no Spicy lyrics screen is open; open fullscreen lyrics once",
                    correlation);
        }
        return shell;
    }

    private static NativeSpicyShellView findShell(View view) {
        if (view instanceof NativeSpicyShellView) return (NativeSpicyShellView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            NativeSpicyShellView found = findShell(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /** Reads the actual footer view, so the device gate verifies rendered UI instead of inferring it. */
    private void readFooter(String correlation) {
        main.accept(() -> {
            try {
                Activity activity = References.currentActivity();
                if (activity == null || activity.isDestroyed() || activity.getWindow() == null) {
                    reply("error", "footer", "no current activity", correlation);
                    return;
                }
                List<TextView> matches = new ArrayList<>();
                findSourceFooters(activity.getWindow().getDecorView(), matches);
                if (matches.size() != 1) {
                    reply("error", "footer", "expected one source footer, found "
                            + matches.size(), correlation);
                    return;
                }
                String text = firstLine(matches.get(0).getText());
                reply("ok", "footer", "track=" + trackLabel() + " text=" + text,
                        correlation);
            } catch (Throwable t) {
                reply("error", "footer", "threw " + t, correlation);
            }
        });
    }

    /**
     * Restores the exact track changed by a gate. If playback advanced, update that old track's
     * catalog directly instead of accidentally applying the restore command to the new track.
     */
    private void restoreSelection(String argument, String correlation) {
        String[] args = argument.split("\\s+");
        if (args.length < 2 || !args[0].startsWith("spotify:track:")) {
            reply("error", "restore-selection", "expected track uri and AUTO or MANUAL",
                    correlation);
            return;
        }
        String uri = args[0];
        String mode = args[1].toUpperCase(Locale.ROOT);
        String candidate = args.length > 2 ? args[2] : "";
        if (!("AUTO".equals(mode) || ("MANUAL".equals(mode) && !candidate.isEmpty()))) {
            reply("error", "restore-selection", "invalid mode or candidate", correlation);
            return;
        }
        SpotifyTrack current = host.getCurrentTrackSafely();
        if (current != null && uri.equals(current.uri)) {
            LyricsHost.CatalogActionCallback callback = (ok, detail) -> reply(
                    ok ? "ok" : "error", "restore-selection",
                    explain(ok, detail, uri), correlation);
            if ("MANUAL".equals(mode)) host.selectCatalogCandidate(candidate, callback);
            else host.resetCatalogToAuto(callback);
            return;
        }
        NativeRuntime.LYRICS_IO.execute(() -> {
            try {
                SpotifyTrack target = new SpotifyTrack("", "", "", uri, 0L, "", 0L,
                        null, 0L, false);
                LyricsCatalog.View view = LyricsCatalog.command(context, target,
                        "MANUAL".equals(mode) ? LyricsCatalog.select(candidate)
                                : LyricsCatalog.resetAuto());
                boolean ok = view != null && view.durable;
                reply(ok ? "ok" : "error", "restore-selection",
                        ok ? "Selection restored" : "Could not restore selection", correlation);
            } catch (Throwable t) {
                reply("error", "restore-selection", "threw " + t, correlation);
            }
        });
    }

    private static void findSourceFooters(View view, List<TextView> matches) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            CharSequence description = text.getContentDescription();
            if (description != null && description.toString().startsWith("Lyrics source:")
                    && firstLine(text.getText()).startsWith("Source: ")) {
                matches.add(text);
            }
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            findSourceFooters(group.getChildAt(i), matches);
        }
    }

    static String firstLine(CharSequence text) {
        if (text == null) return "";
        String value = text.toString().trim();
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline).trim();
    }

    private static CatalogSource.SourceId parseSource(String token) {
        if (token.isEmpty()) return null;
        String value = token.trim().toUpperCase(Locale.ROOT);
        for (CatalogSource.SourceId source : CatalogSource.SourceId.values()) {
            if (source.name().equals(value)) return source;
        }
        return null;
    }

    private String trackLabel() {
        try {
            SpotifyTrack current = host.getCurrentTrackSafely();
            if (current == null || current.uri == null || current.uri.isEmpty()) return "none";
            return current.uri;
        } catch (Throwable t) {
            return "error";
        }
    }

    private void reply(String result, String verb, String detail) {
        reply(result, verb, detail, "");
    }

    private void reply(String result, String verb, String detail, String correlation) {
        String line = MARK + " " + result + " " + verb
                + (detail == null || detail.isEmpty() ? "" : " " + detail.replace('\n', ' '))
                + (correlation == null || correlation.isEmpty() ? "" : " #" + correlation);
        XpLog.log(TAG + " " + line);
        if (dir == null) return;
        try (FileOutputStream stream = new FileOutputStream(new File(dir, OUT), true)) {
            stream.write((line + "\n").getBytes(UTF8));
        } catch (Throwable t) {
            // The log line still carries the reply, but a caller reading the out file would see
            // nothing and time out. Say so once, or that failure is indistinguishable from silence.
            if (!outWriteFailed) {
                outWriteFailed = true;
                XpLog.log(TAG + " cannot write " + OUT + " in " + dir.getAbsolutePath()
                        + "; a scripted caller will time out. " + t);
            }
        }
    }
}
