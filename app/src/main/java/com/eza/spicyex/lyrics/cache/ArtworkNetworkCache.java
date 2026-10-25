package com.eza.spicyex.lyrics.cache;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Covers downloaded from Spotify's image CDN when the host has not published its own bitmap
 * (see {@link SpotifyArtworkCache}), for example right after a cold start. Every artwork surface
 * shares one download per cover; listeners hear when a cover lands.
 */
public final class ArtworkNetworkCache {
    private static final int LIMIT = 4;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EDGE_PX = 1024;
    private static final Map<String, Bitmap> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>(LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > LIMIT;
                }
            });
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private ArtworkNetworkCache() {
    }

    /** An owned copy of the downloaded cover; null starts the shared download instead. */
    public static Bitmap copyOrFetch(String imageId, OkHttpClient http) {
        if (imageId == null || imageId.isEmpty()) return null;
        synchronized (CACHE) {
            Bitmap cached = CACHE.get(imageId);
            if (cached != null && !cached.isRecycled()) {
                return cached.copy(cached.getConfig() != null ? cached.getConfig()
                        : Bitmap.Config.ARGB_8888, false);
            }
        }
        fetch(imageId, http);
        return null;
    }

    public static boolean has(String imageId) {
        if (imageId == null) return false;
        Bitmap cached = CACHE.get(imageId);
        return cached != null && !cached.isRecycled();
    }

    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** Ad creatives and remote playback can carry a full https URL instead of a cover id. */
    public static String url(String imageId) {
        return imageId.startsWith("http") ? imageId : "https://i.scdn.co/image/" + imageId;
    }

    private static void fetch(String imageId, OkHttpClient http) {
        if (http == null || has(imageId) || !IN_FLIGHT.add(imageId)) return;
        http.newCall(new Request.Builder().url(url(imageId)).get().build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                IN_FLIGHT.remove(imageId);
            }

            @Override
            public void onResponse(Call call, Response response) {
                Bitmap bitmap = null;
                try (Response ignored = response) {
                    if (response.isSuccessful() && response.body() != null
                            && response.body().contentLength() <= MAX_BYTES) {
                        byte[] data = readBounded(response.body().byteStream(), MAX_BYTES);
                        if (data != null) bitmap = decodeBounded(data, MAX_EDGE_PX);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (bitmap != null) CACHE.put(imageId, bitmap);
                    IN_FLIGHT.remove(imageId);
                }
                if (bitmap != null) {
                    for (Runnable listener : LISTENERS) listener.run();
                }
            }
        });
    }

    /** Drops every cover; owned copies already handed out stay valid. */
    public static void trimMemory() {
        synchronized (CACHE) {
            for (Bitmap bitmap : CACHE.values()) {
                if (bitmap != null) bitmap.recycle();
            }
            CACHE.clear();
        }
        IN_FLIGHT.clear();
    }

    /** Bounds-first decode with a power-of-two sample size. */
    static Bitmap decodeBounded(byte[] data, int targetLongestEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetLongestEdge);
        return BitmapFactory.decodeByteArray(data, 0, data.length, decode);
    }

    static int sampleSize(int width, int height, int targetLongestEdge) {
        if (width <= 0 || height <= 0 || targetLongestEdge <= 0) return 1;
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= targetLongestEdge) sample *= 2;
        return sample;
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count > maxBytes - total) return null;
                out.write(buffer, 0, count);
                total += count;
            }
            return out.toByteArray();
        }
    }
}
