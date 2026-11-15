package com.eza.spicyex.auto;

import android.content.ComponentName;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Display;
import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Supplies native AndroidView content inside the discovered Compose slots. */
public final class AndroidAutoPrototype {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<AutoLyricsView>> canvases = new ArrayList<>();
    private static final AutoSurfaceReadiness surfaceReadiness = new AutoSurfaceReadiness();
    private static final AutoCompactArtwork compactArtwork = new AutoCompactArtwork();
    private static volatile AutoCompositionRecovery weatherBootstrap;
    private static final List<WeakReference<Object>> scopes = new ArrayList<>();
    private static boolean lastReady, lastWeather, lastSpotify, lastEnabled, lastFullReady, lastPlayerReady;
    private static final AutoArtworkRelease artworkRelease = new AutoArtworkRelease();
    private static final AutoResponseCredit.Window creditWindow = new AutoResponseCredit.Window();
    private static float nativeAlpha = 1f;
    private static final Runnable releaseFrame = () -> refresh();
    private static boolean takeover;
    private static int lastDisplaySettings = -1;
    private static final AutoPrototypePolicy.PlaybackHold playbackHold = new AutoPrototypePolicy.PlaybackHold();
    private static volatile Bundle state;
    private static volatile boolean enabled;
    private static volatile WeakReference<Object> mediaModel = new WeakReference<>(null);
    private static volatile Bundle hostStatus = new Bundle();
    private static AndroidAutoResolver.Results symbols = new AndroidAutoResolver.Results();
    private static final AutoHookInstallation installation = new AutoHookInstallation();
    private static volatile boolean sharedCoreInstalled, compactInstalled, fullInstalled;
    private static long modernWeatherCalls, legacyWeatherCalls, modernWeatherEmissions, legacyWeatherEmissions;
    private static long modernWeatherErrors, legacyWeatherErrors;
    private static String lastWeatherError = "";
    private static boolean started;
    private static int weatherCallersDeoptimized;
    private static long weatherCalls, mediaCalls, contentCalls, artSuppressed;
    private static volatile long connectionWeatherEmissions;
    private static long weatherDispatchCalls;
    private static boolean weatherInstalled, weatherDeoptimized;
    private static final List<WeakReference<Object>> weatherOwners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile long installCompletedAtMs, firstWeatherCallAtMs, firstWeatherDispatchAtMs;
    private static Object weatherFactory, playerFactory, fullFactory, update, lyricSlot, lyricTextSlot, emptySlot, unit;
    private static Object weatherSizeObserver, weatherObserverCheck;
    private static AutoWeatherSizeObserver weatherObserverAttachment;
    private static long weatherSizeErrors;
    private static String lastWeatherSizeError = "";
    private static Object compactInteraction;
    private static float compactWidth;
    private static float fullContentHeightDp;
    private static final ThreadLocal<Boolean> compactFocusDepth = new ThreadLocal<>();
    private static final ThreadLocal<Integer> backgroundDepth = new ThreadLocal<>();
    private static final ThreadLocal<Integer> badgeDepth = new ThreadLocal<>();
    private static final AutoFullBadgeOwnership fullBadgeOwnership = new AutoFullBadgeOwnership();
    private static long fullOwnedBadgeCalls;
    private static final ThreadLocal<Integer> fullMetadataDepth = new ThreadLocal<>();
    private static final ThreadLocal<Integer> fullMetadataReplaced = new ThreadLocal<>();
    private static int explicitBadgeId;
    private static long badgesSuppressed, fullBadgeCalls, fullBadgeMatches, songInfoSuppressed;
    private static long progressSuppressed, thumbnailsSuppressed, navigationSuppressed;
    private static float backgroundAlpha = 1f;
    private static Object backgroundOpacity;
    private static long backgroundFadeStarted, backgroundFades;
    private static String backgroundFadeSamples = "";
    private static final Runnable backgroundFrame = new Runnable() {
        @Override public void run() {
            if (backgroundFadeStarted == 0) return;
            backgroundAlpha = Math.max(0f, 1f - (android.os.SystemClock.elapsedRealtime() - backgroundFadeStarted)
                    / (float) Math.max(300, com.eza.spicyex.ui.Motion.dur(300)));
            writeBackgroundOpacity();
            if (backgroundAlpha > 0f) main.postDelayed(this, 32);
            else backgroundFadeStarted = 0;
        }
    };

