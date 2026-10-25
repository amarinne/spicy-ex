package com.eza.spicyex.hooks;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.SettingsReader;
import com.eza.spicyex.settings.values.ChromeClusterLayout;
import com.eza.spicyex.settings.values.ChromeClusterPosition;
import com.eza.spicyex.settings.values.ChipPosition;
import com.eza.spicyex.settings.values.ChipStyle;
import com.eza.spicyex.settings.values.FollowChipIcon;
import com.eza.spicyex.settings.values.LikedSongsButton;
import com.eza.spicyex.settings.values.StatusBarHiddenMode;
import com.eza.spicyex.settings.values.TapSeekMode;
import com.eza.spicyex.settings.values.TrackInfoPosition;

/**
 * Immutable fullscreen chrome, chip, and gesture snapshot: the shell settings read together.
 *
 * <p>Owns no schema, default, persistence, cache identity, or live observation. The reader
 * resolves orientation, never this holder. Consumers take a fresh snapshot where they read the
 * settings before.
 */
public final class LyricsShellChromeConfig {
    public final LikedSongsButton likedSongsButton;
    public final ChromeClusterLayout chromeClusterLayout;
    public final ChromeClusterPosition chromeClusterPosition;
    public final boolean showFullscreenBackButton;
    public final ChipPosition followChipPosition;
    public final ChipStyle followChipStyle;
    public final FollowChipIcon followChipIcon;
    public final ChipPosition skipChipPosition;
    public final ChipStyle skipChipStyle;
    public final TrackInfoPosition trackInfoPosition;
    public final boolean longPressShare;
    public final TapSeekMode tapSeekMode;
    public final boolean stayInLyrics;
    public final StatusBarHiddenMode statusBarHiddenMode;
    public final int syncOffsetMs;

    private LyricsShellChromeConfig(LikedSongsButton likedSongsButton,
            ChromeClusterLayout chromeClusterLayout,
            ChromeClusterPosition chromeClusterPosition,
            boolean showFullscreenBackButton,
            ChipPosition followChipPosition,
            ChipStyle followChipStyle,
            FollowChipIcon followChipIcon,
            ChipPosition skipChipPosition,
            ChipStyle skipChipStyle,
            TrackInfoPosition trackInfoPosition,
            boolean longPressShare,
            TapSeekMode tapSeekMode,
            boolean stayInLyrics,
            StatusBarHiddenMode statusBarHiddenMode,
            int syncOffsetMs) {
        this.likedSongsButton = likedSongsButton;
        this.chromeClusterLayout = chromeClusterLayout;
        this.chromeClusterPosition = chromeClusterPosition;
        this.showFullscreenBackButton = showFullscreenBackButton;
        this.followChipPosition = followChipPosition;
        this.followChipStyle = followChipStyle;
        this.followChipIcon = followChipIcon;
        this.skipChipPosition = skipChipPosition;
        this.skipChipStyle = skipChipStyle;
        this.trackInfoPosition = trackInfoPosition;
        this.longPressShare = longPressShare;
        this.tapSeekMode = tapSeekMode;
        this.stayInLyrics = stayInLyrics;
        this.statusBarHiddenMode = statusBarHiddenMode;
        this.syncOffsetMs = syncOffsetMs;
    }

    public static LyricsShellChromeConfig read(SettingsReader reader) {
        return new LyricsShellChromeConfig(
                reader.get(Settings.LIKED_SONGS_BUTTON, LikedSongsButton.class),
                reader.get(Settings.CHROME_CLUSTER_LAYOUT, ChromeClusterLayout.class),
                reader.get(Settings.CHROME_CLUSTER_POSITION, ChromeClusterPosition.class),
                Boolean.TRUE.equals(reader.get(Settings.SHOW_FULLSCREEN_BACK_BUTTON)),
                reader.get(Settings.FOLLOW_CHIP_POSITION, ChipPosition.class),
                reader.get(Settings.FOLLOW_CHIP_STYLE, ChipStyle.class),
                reader.get(Settings.FOLLOW_CHIP_ICON, FollowChipIcon.class),
                reader.get(Settings.SKIP_CHIP_POSITION, ChipPosition.class),
                reader.get(Settings.SKIP_CHIP_STYLE, ChipStyle.class),
                reader.get(Settings.TRACK_INFO_POSITION, TrackInfoPosition.class),
                Boolean.TRUE.equals(reader.get(Settings.LONG_PRESS_SHARE)),
                reader.get(Settings.TAP_SEEK_MODE, TapSeekMode.class),
                !Boolean.FALSE.equals(reader.get(Settings.STAY_IN_LYRICS)),
                reader.get(Settings.STATUS_BAR_HIDDEN_MODE, StatusBarHiddenMode.class),
                reader.get(Settings.SYNC_OFFSET_MS));
    }
}
