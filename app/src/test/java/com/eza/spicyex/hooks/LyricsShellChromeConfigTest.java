package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.SettingsReader;
import com.eza.spicyex.settings.TypedStore;
import com.eza.spicyex.settings.values.ChromeClusterLayout;
import com.eza.spicyex.settings.values.ChromeClusterPosition;
import com.eza.spicyex.settings.values.ChipPosition;
import com.eza.spicyex.settings.values.ChipStyle;
import com.eza.spicyex.settings.values.FollowChipIcon;
import com.eza.spicyex.settings.values.LikedSongsButton;
import com.eza.spicyex.settings.values.StatusBarHiddenMode;
import com.eza.spicyex.settings.values.StoredValues;
import com.eza.spicyex.settings.values.TapSeekMode;
import com.eza.spicyex.settings.values.TrackInfoPosition;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** The fullscreen chrome snapshot: every field, schema defaults, and reader-resolved orientation. */
public class LyricsShellChromeConfigTest {
    @Test
    public void readsEveryChromeSettingOnce() {
        FakeStore store = new FakeStore();
        store.set(Settings.LIKED_SONGS_BUTTON, "Heart");
        store.set(Settings.CHROME_CLUSTER_LAYOUT, "Horizontal");
        store.set(Settings.CHROME_CLUSTER_POSITION, "Left");
        store.set(Settings.SHOW_FULLSCREEN_BACK_BUTTON, false);
        store.set(Settings.FOLLOW_CHIP_POSITION, "Center");
        store.set(Settings.FOLLOW_CHIP_STYLE, "Label");
        store.set(Settings.FOLLOW_CHIP_ICON, "Waveform");
        store.set(Settings.SKIP_CHIP_POSITION, "Left");
        store.set(Settings.SKIP_CHIP_STYLE, "Icon");
        store.set(Settings.TRACK_INFO_POSITION, "Bottom");
        store.set(Settings.LONG_PRESS_SHARE, true);
        store.set(Settings.TAP_SEEK_MODE, "Single tap");
        store.set(Settings.STAY_IN_LYRICS, false);
        store.set(Settings.STATUS_BAR_HIDDEN_MODE, "Landscape");
        store.set(Settings.SYNC_OFFSET_MS, 250);

        LyricsShellChromeConfig chrome = LyricsShellChromeConfig.read(store);

        assertSame(LikedSongsButton.HEART, chrome.likedSongsButton);
        assertSame(ChromeClusterLayout.HORIZONTAL, chrome.chromeClusterLayout);
        assertSame(ChromeClusterPosition.LEFT, chrome.chromeClusterPosition);
        assertFalse(chrome.showFullscreenBackButton);
        assertSame(ChipPosition.CENTER, chrome.followChipPosition);
        assertSame(ChipStyle.LABEL, chrome.followChipStyle);
        assertSame(FollowChipIcon.WAVEFORM, chrome.followChipIcon);
        assertSame(ChipPosition.LEFT, chrome.skipChipPosition);
        assertSame(ChipStyle.ICON, chrome.skipChipStyle);
        assertSame(TrackInfoPosition.BOTTOM, chrome.trackInfoPosition);
        assertTrue(chrome.longPressShare);
        assertSame(TapSeekMode.SINGLE_TAP, chrome.tapSeekMode);
        assertFalse(chrome.stayInLyrics);
        assertSame(StatusBarHiddenMode.LANDSCAPE, chrome.statusBarHiddenMode);
        assertEquals(250, chrome.syncOffsetMs);
        assertEquals(15, store.reads);
    }