    public static synchronized void install(Context context, ClassLoader loader) {
        if (started || !android.app.Application.getProcessName().endsWith(":projection")) return;
        started = true;
        new Thread(() -> {
            sharedCoreInstalled = installStage("shared-core", () -> {
                int id = context.getResources().getIdentifier("temperature_unavailable", "string", context.getPackageName());
                explicitBadgeId = context.getResources().getIdentifier("gs_explicit_fill1_vd_theme_24", "drawable", context.getPackageName());
                symbols = AndroidAutoResolver.resolve(context.getApplicationInfo().sourceDir, loader, id, explicitBadgeId,
                        AndroidAutoPrototype::installWeatherEarly);
                XpLog.log("[SpicyAuto] discovery " + symbols.diagnostics);
                if (symbols.androidViewEmitter == null || symbols.androidViewNoOp == null
                        || symbols.emptyModifier == null || symbols.currentScope == null
                        || symbols.markScopeUsed == null || symbols.invalidateScope == null
                        || symbols.mediaComposeEntry == null || symbols.mediaModelDump == null
                        || symbols.mediaObservableRead == null || symbols.mediaModelGetters.length == 0)
                    throw new IllegalStateException("Shared Compose/Spotify owner prerequisites unresolved");
                Class<?> function = symbols.androidViewEmitter.getParameterTypes()[0];
                Method invoke = function.getMethod("invoke", Object.class);
                unit = invoke.invoke(symbols.androidViewNoOp, new Object[]{null});
                weatherFactory = factory(function, "weather");
                playerFactory = factory(function, "player");
                fullFactory = factory(function, "player-full");
                update = callback(function, args -> {
                    Bundle s = state;
                    if (wantsLyrics()) ((AutoLyricsView) args[0]).update(s);
                    return unit;
                });
                XpHooks.hookBefore(symbols.mediaComposeEntry, "auto:media-owner", p -> {
                    rememberScope(p.args[p.args.length - 4]);
                    mediaCalls++;
                    Class<?> type = symbols.mediaModelDump.getDeclaringClass();
                    for (Object arg : p.args) if (type.isInstance(arg)) mediaModel = new WeakReference<>(arg);
                });
            });
            if (sharedCoreInstalled) {
                installStage("navigation", () -> {
                    if (symbols.dashboardCardsRead == null || symbols.dashboardLayout == null) throw new IllegalStateException("navigation unresolved");
                    XpHooks.hookBefore(symbols.dashboardLayout, "auto:dashboard-layout", p -> {
                        rememberWeatherOwner(p.args[6]);
                        rememberScope(p.args[6]);
                    });
                    XpHooks.hookAfter(symbols.dashboardCardsRead, "auto:navigation-suggestions", p -> {
                        if (!enabled
                                || !(p.getResult() instanceof List)) return;
                        List<?> original = (List<?>) p.getResult();
                        List<?> filtered = AutoDashboardCards.withoutSuggestion(original, symbols.navigationSuggestionCard);
                        if (filtered != original) { navigationSuppressed++; p.setResult(filtered); }
                    });
                });
                installStage("full-owner", () -> {
                    if (symbols.fullMediaDispatcher == null) throw new IllegalStateException("full-owner unresolved");
                    XpHooks.hookBefore(symbols.fullMediaDispatcher, "auto:full-owner", p -> {
                        float height = (Float) p.args[1];
                        if (height != fullContentHeightDp) surfaceReadiness.layoutChanged("player-full");
                        fullContentHeightDp = height;
                        rememberScope(p.args[4]);
                    });
                });
                installStage("full-progress", () -> {
                    if (symbols.fullMediaProgress == null) throw new IllegalStateException("full-progress unresolved");
                    XpHooks.hookBefore(symbols.fullMediaProgress, "auto:short-progress", p -> {
                        rememberScope(p.args[2]);
                        if (fullInstalled && AutoPrototypePolicy.suppressFullProgress(fullReady(), isSpotifySelected(), fullContentHeightDp)) {
                            progressSuppressed++;
                            p.setResult(null);
                        }
                    });
                });
                installStage("full-metadata", () -> {
                    if (symbols.fullMediaContent.length == 0) throw new IllegalStateException("full-metadata unresolved");
                    for (Method method : symbols.fullMediaContent) XpHooks.hook(method, "auto:full-badge-scope", p -> {
                        Object composer = p.args[p.args.length - 2];
                        rememberScope(composer);
                        if (AutoPrototypePolicy.suppressFullSongInfo(fullInstalled && fullReady(),
                                isSpotifySelected(), AutoDisplaySettings.extraLine(state))) {
                            // The extra line takes the whole media body. Replacing its parent
                            // removes the artwork and the native title/artist row together.
                            songInfoSuppressed++;
                            contentCalls++;
                            Integer replaced = fullMetadataReplaced.get();
                            fullMetadataReplaced.set(replaced == null ? 1 : replaced + 1);
                            emit(fullFactory, symbols.fillContentModifier.invoke(null,
                                    p.args[1] == null ? symbols.emptyModifier : p.args[1], 1.0f), composer);
                            p.setResult(null);
                            return;
                        }
                        if (fullInstalled && !fullReady() && nativeAlpha < 1f && isSpotifySelected()) {
                            p.args[1] = nativeFadeModifier(p.args[1]);
                            p.args[3] = ((Integer) p.args[3]) & ~112;
                        }
                        Integer depth = fullMetadataDepth.get(); fullMetadataDepth.set(depth == null ? 1 : depth + 1);
                    }, p -> {
                        Integer replaced = fullMetadataReplaced.get();
                        if (replaced != null) {
                            if (replaced <= 1) fullMetadataReplaced.remove(); else fullMetadataReplaced.set(replaced - 1);
                            return;
                        }
                        Integer depth = fullMetadataDepth.get();
                        if (depth == null || depth <= 1) fullMetadataDepth.remove(); else fullMetadataDepth.set(depth - 1);
                    });
                });
                installStage("full-badge", () -> {
                    if (symbols.fullBadgePainter == null || symbols.fullBadgeDrawable == null) throw new IllegalStateException("full-badge unresolved");
                    XpHooks.hook(symbols.fullBadgePainter, "auto:full-badge-observe", p -> {
                        fullBadgeCalls++;
                        if (isExplicitIcon(symbols.fullBadgeIcon.get(p.thisObject))) {
                            fullBadgeMatches++; rememberScope(p.args[1]);
                        }
                        // Ownership persists when the inline child recomposes outside its metadata parent.
                        boolean owned = fullBadgeOwnership.enter(p.thisObject, fullMetadataDepth.get() != null, badgeDepth.get() != null);
                        if (owned) fullOwnedBadgeCalls++;
                    }, p -> fullBadgeOwnership.exit());
                    XpHooks.hookAfter(symbols.fullBadgeDrawable, "auto:full-explicit-drawable", p -> {
                        if (fullBadgeOwnership.suppress(fullInstalled && fullReady(), isSpotifySelected())
                                && isExplicitIcon(p.args[1])) {
                            badgesSuppressed++;
                            p.setResult(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                        }
                    });
                });
                installStage("compact-badge", () -> {
                    if (explicitBadgeId == 0) throw new IllegalStateException("compact-badge unresolved");
                    if (symbols.mediaBadgeContent != null && symbols.mediaBadgeListRead != null && symbols.mediaBadgeTextScope != null) {
                        XpHooks.hook(symbols.mediaBadgeContent, "auto:badge-scope", p -> {
                            if (!symbols.mediaBadgeTextScope.isInstance(p.args[0])) return;
                            rememberScope(p.args[1]);
                            Integer depth = badgeDepth.get();
                            badgeDepth.set(depth == null ? 1 : depth + 1);
                        }, p -> {
                            if (!symbols.mediaBadgeTextScope.isInstance(p.args[0])) return;
                            Integer depth = badgeDepth.get();
                            if (depth == null || depth <= 1) badgeDepth.remove(); else badgeDepth.set(depth - 1);
                        });
                        XpHooks.hookAfter(symbols.mediaBadgeListRead, "auto:explicit-badge", p -> {
                            if (!compactInstalled || badgeDepth.get() == null || !AutoPrototypePolicy.suppressCompactBadge(surfaceReady("player"), isSpotifySelected(), weatherVisible()) || !(p.getResult() instanceof List)) return;
                            List<?> original = (List<?>) p.getResult();
                            List<?> filtered = AutoMetadataBadges.withoutExplicit(original, explicitBadgeId);
                            if (filtered != original) { badgesSuppressed++; p.setResult(filtered); }
                        });
                        return;
                    }
                    // The compact composable signature changes between host releases. The
                    // metadata badge producer is the stable source of the same transient list.
                    if (symbols.explicitBadgeProducer == null) throw new IllegalStateException("compact-badge unresolved");
                    XpHooks.hookAfter(symbols.explicitBadgeProducer, "auto:explicit-badge-producer", p -> {
                        if (!compactInstalled || !AutoPrototypePolicy.suppressCompactBadge(surfaceReady("player"), isSpotifySelected(), weatherVisible()) || !(p.getResult() instanceof List)) return;
                        List<?> original = (List<?>) p.getResult();
                        List<?> filtered = AutoMetadataBadges.withoutExplicit(original, explicitBadgeId);
                        if (filtered != original) { badgesSuppressed++; p.setResult(filtered); }
                    });
                });
                fullInstalled = installStage("full-slot", () -> {
                    if (symbols.fullMediaArtwork.length == 0 || symbols.fillContentModifier == null)
                        throw new IllegalStateException("Full-player slot unresolved");
                    for (Method method : symbols.fullMediaArtwork) XpHooks.hookBefore(method, "auto:full-art", p -> {
                        Object composer = p.args[p.args.length - 2];
                        rememberScope(composer);
                        if (!fullInstalled || !fullReady() || !isSpotifySelected()) return;
                        artSuppressed++; contentCalls++;
                        emit(fullFactory, symbols.fillContentModifier.invoke(null, symbols.emptyModifier, 1.0f), composer);
                        p.setResult(null);
                    });
                });
                installStage("compact-thumbnail", () -> {
                    if (symbols.mediaThumbnail == null) throw new IllegalStateException("compact-thumbnail unresolved");
                    XpHooks.hookBefore(symbols.mediaThumbnail, "auto:compact-thumbnail", p -> {
                        rememberScope(p.args[3]);
                        if (!compactInstalled || !AutoPrototypePolicy.suppressCompactThumbnail(
                                surfaceReady("player"), isSpotifySelected(), weatherVisible())) return;
                        // Low-height cards keep a separate 32dp thumbnail beside native metadata.
                        thumbnailsSuppressed++;
                        p.setResult(null);
                    });
                });
                compactInstalled = installStage("compact-slot", () -> {
                    if (symbols.mediaContentSlot == null || symbols.mediaTextContainer == null
                            || symbols.mediaWrapHeightModifier == null || symbols.fillContentModifier == null
                            || symbols.columnWeightModifier == null || symbols.zeroMediaPadding == null
                            || symbols.mediaTextAlignment == null || symbols.mediaArtwork == null)
                        throw new IllegalStateException("Compact-player slot unresolved");
                    Class<?> slotType = symbols.mediaContentSlot.getParameterTypes()[0];
                    emptySlot = callback(slotType, args -> unit);
                    lyricTextSlot = callback(slotType, args -> {
                        // A Column measures ordinary children with unbounded height. Native
                        // weight allocates its finite remaining height to this lyric child.
                        Object fill = symbols.fillContentModifier.invoke(null, symbols.emptyModifier, 1.0f);
                        emit(playerFactory, symbols.columnWeightModifier.invoke(null, fill, 1.0f, true), args[1]);
                        return unit;
                    });
                    lyricSlot = callback(slotType, args -> {
                        // Reuse native metadata focus inside the bounded artwork space.
                        symbols.mediaTextContainer.invoke(null, lyricTextSlot, emptySlot, compactInteraction, compactWidth,
                                symbols.fillContentModifier.invoke(null, symbols.emptyModifier, 1.0f), true,
                                symbols.zeroMediaPadding, null, null, false, symbols.mediaTextAlignment, args[1], 0, 0, 128 | 256);
                        return unit;
                    });
                    XpHooks.hook(symbols.mediaTextContainer, "auto:metadata-space", p -> {
                        if (p.args[0] == emptySlot && p.args[1] == emptySlot) p.setResult(null);
                        if (p.args[0] == lyricTextSlot) compactFocusDepth.set(true);
                    }, p -> {
                        if (p.args[0] == lyricTextSlot) compactFocusDepth.remove();
                    });
                    XpHooks.hookBefore(symbols.mediaWrapHeightModifier, "auto:bounded-metadata-focus", p -> {
                        // Native text permits unlimited height. The lyric focus wrapper must
                        // keep the artwork region's maximum height so column weight can fill it.
                        if (compactFocusDepth.get() != null) p.args[2] = false;
                    });
                    XpHooks.hookBefore(symbols.mediaContentSlot, "auto:content-slot", p -> {
                        rememberScope(p.args[17]);
                        float width = (Float) p.args[5];
                        if (width != compactWidth) surfaceReadiness.layoutChanged("player");
                        compactWidth = width;
                        if (compactInstalled && !surfaceReady("player") && nativeAlpha < 1f && isSpotifySelected()) {
                            p.args[12] = nativeFadeModifier(p.args[12]);
                            p.args[20] = ((Integer) p.args[20]) & ~4096;
                        }
                        if (!compactInstalled || !surfaceReady("player") || !isSpotifySelected() || weatherVisible()) return;
                        contentCalls++;
                        compactInteraction = p.args[3];
                        // Use the native weighted artwork space. The text-only branch is
                        // measured with unbounded height and collapses an AndroidView to padding.
                        p.args[0] = emptySlot;
                        p.args[1] = emptySlot;
                        p.args[2] = lyricSlot;
                        p.args[18] = ((Integer) p.args[18]) & ~0x3fe;
                    });
                });
                installStage("compact-background", () -> {
                    if (symbols.mediaArtworkContent != null && symbols.mediaArtworkModifier != null && symbols.graphicsLayer != null
                            && symbols.floatStateConstructor != null && symbols.floatStateRead != null && symbols.floatStateWrite != null) {
                        backgroundOpacity = symbols.floatStateConstructor.newInstance(1f);
                        XpHooks.hook(symbols.mediaArtworkContent, "auto:background-content", p -> {
                            backgroundDepth.set(0);
                        }, p -> backgroundDepth.remove());
                        XpHooks.hookBefore(symbols.mediaArtworkModifier, "auto:background-opacity", p -> {
                            Integer depth = backgroundDepth.get();
                            if (depth == null) return;
                            backgroundDepth.set(depth + 1);
                            if (depth != 0) return;
                            float alpha = (Float) symbols.floatStateRead.invoke(backgroundOpacity);
                            // Observe state even before takeover so unchanged native artwork
                            // recomposes when the module starts its fade.
                            if (!compactInstalled || !isSpotifySelected() || weatherVisible()) alpha = 1f;
                            else if (!surfaceReady("player")) alpha = nativeAlpha;
                            if (backgroundFadeSamples.length() < 200) backgroundFadeSamples += alpha + ",";
                            p.args[1] = symbols.graphicsLayer.invoke(null, p.args[1], 0f, 0f, alpha,
                                    0f, 0f, null, false, 0, 131067);
                            artSuppressed++;
                        });
                    } else XpHooks.hookBefore(symbols.mediaArtwork, "auto:background-art", p -> {
                        rememberScope(p.args[5]);
                        if (compactInstalled && surfaceReady("player") && isSpotifySelected() && !weatherVisible()) { artSuppressed++; p.setResult(null); }
                    });
                });
                // The weather hook usually installs from the resolver callback, before the
                // unrelated families delay it. Retry here if that earlier stage failed.
                if (!weatherInstalled) installWeatherEarly(symbols);
            }
            installCompletedAtMs = android.os.SystemClock.elapsedRealtime();
            if (symbols.compositionSlots != null && sharedCoreInstalled
                    && "installed".equals(installation.error("weather-modern"))) {
                weatherBootstrap = new AutoCompositionRecovery(symbols.compositionTables,
                        symbols.compositionSlots, symbols.invalidateScope);
                // Owners observed before the bootstrap existed still deserve one refresh.
                for (WeakReference<Object> ref : weatherOwners) weatherBootstrap.remember(ref.get());
            }
            XpLog.log("[SpicyAuto] install complete after "
                    + (installCompletedAtMs - android.os.Process.getStartElapsedRealtime()) + "ms");
            if (!sharedCoreInstalled || (!weatherInstalled && !compactInstalled && !fullInstalled)) return;
            AutoPrototypeClient client = new AutoPrototypeClient(context);
            java.util.concurrent.ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
                    task -> new Thread(task, "SpicyAutoHost"));
            DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            DisplayManager.DisplayListener connection = new DisplayManager.DisplayListener() {
                private java.util.concurrent.ScheduledFuture<?> polling;
                private volatile int generation;
                private void changed() {
                    boolean connected = false;
                    for (Display display : displays.getDisplays())
                        if (display.getDisplayId() > 0 && display.getState() == Display.STATE_ON) connected = true;
                    if (connected == (polling != null)) return;
                    int version = ++generation;
                    if (connected) {
                        onConnection();
                        polling = worker.scheduleWithFixedDelay(() -> poll(version), 0, 100, TimeUnit.MILLISECONDS);
                    } else {
                        polling.cancel(false);
                        polling = null;
                        worker.execute(() -> {
                            try { client.callIfConnected("host", new Bundle()); }
                            catch (android.os.RemoteException ignored) { }
                            finally { client.close(); }
                        });
                        enabled = false; state = null; refresh();
                    }
                }
                private void poll(int version) {
                    if (version != generation) return;
                    try {
                        Bundle status = new Bundle(hostStatus);
                        status.putBoolean("connected", true);
                        client.call("host", status);
                        Bundle reply = client.call("state", null);
                        Bundle next = reply == null ? null : reply.getBundle("state");
                        if (next != null) next.putBoolean("stress", reply.getBoolean("stress"));
                        main.post(() -> {
                            if (version != generation) return;
                            enabled = reply != null && reply.getBoolean("enabled");
                            state = next;
                            refresh();
                        });
                    } catch (Throwable error) { main.post(() -> {
                        if (version != generation) return;
                        enabled = false; state = null; refresh();
                    }); }
                }
                @Override public void onDisplayAdded(int id) { changed(); }
                @Override public void onDisplayChanged(int id) { changed(); }
                @Override public void onDisplayRemoved(int id) { changed(); }
            };
            main.post(() -> {
                displays.registerDisplayListener(connection, main);
                connection.onDisplayChanged(Display.DEFAULT_DISPLAY);
            });
        }, "SpicyAutoDiscovery").start();
    }

