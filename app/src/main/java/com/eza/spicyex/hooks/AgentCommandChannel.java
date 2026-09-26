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
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import com.eza.spicyex.lyrics.catalog.LyricsCatalog;
import com.eza.spicyex.xposed.XpLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A file-based command channel so an automated agent can drive the catalog without tapping.
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
 *   <li>{@code auto} — drop any manual pin and re-elect the automatic winner</li>
 *   <li>{@code climb} — ask the whole quality chain in order, bypassing the racing chain</li>
 *   <li>{@code check <source>} — ask one source, e.g. {@code check apple}</li>
 *   <li>{@code select-candidate <id>} — pin one exact stored catalog candidate</li>
 *   <li>{@code restore-selection <uri> <mode> [id]} — restore a gate's previous seat</li>
 *   <li>{@code footer} — read the source footer currently rendered by the lyrics surface</li>
 *   <li>{@code picker} — open the source picker, for the rare case a human needs to see it</li>
 * </ul>
 *
 * <p>Each command appends exactly one {@code SPICY_AGENT} line to {@code out} and to the module
 * log. Results are appended rather than overwritten so a caller can fire several commands and read
 * them back in order.
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
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService worker;
    /** Last command text dispatched, so an undeletable command file cannot cause a poll loop. */
    private String lastDispatched = "";
    /** Set once the reply file has proven unwritable, so the failure is logged exactly one time. */
    private boolean outWriteFailed;

    private AgentCommandChannel(LyricsHost host, Context context) {
        this.host = host;
        this.context = context;
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

    private void dispatch(String raw) {
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
        try {
            switch (verb) {
                case "status":
                    reply("ok", verb, "track=" + track, correlation);
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
                    openPicker(correlation);
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

    private void openPicker(String correlation) {
        Activity activity = References.currentActivity();
        if (activity == null) {
            reply("error", "picker", "no current activity", correlation);
            return;
        }
        main.post(() -> {
            try {
                // The picker needs the same collaborators the shell chrome passes in: the host and
                // the interface-language strings.
                LyricsSourcePickerDialog.show(activity, host,
                        com.eza.spicyex.UiLanguage.strings(activity, null),
                        message -> reply("ok", "picker", message, correlation));
            } catch (Throwable t) {
                reply("error", "picker", "threw " + t, correlation);
            }
        });
    }

    /** Reads the actual footer view, so the device gate verifies rendered UI instead of inferring it. */
    private void readFooter(String correlation) {
        main.post(() -> {
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
