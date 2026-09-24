package com.eza.spicyex.lyrics;

import android.graphics.*;
import android.media.MediaMetadata;
import java.lang.ref.WeakReference;
import android.net.Uri;

/** One weak host-owned image. Never guesses track identity from cover-view geometry. */
public final class SpotifyArtworkCache {
    private static volatile Entry current;
    private static final class Entry {
        final String imageUri, trackUri;
        final WeakReference<Bitmap> bitmap;
        Entry(String imageUri, String trackUri, Bitmap bitmap) {
            this.imageUri = imageUri; this.trackUri = trackUri;
            this.bitmap = new WeakReference<>(bitmap);
        }
    }
    private SpotifyArtworkCache() {}

    public static void capture(MediaMetadata metadata) {
        current = null;
        if (metadata == null) return;
        String[] images = {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON};
        String[] uris = {MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI};
        for (int i=0;i<images.length;i++) {
            Bitmap bitmap = metadata.getBitmap(images[i]);
            String uri = metadata.getString(uris[i]);
            String track = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (bitmap != null && !bitmap.isRecycled() && ((uri != null && !uri.isEmpty())
                    || (track != null && !track.isEmpty()))) {
                current = new Entry(uri,track,bitmap);
                return;
            }
        }
    }

    /** Copies into a bounded software bitmap before the caller queues any worker work. */
    public static Bitmap snapshot(String imageId, String trackUri) {
        return snapshotSized(imageId, trackUri, 128);
    }

    /** Larger bounded copy for the landscape side panel (capped; same miss semantics). */
    public static Bitmap snapshotLarge(String imageId, String trackUri, int sizePx) {
        return snapshotSized(imageId, trackUri, Math.max(128, Math.min(512, sizePx)));
    }

    private static Bitmap snapshotSized(String imageId, String trackUri, int sizePx) {
        Entry entry = current;
        if (entry == null || !matches(entry.imageUri,entry.trackUri,imageId,trackUri)) return null;
        Bitmap borrowed = entry.bitmap.get();
        if (borrowed == null || borrowed.isRecycled() || borrowed.getConfig() == Bitmap.Config.HARDWARE) return null;
        Bitmap copy = Bitmap.createBitmap(sizePx,sizePx,Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(copy);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(borrowed,null,new Rect(0,0,sizePx,sizePx),new Paint(Paint.FILTER_BITMAP_FLAG));
            return copy;
        } catch (RuntimeException unavailable) {
            copy.recycle();
            return null;
        }
    }

    static boolean matches(String imageUri,String mediaId,String imageId,String trackUri) {
        if (imageId == null || imageId.isEmpty()) return false;
        if (imageUri != null && !imageUri.isEmpty()) {
            String normalized = imageUri;
            try { normalized = Uri.decode(imageUri); } catch (Throwable ignored) { }
            normalized = normalized.replace("%3A", ":").replace("%3a", ":");
            int marker = normalized.indexOf("spotify:image:");
            if (marker >= 0) {
                int start = marker + "spotify:image:".length();
                int end = start;
                while (end < normalized.length()) {
                    char c = normalized.charAt(end);
                    if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) break;
                    end++;
                }
                normalized = "spotify:image:" + normalized.substring(start, end);
            }
            // imageId may already be a full https URL (ad creatives, remote playback) —
            // compare directly in that case too.
            if (imageId.startsWith("http")) {
                if (normalized.equals(imageId)) return true;
            }
            return normalized.equals("spotify:image:"+imageId)
                    || normalized.equals("https://i.scdn.co/image/"+imageId)
                    || normalized.equals(imageId);
        }
        // No embedded URI to compare (remote/ad playback): fall back to track identity.
        // Ads use spotify:ad: URIs, so both prefixes must match here — otherwise ad artwork
        // captured from MediaMetadata could never be served back.
        if (trackUri != null && mediaId != null && trackUri.equals(mediaId)) {
            return trackUri.startsWith("spotify:track:") || trackUri.startsWith("spotify:ad:")
                    || trackUri.startsWith("spotify:episode:");
        }
        return false;
    }
}