    @Test
    public void unsetValuesKeepTheirSchemaDefaults() {
        LyricsShellChromeConfig chrome = LyricsShellChromeConfig.read(new FakeStore());

        assertSame(StoredValues.defaultValue(LikedSongsButton.class), chrome.likedSongsButton);
        assertSame(StoredValues.defaultValue(ChromeClusterLayout.class), chrome.chromeClusterLayout);
        assertSame(StoredValues.defaultValue(ChromeClusterPosition.class), chrome.chromeClusterPosition);
        assertEquals(Settings.SHOW_FULLSCREEN_BACK_BUTTON.defaultValue,
                Boolean.valueOf(chrome.showFullscreenBackButton));
        assertSame(StoredValues.defaultValue(ChipPosition.class), chrome.followChipPosition);
        assertSame(StoredValues.defaultValue(ChipStyle.class), chrome.followChipStyle);
        assertSame(StoredValues.defaultValue(FollowChipIcon.class), chrome.followChipIcon);
        assertSame(StoredValues.defaultValue(ChipPosition.class), chrome.skipChipPosition);
        assertSame(StoredValues.defaultValue(ChipStyle.class), chrome.skipChipStyle);
        assertSame(StoredValues.defaultValue(TrackInfoPosition.class), chrome.trackInfoPosition);
        assertEquals(Settings.LONG_PRESS_SHARE.defaultValue, Boolean.valueOf(chrome.longPressShare));
        assertSame(StoredValues.defaultValue(TapSeekMode.class), chrome.tapSeekMode);
        assertEquals(Settings.STAY_IN_LYRICS.defaultValue, Boolean.valueOf(chrome.stayInLyrics));
        assertSame(StoredValues.defaultValue(StatusBarHiddenMode.class), chrome.statusBarHiddenMode);
        assertEquals(Settings.SYNC_OFFSET_MS.defaultValue, Integer.valueOf(chrome.syncOffsetMs));
    }

    @Test
    public void orientationIsWhicheverValueTheReaderResolves() {
        OrientationReader portrait = new OrientationReader(false);
        assertSame(ChromeClusterLayout.VERTICAL,
                LyricsShellChromeConfig.read(portrait).chromeClusterLayout);
        assertSame(ChromeClusterPosition.RIGHT,
                LyricsShellChromeConfig.read(portrait).chromeClusterPosition);

        OrientationReader landscape = new OrientationReader(true);
        assertSame(ChromeClusterLayout.HORIZONTAL,
                LyricsShellChromeConfig.read(landscape).chromeClusterLayout);
        assertSame(ChromeClusterPosition.LEFT,
                LyricsShellChromeConfig.read(landscape).chromeClusterPosition);
    }

    @Test
    public void snapshotDoesNotFollowLaterWrites() {
        FakeStore store = new FakeStore();
        store.set(Settings.CHROME_CLUSTER_LAYOUT, "Vertical");
        store.set(Settings.STATUS_BAR_HIDDEN_MODE, "Portrait");

        LyricsShellChromeConfig chrome = LyricsShellChromeConfig.read(store);

        store.set(Settings.CHROME_CLUSTER_LAYOUT, "Horizontal");
        store.set(Settings.STATUS_BAR_HIDDEN_MODE, "Both");
        assertSame(ChromeClusterLayout.VERTICAL, chrome.chromeClusterLayout);
        assertSame(StatusBarHiddenMode.PORTRAIT, chrome.statusBarHiddenMode);
    }

    /** Simulates the two orientation-resolved settings a live reader returns for a rotation. */
    private static final class OrientationReader implements SettingsReader {
        private final boolean landscape;

        OrientationReader(boolean landscape) {
            this.landscape = landscape;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Settings.Setting<T> setting) {
            Object value = setting.defaultValue;
            if (setting == Settings.CHROME_CLUSTER_LAYOUT) {
                value = (landscape ? ChromeClusterLayout.HORIZONTAL : ChromeClusterLayout.VERTICAL)
                        .storedLabel();
            } else if (setting == Settings.CHROME_CLUSTER_POSITION) {
                value = (landscape ? ChromeClusterPosition.LEFT : ChromeClusterPosition.RIGHT)
                        .storedLabel();
            }
            return (T) value;
        }
    }

    private static final class FakeStore implements TypedStore {
        final Map<String, Object> values = new HashMap<>();
        int reads;

        void set(Settings.Setting<?> setting, Object value) {
            values.put(setting.key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Settings.Setting<T> setting) {
            reads++;
            Object value = values.get(setting.key);
            return value == null ? setting.defaultValue : (T) value;
        }

        @Override
        public boolean contains(Settings.Setting<?> setting) {
            return values.containsKey(setting.key);
        }

        @Override
        public void putBoolean(Settings.BooleanSetting setting, boolean value) {
            values.put(setting.key, value);
        }

        @Override
        public void putString(Settings.StringSetting setting, String value) {
            values.put(setting.key, value);
        }

        @Override
        public void putInt(Settings.IntegerSetting setting, int value) {
            values.put(setting.key, value);
        }
    }
}