    /**
     * Hook and deoptimize the weather entry as soon as its own symbols resolve. The native card
     * can compose during the remaining multi-family scan; every second of that window is a
     * window where the host draws weather itself and the module never sees it.
     */
    private static void installWeatherEarly(AndroidAutoResolver.Results resolved) {
        if (resolved == null || !resolved.weatherMapped() || resolved.weatherRenderer == null) return;
        if (!weatherInstalled) {
            weatherInstalled = installStage("weather-modern",
                    () -> installWeatherSlot(resolved.weatherRenderer, 2, "auto:weather-slot"));
            if (!weatherInstalled) return;
            if (resolved.legacyWeatherRenderer != null) installStage("weather-legacy",
                    () -> installWeatherSlot(resolved.legacyWeatherRenderer, 4, "auto:legacy-weather-slot"));
            // A dispatcher call without a renderer call proves the entry is bypassed rather
            // than merely late; both counters appear in the provider status.
            if (resolved.dashboardDispatcher != null) XpHooks.hookBefore(resolved.dashboardDispatcher,
                    "auto:weather-dispatch", p -> {
                        weatherDispatchCalls++;
                        if (firstWeatherDispatchAtMs != 0) return;
                        firstWeatherDispatchAtMs = android.os.SystemClock.elapsedRealtime();
                        XpLog.log("[SpicyAuto] first weather dispatch after "
                                + (firstWeatherDispatchAtMs - android.os.Process.getStartElapsedRealtime()) + "ms");
                    });
        }
        deoptimizeWeatherCallers(resolved);
    }

