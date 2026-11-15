package com.eza.spicyex.hooks;

import android.content.Intent;

import com.eza.spicyex.xposed.XpLog;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Package-local ownership table for activity results the module starts from Spotify's process.
 *
 * <p>A pending result belongs to the Activity instance that started it, not to the request code
 * alone: two Spotify activities may use the same code, and a result must never reach a callback
 * owned by another instance. Each launch also keeps its own attempt identity so a late launch
 * failure cannot release a newer attempt.
 *
 * <p>Only one attempt per Activity instance and code may be pending. A second launch would be
 * indistinguishable from the first when Android returns a bare request code, so it is refused
 * instead of replacing the callback that is already waiting.
 *
 * <p>Destruction of the owning Activity releases the callback without invoking it: the owner's UI
 * is gone, and a posted delivery that has not run yet is suppressed for the same reason.
 */
final class ActivityResultRequests {
    private static final String TAG = "[SpicyActivityResult]";

    /** Posts a delivery to the owner's main thread. */
    interface Poster {
        void post(Runnable runnable);
    }

    /** Starts the host activity from the owning Activity. */
    interface Launch {
        void launch(int requestCode) throws Throwable;
    }

    private static final class Attempt {
        final Owner owner;
        final int requestCode;
        final Consumer<Intent> onResult;

        Attempt(Owner owner, int requestCode, Consumer<Intent> onResult) {
            this.owner = owner;
            this.requestCode = requestCode;
            this.onResult = onResult;
        }
    }

    private static final class Owner {
        final Object activity;
        final Map<Integer, Attempt> pending = new LinkedHashMap<>();
        int posted;
        boolean retired;

        Owner(Object activity) {
            this.activity = activity;
        }
    }

    private final Poster poster;
    private final IdentityHashMap<Object, Owner> owners = new IdentityHashMap<>();

    ActivityResultRequests(Poster poster) {
        this.poster = poster;
    }

    /**
     * Registers and starts one attempt. The callback receives null when the launch is refused or
     * throws, matching the previous failure contract; it runs at most once per attempt.
     */
    void start(Object activity, int requestCode, Consumer<Intent> onResult, Launch launch) {
        Attempt attempt = begin(activity, requestCode, onResult);
        if (attempt == null) {
            XpLog.log(TAG + " start refused: request " + requestCode + " already pending");
            if (onResult != null) onResult.accept(null);
            return;
        }
        try {
            launch.launch(requestCode);
        } catch (Throwable t) {
            // A result that arrived synchronously already released this attempt; never replace
            // that delivery with a failure callback.
            if (failLaunch(attempt)) {
                XpLog.log(TAG + " start failed: " + t);
                onResult.accept(null);
            }
        }
    }

    /**
     * Consumes the attempt owned by {@code activity} for {@code requestCode}. Returns false when
     * the result belongs to the host, a different activity, or an attempt that is already done.
     */
    boolean dispatch(Object activity, int requestCode, boolean resultOk, Intent data) {
        Attempt attempt;
        Owner owner;
        synchronized (this) {
            owner = owners.get(activity);
            if (owner == null || owner.retired) return false;
            attempt = owner.pending.remove(requestCode);
            if (attempt == null) {
                prune(owner);
                return false;
            }
            owner.posted++;
        }
        Intent delivered = resultOk ? data : null;
        poster.post(() -> deliver(owner, attempt, delivered));
        return true;
    }

    /** Releases everything owned by a destroyed Activity without delivering to its retired UI. */
    synchronized void destroy(Object activity) {
        Owner owner = owners.remove(activity);
        if (owner == null) return;
        owner.retired = true;
        owner.pending.clear();
    }

    private synchronized Attempt begin(Object activity, int requestCode, Consumer<Intent> onResult) {
        if (activity == null || onResult == null) return null;
        Owner owner = owners.get(activity);
        if (owner == null) {
            owner = new Owner(activity);
            owners.put(activity, owner);
        }
        if (owner.retired || owner.pending.containsKey(requestCode)) return null;
        Attempt attempt = new Attempt(owner, requestCode, onResult);
        owner.pending.put(requestCode, attempt);
        return attempt;
    }

    private synchronized boolean failLaunch(Attempt attempt) {
        if (attempt.owner.retired) return false;
        if (attempt.owner.pending.get(attempt.requestCode) != attempt) return false;
        attempt.owner.pending.remove(attempt.requestCode);
        prune(attempt.owner);
        return true;
    }

    private void deliver(Owner owner, Attempt attempt, Intent data) {
        boolean allowed;
        synchronized (this) {
            allowed = !owner.retired;
        }
        try {
            if (allowed) attempt.onResult.accept(data);
        } catch (Throwable t) {
            XpLog.log(TAG + " consumer failed: " + t);
        } finally {
            synchronized (this) {
                owner.posted--;
                prune(owner);
            }
        }
    }

    /** Drops an idle owner so the table never keeps a live Activity for an idle request. */
    private void prune(Owner owner) {
        if (owner.pending.isEmpty() && owner.posted == 0
                && owners.get(owner.activity) == owner) {
            owners.remove(owner.activity);
        }
    }
}
