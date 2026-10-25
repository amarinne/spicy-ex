package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;

/** Numeric range and presentation, including an optional final named stop. */
public final class SliderSpec {
    public final int min;
    public final int max;
    public final int step;
    public final String unitKey;
    public final Integer endValue;
    public final String endLabelKey;
    public final Integer defaultMarker;
    public final ValueCodec codec;

    public SliderSpec(int min, int max, int step, String unitKey, Integer endValue,
            String endLabelKey, Integer defaultMarker, ValueCodec codec) {
        if (min > max || step < 1) throw new IllegalArgumentException("invalid slider range");
        if ((endValue == null) != (endLabelKey == null)
                || (endValue != null && endValue <= max)) {
            throw new IllegalArgumentException("invalid final stop");
        }
        this.min = min;
        this.max = max;
        this.step = step;
        this.unitKey = unitKey;
        this.endValue = endValue;
        this.endLabelKey = endLabelKey;
        this.defaultMarker = defaultMarker;
        this.codec = codec;
    }

    public static SliderSpec from(Settings.IntegerSetting setting, String unitKey) {
        return new SliderSpec(setting.minValue, setting.maxValue, setting.stepValue, unitKey,
                null, null, setting.defaultValue, null);
    }
}