    /** Run only after the hook exists: compiled direct and restart calls must land in it. */
    private static void deoptimizeWeatherCallers(AndroidAutoResolver.Results resolved) {
        if (weatherDeoptimized) return;
        weatherDeoptimized = true;
        for (Method caller : resolved.weatherCallers) {
            try {
                if (XpHooks.deoptimize(caller)) weatherCallersDeoptimized++;
                else XpLog.log("[SpicyAuto] weather caller deoptimization rejected " + caller);
            } catch (Throwable error) {
                XpLog.log("[SpicyAuto] weather caller deoptimization failed " + error);
            }
        }
        XpLog.log("[SpicyAuto] weather callers deoptimized " + weatherCallersDeoptimized
                + "/" + resolved.weatherCallers.length);
    }

    /**
     * Remember compositions that can own the native weather subtree. Media and full-player
     * composers are excluded: invalidating them only recomposes unrelated content.
     */
    private static void rememberWeatherOwner(Object composer) {
        if (composer == null) return;
        AutoCompositionRecovery bootstrap = weatherBootstrap;
        if (bootstrap != null) { bootstrap.remember(composer); return; }
        for (WeakReference<Object> ref : weatherOwners) if (ref.get() == composer) return;
        if (weatherOwners.size() < 8) weatherOwners.add(new WeakReference<>(composer));
    }

