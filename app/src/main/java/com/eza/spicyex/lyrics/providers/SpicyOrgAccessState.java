package com.eza.spicyex.lyrics.providers;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.CopyOnWriteArraySet;

/** A confirmed access loss blocks cached lyrics until the server confirms restored access. */
public final class SpicyOrgAccessState {
    private static final String STATE = "SpotifyPlusSpicyOrgAccessState";
    private static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();

    private SpicyOrgAccessState() { }

    public static final class RequestTicket {
        public final long keyEpoch;
        private final long revision;
        private final boolean recovery;

        private RequestTicket(long keyEpoch, long revision, boolean recovery) {
            this.keyEpoch = keyEpoch;
            this.revision = revision;
            this.recovery = recovery;
        }
    }

    public static boolean isTerminated(Context context) {
        synchronized (SpicyOrgKeyStore.class) {
            return context != null && state(context).getBoolean("terminated", false);
        }
    }

    public static long revision(Context context) {
        synchronized (SpicyOrgKeyStore.class) {
            return context == null ? 0L : state(context).getLong("revision", 0L);
        }
    }

    public static boolean canAcquire(Context context) {
        return beginRequest(context, false) != null;
    }

    /** A new key can be checked, but rotation alone cannot restore cached lyric display. */
    public static RequestTicket beginRequest(Context context, boolean explicitRecovery) {
        synchronized (SpicyOrgKeyStore.class) {
            if (context == null) return null;
            SharedPreferences state = state(context);
            long epoch = SpicyOrgKeyStore.epoch(context);
            if (state.getBoolean("terminated", false)
                    && state.getLong("terminated_epoch", -1L) == epoch && !explicitRecovery) {
                return null;
            }
            return new RequestTicket(epoch, state.getLong("revision", 0L), explicitRecovery);
        }
    }

    public static boolean terminate(Context context, RequestTicket ticket, String stableCode) {
        if (!SpicyOrgProtocol.terminatesAccess(stableCode)) return false;
        synchronized (SpicyOrgKeyStore.class) {
            if (!current(context, ticket)) return false;
            state(context).edit().putBoolean("terminated", true)
                    .putLong("terminated_epoch", ticket.keyEpoch)
                    .putLong("revision", ticket.revision + 1L).commit();
        }
        changed();
        return true;
    }

    /** Call only after the matching successful response and its lyrics pass validation. */
    public static boolean acceptValidatedResponse(Context context, RequestTicket ticket) {
        boolean restored = false;
        synchronized (SpicyOrgKeyStore.class) {
            if (!current(context, ticket)) return false;
            SharedPreferences state = state(context);
            if (state.getBoolean("terminated", false)) {
                if (!ticket.recovery
                        && state.getLong("terminated_epoch", -1L) == ticket.keyEpoch) return false;
                state.edit().putBoolean("terminated", false).remove("terminated_epoch")
                        .putLong("revision", ticket.revision + 1L).commit();
                restored = true;
            }
        }
        if (restored) changed();
        return true;
    }

    /** Listeners must post work to their owner thread and read the latest state when it runs. */
    public static void addListener(Runnable listener) { if (listener != null) listeners.add(listener); }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    private static boolean current(Context context, RequestTicket ticket) {
        return context != null && ticket != null
                && SpicyOrgKeyStore.epoch(context) == ticket.keyEpoch
                && state(context).getLong("revision", 0L) == ticket.revision;
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    private static void changed() {
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }
}
