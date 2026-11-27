package com.eza.spicyex.settings.panel;

import com.eza.spicyex.settings.values.ChineseMode;
import com.eza.spicyex.settings.values.StoredValues;

/** Draft for the two controls that share the existing Chinese mode setting. */
final class ChineseReadingSelection {
    private final boolean mandarinEzaAvailable;
    private final ChineseMode initial;
    private ChineseMode mode;
    private boolean eza;

    ChineseReadingSelection(String stored, boolean mandarinEzaAvailable) {
        this.mandarinEzaAvailable = mandarinEzaAvailable;
        ChineseMode configured = StoredValues.fromStored(ChineseMode.class, stored);
        switch (configured) {
            case PINYIN_EZA: mode = ChineseMode.PINYIN; eza = mandarinEzaAvailable; break;
            case JYUTPING_EZA: mode = ChineseMode.JYUTPING; eza = true; break;
            case AUTO_EZA: mode = ChineseMode.AUTO; eza = true; break;
            default: mode = configured;
        }
        initial = selected();
    }

    void selectMode(ChineseMode mode) { this.mode = mode; }
    void selectEza(boolean eza) { this.eza = eza; }
    ChineseMode mode() { return mode; }
    boolean isDirty() { return selected() != initial; }

    boolean hasStyle() {
        return mode != ChineseMode.OFF && mode != ChineseMode.CYCLE;
    }

    boolean offersEza() {
        return hasStyle() && (mode != ChineseMode.PINYIN || mandarinEzaAvailable);
    }

    boolean usesEza() {
        return eza && offersEza();
    }

    ChineseMode selected() {
        if (!usesEza()) return mode;
        switch (mode) {
            case PINYIN: return ChineseMode.PINYIN_EZA;
            case JYUTPING: return ChineseMode.JYUTPING_EZA;
            case AUTO: return ChineseMode.AUTO_EZA;
            default: return mode;
        }
    }
}