    private static boolean installStage(String role, AutoHookInstallation.Action action) {
        boolean installed = installation.attempt(role, action);
        if (!installed) XpLog.log("[SpicyAuto] install " + role + " failed " + installation.error(role));
        return installed;
    }

    private static void installWeatherSlot(Method renderer, int composerIndex, String id) {
        boolean legacy = composerIndex == 4;
        XpHooks.hookBefore(renderer, id, p -> {
            if (firstWeatherCallAtMs == 0) {
                firstWeatherCallAtMs = android.os.SystemClock.elapsedRealtime();
                XpLog.log("[SpicyAuto] first weather call after "
                        + (firstWeatherCallAtMs - android.os.Process.getStartElapsedRealtime()) + "ms");
            }
            weatherCalls++;
            if (legacy) legacyWeatherCalls++; else modernWeatherCalls++;
            try {
                rememberWeatherOwner(p.args[composerIndex]);
                // The hook installs before the shared Compose core finishes resolving. Until the
                // factory exists the module cannot emit, so the native card must draw instead.
                if (weatherFactory == null || update == null) return;
                rememberScope(p.args[composerIndex]);
                if (symbols.weatherSizeModifier != null) {
                    if (weatherSizeObserver == null) {
                        weatherObserverAttachment = new AutoWeatherSizeObserver(symbols.weatherModifierAll,
                                symbols.weatherSizeModifier, symbols.weatherObserverCallback);
                        Class<?> function = symbols.androidViewEmitter.getParameterTypes()[0];
                        weatherSizeObserver = callback(function, args -> {
                            try {
                                long size = symbols.weatherSizeValue.getLong(args[0]);
                                if (surfaceReadiness.measured("weather", (int) (size >> 32), (int) size))
                                    main.post(() -> { refresh(); recompose(); });
                            } catch (Throwable error) {
                                weatherSizeErrors++;
                                lastWeatherSizeError = AutoHookInstallation.describe(error);
                            }
                            return unit;
                        });
                        weatherObserverCheck = callback(function, args ->
                                weatherObserverAttachment.isOtherElement(args[0], weatherSizeObserver));
                    }
                    // Native restart closures retain this modifier. Inspect the whole chain before appending.
                    p.args[0] = weatherObserverAttachment.attach(
                            p.args[0] == null ? symbols.emptyModifier : p.args[0], weatherSizeObserver, weatherObserverCheck);
                    p.args[p.args.length - 1] = ((Integer) p.args[p.args.length - 1]) & ~14;
                }
                if (!surfaceReady("weather")) {
                    if (nativeAlpha < 1f && isSpotifySelected()) p.args[0] = nativeFadeModifier(p.args[0]);
                    return;
                }
                emit(weatherFactory, p.args[0], p.args[composerIndex]);
                if (legacy) legacyWeatherEmissions++; else modernWeatherEmissions++;
                connectionWeatherEmissions++;
                p.setResult(null);
            } catch (Throwable error) {
                if (legacy) legacyWeatherErrors++; else modernWeatherErrors++;
                lastWeatherError = id + ": " + AutoHookInstallation.describe(error);
                throw error; // The protective hook chain preserves native fallback.
            }
        });
    }

    private static boolean isExplicitIcon(Object carIcon) throws ReflectiveOperationException {
        Object icon = carIcon == null ? null : symbols.carIconRead.invoke(carIcon);
        if (icon == null) return false;
        try {
            return explicitBadgeId == (Integer) symbols.iconResourceId.invoke(icon)
                    && "com.google.android.projection.gearhead".equals(symbols.iconResourcePackage.invoke(icon));
        } catch (java.lang.reflect.InvocationTargetException ignored) { return false; }
    }

    private static void writeBackgroundOpacity() {
        if (backgroundOpacity == null) return;
        try { symbols.floatStateWrite.invoke(backgroundOpacity, backgroundAlpha); }
        catch (ReflectiveOperationException error) { XpLog.log("[SpicyAuto] opacity state failed " + error); }
    }
    static void playerDrawn() {
        if (backgroundFadeStarted != -1 || backgroundOpacity == null) return;
        backgroundFadeStarted = android.os.SystemClock.elapsedRealtime();
        main.postDelayed(backgroundFrame, 32);
    }

