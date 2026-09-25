package com.eza.spicyex;

public class SpotifyTrack {
    public final String title;
    public final String artist;
    public final String album;
    public final String uri;
    public final long duration;
    public final long position;
    public final String color;
    public final long lastUpdated;
    public final String imageId;
    public final boolean saved;

    public SpotifyTrack(String title, String artist, String album, String uri, long position, String color, long lastUpdated, String imageId, long duration, boolean saved) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.uri = uri;
        this.position = position;
        this.color = color;
        this.lastUpdated = lastUpdated;
        if (imageId != null) {
            if (imageId.startsWith("http")) {
                this.imageId = imageId;
            } else {
                String[] parts = imageId.split(":");
                this.imageId = parts.length > 2 ? parts[2] : imageId;
            }
        } else {
            this.imageId = null;
        }
        this.duration = duration;
        this.saved = saved;
    }
}