    private interface Function { Object call(Object[] args) throws Throwable; }
    private static Object callback(Class<?> type, Function function) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if ("invoke".equals(method.getName())) return function.call(args);
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return proxy == args[0];
            if ("toString".equals(method.getName())) return "SpicyAutoContent";
            return null;
        });
    }
    private static Object factory(Class<?> function, String target) {
        return callback(function, args -> {
            AutoLyricsView view = new AutoLyricsView((Context) args[0]);
            view.setTag(target);
            view.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1, -1));
            canvases.add(new WeakReference<>(view));
            Bundle s = state;
            if (s != null) view.update(s);
            return view;
        });
    }
    private static void emit(Object factory, Object modifier, Object composer) throws Exception {
        symbols.androidViewEmitter.invoke(null, factory, modifier == null ? symbols.emptyModifier : modifier, update, composer, 0, 0);
    }
    private static void rememberScope(Object composer) throws Exception {
        Object scope = symbols.currentScope.invoke(composer);
        if (scope == null) return;
        symbols.markScopeUsed.invoke(scope);
        for (WeakReference<Object> ref : scopes) if (ref.get() == scope) return;
        scopes.add(new WeakReference<>(scope));
    }
    private static void recompose() {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            Object scope = scopes.get(i).get();
            if (scope == null) { scopes.remove(i); continue; }
            try { symbols.invalidateScope.invoke(scope); }
            catch (ReflectiveOperationException error) { XpLog.log("[SpicyAuto] invalidation failed " + error); }
        }
    }
    /** A new car connection starts a fresh, bounded recovery chance. */
    private static void onConnection() {
        connectionWeatherEmissions = 0;
        AutoCompositionRecovery bootstrap = weatherBootstrap;
        if (bootstrap != null) bootstrap.reset();
    }
    static void rejectSurface(String target, String row, int width, int height, boolean credit) {
        // An unpaged credit can exceed a box that still fits ordinary lyric pages.
        boolean changed = credit ? surfaceReadiness.reject(target, row)
                : surfaceReadiness.reject(target, row, width, height);
        if (!changed) return;
        // Measurement can happen inside composition. Defer invalidation until it returns.
        main.post(() -> { refresh(); recompose(); });
    }
    /** A resized lyric box may now fit a page that the previous size could not. */
    static void surfaceMeasured(String target, int width, int height) {
        if ("weather".equals(target) && symbols.weatherSizeModifier != null) return;
        if (!surfaceReadiness.measured(target, width, height)) return;
        main.post(() -> { refresh(); recompose(); });
    }
    private static boolean surfaceReady(String target) {
        return ready() && surfaceReadiness.allows(target, AutoLyricsView.sampleKey(state));
    }
    private static boolean ready() {
        return wantsLyrics() || artworkRelease.retainLyrics(android.os.SystemClock.elapsedRealtime());
    }
    private static Object nativeFadeModifier(Object modifier) throws Exception {
        if (symbols.graphicsLayer == null) return modifier;
        return symbols.graphicsLayer.invoke(null, modifier == null ? symbols.emptyModifier : modifier,
                0f, 0f, nativeAlpha, 0f, 0f, null, false, 0, 131067);
    }
    private static boolean wantsLyrics() {
        Bundle s = state;
        return sharedCoreInstalled && takeover && enabled && s != null
                && AutoPrototypePolicy.fresh(s.getLong("sampledAt"), android.os.SystemClock.elapsedRealtime())
                && AutoResponseCredit.packetAllowed(s.getBoolean("orgSource"), s.getLong("creditFetchedAtMs"), System.currentTimeMillis())
                && !AutoLyricPresentation.nativeArtwork(s.getBoolean("documentResolved"),
                        s.getBoolean("hasSyncedLyrics"), s.getLong("firstVocalStartMs", Long.MAX_VALUE),
                        s.getLong("lastVocalEndMs"), AutoPrototypePolicy.position(s.getLong("positionMs"),
                                s.getLong("sampledAt"), s.getBoolean("playing"), s.getDouble("playbackRate", 1d), android.os.SystemClock.elapsedRealtime()),
                        s.getBoolean("stress"), s.getBoolean("creditOutroVisible"));
    }
    private static boolean fullReady() {
        Bundle s = state;
        return AutoPrototypePolicy.fullPlayerTakeover(surfaceReady("player-full"), s != null && s.getBoolean("hasSyncedLyrics"),
                s != null && s.getBoolean("stress"),
                true);
    }
    private static boolean isSpotifySelected() {
        Object vm = mediaModel.get();
        if (vm == null) return false;
        try {
            for (Method getter : symbols.mediaModelGetters) {
                Object observable = getter.invoke(vm);
                Object value = observable == null ? null : symbols.mediaObservableRead.invoke(observable);
                if (value instanceof ComponentName) return "com.spotify.music".equals(((ComponentName) value).getPackageName());
            }
        } catch (ReflectiveOperationException ignored) { }
        return false;
    }
    private static boolean fullPlayerVisible() {
        for (WeakReference<AutoLyricsView> ref : canvases) {
            AutoLyricsView view = ref.get();
            if (visible(view) && "player-full".equals(view.getTag())) return true;
        }
        return false;
    }
    private static boolean weatherVisible() {
        for (WeakReference<AutoLyricsView> ref : canvases) {
            AutoLyricsView view = ref.get();
            if (surfaceReady("weather") && visible(view) && "weather".equals(view.getTag())) return true;
        }
        return false;
    }
    private static boolean visible(View view) {
        return view != null && view.isAttachedToWindow() && view.isShown() && view.getDisplay() != null
                && view.getDisplay().getState() == android.view.Display.STATE_ON && ("Dashboard".equals(view.getDisplay().getName())
                || ("player-full".equals(view.getTag()) && view.getDisplay().getDisplayId() > 0));
    }
    private static boolean creditPresented(Bundle sample) {
        if (sample == null) return false;
        for (WeakReference<AutoLyricsView> ref : canvases) {
            AutoLyricsView view = ref.get();
            if (visible(view) && view.creditVisibleFor(sample.getInt("generation"), sample.getString("trackUri", ""))) return true;
        }
        return false;
    }
    private static void refresh() {
        boolean spotify = isSpotifySelected();
        Bundle sample = state;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean fresh = sample != null && AutoPrototypePolicy.fresh(sample.getLong("sampledAt"), now);
        boolean creditAllowed = sample != null && AutoResponseCredit.packetAllowed(
                sample.getBoolean("orgSource"), sample.getLong("creditFetchedAtMs"), System.currentTimeMillis());
        long position = sample == null ? 0 : AutoPrototypePolicy.position(sample.getLong("positionMs"),
                sample.getLong("sampledAt"), sample.getBoolean("playing"), sample.getDouble("playbackRate", 1d), now);
        long lastVocalEnd = sample == null ? 0 : sample.getLong("lastVocalEndMs");
        boolean hasCredit = sample != null && AutoResponseCredit.hasCredit(
                sample.getString("creditWriters"), sample.getStringArrayList("creditLabels"));
        boolean creditWindowOpen = sample != null && AutoResponseCredit.outroWindow(
                sample.getBoolean("hasSyncedLyrics"), lastVocalEnd, position, hasCredit);
        boolean valid = enabled && spotify && sample != null && !sample.getString("trackUri", "").isEmpty()
                && (creditWindowOpen || (fresh && creditAllowed));
        boolean connected = playbackHold.update(valid, sample != null
                && (sample.getBoolean("playing") || sample.getBoolean("stress") || creditWindowOpen),
                now, AutoDisplaySettings.pauseSeconds() * 1000L);
        takeover = connected;
        String session = sample == null ? null : sample.getInt("generation") + ":" + sample.getString("trackUri", "");
        boolean creditVisible = creditWindow.update(session, valid, sample != null && takeover && !sample.getBoolean("stress"),
                sample != null && sample.getBoolean("hasSyncedLyrics"), sample == null ? 0 : sample.getLong("lastVocalEndMs"),
                position,
                sample != null && AutoResponseCredit.hasCredit(sample.getString("creditWriters"), sample.getStringArrayList("creditLabels")), creditPresented(sample), now);
        boolean nativeArtwork = sample != null && AutoLyricPresentation.nativeArtwork(
                sample.getBoolean("documentResolved"), sample.getBoolean("hasSyncedLyrics"),
                sample.getLong("firstVocalStartMs", Long.MAX_VALUE), sample.getLong("lastVocalEndMs"),
                position, sample.getBoolean("stress"), creditVisible);
        if (sample != null) {
            // Keep an outgoing view's sample intact while the host fades its credit away.
            sample = new Bundle(sample);
            sample.putBoolean("creditOutroVisible", creditVisible);
            state = sample;
        }
        boolean wasReleasing = artworkRelease.running();
        artworkRelease.update(session,
                wantsLyrics(), valid, com.eza.spicyex.ui.Motion.animationsEnabled() && symbols.graphicsLayer != null, now);
        nativeAlpha = artworkRelease.nativeAlpha(now);
        main.removeCallbacks(releaseFrame);
        if (artworkRelease.running()) main.postDelayed(releaseFrame, 32);
        boolean active = ready(), weather = weatherVisible(), fullActive = fullReady(), playerActive = surfaceReady("player");
        AutoCompositionRecovery bootstrap = weatherBootstrap;
        if (bootstrap != null && connectionWeatherEmissions == 0 && active && spotify) {
            long before = bootstrap.compositions;
            bootstrap.recover();
            if (bootstrap.compositions != before) XpLog.log("[SpicyAuto] weather bootstrap compositions="
                    + bootstrap.compositions + " scopeInvalidations=" + bootstrap.scopes + " errors=" + bootstrap.errors);
        }
        boolean compactEntering = compactArtwork.update(playerActive, spotify, weather);
        boolean weatherReadyChanged = surfaceReadiness.changed("weather", AutoLyricsView.sampleKey(sample), active);
        if (!playerActive) {
            main.removeCallbacks(backgroundFrame); backgroundFadeStarted = 0;
            backgroundAlpha = nativeAlpha; writeBackgroundOpacity();
        }
        int displaySettings = AutoDisplaySettings.signature(sample);
        if (weatherReadyChanged || playerActive != lastPlayerReady || displaySettings != lastDisplaySettings || fullActive != lastFullReady || active != lastReady || weather != lastWeather || spotify != lastSpotify || enabled != lastEnabled) {
            boolean immediate = sample != null && AutoLyricPresentation.immediateEntrance(
                    sample.getBoolean("hasSyncedLyrics"), sample.getLong("firstVocalStartMs", Long.MAX_VALUE),
                    AutoPrototypePolicy.position(sample.getLong("positionMs"), sample.getLong("sampledAt"),
                            sample.getBoolean("playing"), sample.getDouble("playbackRate", 1d), now));
            if (active && !lastReady && spotify && !immediate) AutoArtworkTransition.capture();
            if (compactEntering) {
                main.removeCallbacks(backgroundFrame);
                backgroundFadeStarted = -1; backgroundFades++; backgroundFadeSamples = "";
                backgroundAlpha = com.eza.spicyex.ui.Motion.animationsEnabled() && !immediate ? 1f : 0f;
                if (backgroundAlpha == 0f) backgroundFadeStarted = 0;
            } else if (!compactArtwork.owned()) {
                main.removeCallbacks(backgroundFrame);
                backgroundAlpha = nativeAlpha; backgroundFadeStarted = 0;
            }
            writeBackgroundOpacity();
            lastReady = active; lastWeather = weather; lastSpotify = spotify; lastEnabled = enabled;
            lastFullReady = fullActive; lastPlayerReady = playerActive;
            lastDisplaySettings = displaySettings;
            recompose();
        }
        if (artworkRelease.running() || wasReleasing) recompose();
        Bundle status = new Bundle();
        status.putString("process", android.app.Application.getProcessName());
        status.putString("hostBuild", com.eza.spicyex.BuildStamp.FULL);
        status.putBoolean("sharedCoreInstalled", sharedCoreInstalled);
        status.putBoolean("compactInstalled", compactInstalled);
        status.putBoolean("fullInstalled", fullInstalled);
        status.putString("hookInstallation", installation.summary());
        status.putBoolean("modernWeatherInstalled", "installed".equals(installation.error("weather-modern")));
        status.putBoolean("legacyWeatherInstalled", "installed".equals(installation.error("weather-legacy")));
        status.putString("resolverDiagnostics", symbols.diagnostics.toString());
        status.putLong("modernWeatherCalls", modernWeatherCalls);
        status.putLong("legacyWeatherCalls", legacyWeatherCalls);
        status.putLong("modernWeatherEmissions", modernWeatherEmissions);
        status.putLong("legacyWeatherEmissions", legacyWeatherEmissions);
        status.putLong("modernWeatherErrors", modernWeatherErrors);
        status.putLong("legacyWeatherErrors", legacyWeatherErrors);
        status.putString("lastWeatherError", lastWeatherError);
        status.putBoolean("ready", active);
        status.putBoolean("fresh", fresh);
        status.putBoolean("creditAllowed", creditAllowed);
        status.putBoolean("nativeArtwork", nativeArtwork);
        status.putLong("stateAgeMs", sample == null ? -1 : Math.max(0, now - sample.getLong("sampledAt")));
        status.putLong("positionMs", position);
        status.putLong("installCompletedAgeMs", installCompletedAtMs == 0 ? -1 : now - installCompletedAtMs);
        status.putLong("firstWeatherCallAgeMs", firstWeatherCallAtMs == 0 ? -1 : now - firstWeatherCallAtMs);
        status.putLong("weatherDispatchCalls", weatherDispatchCalls);
        status.putLong("firstWeatherDispatchAgeMs", firstWeatherDispatchAtMs == 0 ? -1 : now - firstWeatherDispatchAtMs);
        status.putLong("connectionWeatherEmissions", connectionWeatherEmissions);
        status.putInt("weatherOwners", weatherOwners.size());
        status.putBoolean("weatherBootstrapResolved", bootstrap != null);
        status.putLong("weatherBootstrapCompositions", bootstrap == null ? 0 : bootstrap.compositions);
        status.putLong("weatherBootstrapScopeInvalidations", bootstrap == null ? 0 : bootstrap.scopes);
        status.putLong("weatherBootstrapErrors", bootstrap == null ? 0 : bootstrap.errors);
        status.putBoolean("documentResolved", sample != null && sample.getBoolean("documentResolved"));
        status.putLong("firstVocalStartMs", sample == null ? Long.MAX_VALUE : sample.getLong("firstVocalStartMs", Long.MAX_VALUE));
        status.putLong("lastVocalEndMs", sample == null ? 0 : sample.getLong("lastVocalEndMs"));
        status.putBoolean("creditOutroVisible", creditVisible);
        status.putBoolean("creditWindowOpen", creditWindowOpen);
        status.putLong("creditRemainingMs", creditWindow.remainingMs(now));
        status.putBoolean("takeover", takeover);
        status.putBoolean("fullTakeover", fullActive);
        status.putBoolean("artworkRelease", artworkRelease.running());
        status.putFloat("nativeAlpha", nativeAlpha);
        status.putFloat("lyricAlpha", artworkRelease.lyricAlpha(now));
        status.putInt("pauseHoldSeconds", AutoDisplaySettings.pauseSeconds());
        status.putBoolean("showProgress", !AutoPrototypePolicy.suppressFullProgress(fullActive, spotify, fullContentHeightDp));
        status.putBoolean("suppressSongInfo", AutoPrototypePolicy.suppressFullSongInfo(fullActive, spotify,
                AutoDisplaySettings.extraLine(sample)));
        status.putFloat("fullContentHeightDp", fullContentHeightDp);
        status.putBoolean("hasSyncedLyrics", sample != null && sample.getBoolean("hasSyncedLyrics"));
        status.putBoolean("interlude", active && !sample.getBoolean("stress") && sample.getString("line", "").isEmpty());
        status.putBoolean("weatherResolved", symbols.weatherMapped());
        status.putBoolean("legacyWeatherResolved", symbols.legacyWeatherRenderer != null);
        status.putBoolean("weatherGeometryResolved", symbols.weatherSizeModifier != null);
        status.putLong("weatherSizeErrors", weatherSizeErrors);
        status.putString("lastWeatherSizeError", lastWeatherSizeError);
        status.putBoolean("weatherReady", surfaceReady("weather"));
        status.putBoolean("weatherFitRejected", !surfaceReadiness.allows("weather", AutoLyricsView.sampleKey(sample)));
        long weatherSize = surfaceReadiness.measuredSize("weather");
        status.putString("weatherSize", (int) (weatherSize >> 32) + "x" + (int) weatherSize);
        status.putBoolean("mediaResolved", symbols.mediaContentSlot != null);
        status.putBoolean("navigationResolved", symbols.dashboardCardsRead != null);
        status.putLong("navigationSuppressed", navigationSuppressed);
        status.putInt("weatherCallerCount", symbols.weatherCallers.length);
        status.putInt("weatherCallersDeoptimized", weatherCallersDeoptimized);
        status.putLong("weatherCalls", weatherCalls); status.putLong("mediaCalls", mediaCalls);
        status.putLong("contentCalls", contentCalls); status.putLong("artSuppressed", artSuppressed);
        status.putBoolean("badgesResolved", explicitBadgeId != 0
                && (symbols.mediaBadgeListRead != null || symbols.explicitBadgeProducer != null));
        status.putLong("badgesSuppressed", badgesSuppressed);
        status.putLong("songInfoSuppressed", songInfoSuppressed);
        status.putLong("fullOwnedBadgeCalls", fullOwnedBadgeCalls);
        status.putLong("fullBadgeCalls", fullBadgeCalls); status.putLong("fullBadgeMatches", fullBadgeMatches);
        status.putBoolean("progressResolved", symbols.fullMediaProgress != null);
        status.putBoolean("thumbnailResolved", symbols.mediaThumbnail != null);
        status.putLong("progressSuppressed", progressSuppressed);
        status.putLong("thumbnailsSuppressed", thumbnailsSuppressed);
        status.putFloat("backgroundAlpha", backgroundAlpha); status.putLong("backgroundFades", backgroundFades);
        status.putString("backgroundFadeSamples", backgroundFadeSamples);
        status.putString("target", "none");
        ArrayList<String> surfaceMetrics = new ArrayList<>();
        int reportedSurfaces = 0;
        for (int i = canvases.size() - 1; i >= 0; i--) {
            AutoLyricsView view = canvases.get(i).get();
            if (view == null) { canvases.remove(i); continue; }
            Bundle surface = view.metrics();
            surface.putString("surface", String.valueOf(view.getTag()));
            surface.putBoolean("attached", view.isAttachedToWindow());
            surface.putBoolean("hostVisible", visible(view));
            surface.putString("bounds", view.getWidth() + "x" + view.getHeight());
            surface.keySet(); surfaceMetrics.add(surface.toString());
            if (!surfaceReady(String.valueOf(view.getTag())) || ("player-full".equals(view.getTag()) && (!fullReady() || !isSpotifySelected()))
                    || ("player".equals(view.getTag()) && (!isSpotifySelected() || weatherVisible()))) {
                view.stop(); view.setVisibility(View.GONE); continue;
            }
            view.setVisibility(View.VISIBLE);
            if (!visible(view)) { view.stop(); continue; }
            view.setAlpha(artworkRelease.lyricAlpha(now));
            if (wantsLyrics()) view.update(state);
            else view.stop();
            String target = String.valueOf(view.getTag());
            status.putString("target", ++reportedSurfaces == 1 ? target : "multiple");
            status.putString("size", view.getWidth() + "x" + view.getHeight());
            Bundle metrics = view.metrics(); metrics.keySet(); status.putString("renderMetrics", metrics.toString());
        }
        if (reportedSurfaces > 1) { status.remove("size"); status.remove("renderMetrics"); }
        status.putString("surfaceMetrics", surfaceMetrics.toString());
        hostStatus = status;
    }
}
