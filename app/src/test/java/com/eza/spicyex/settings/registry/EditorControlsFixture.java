package com.eza.spicyex.settings.registry;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed builder recordings and their input snapshots. This reader never loads production builders. */
final class EditorControlsFixture {
    Map<String, Layout> contexts = new LinkedHashMap<>();

    static final class Layout {
        boolean twoColumn, cardMode, landscape;
        Map<String, Variant> variants = new LinkedHashMap<>();

        UiContext context(Variant variant) {
            return new UiContext(twoColumn, cardMode, landscape,
                    new SettingsSnapshot(variant.settings), false);
        }
    }

    static final class Variant {
        Map<String, Object> settings = new LinkedHashMap<>();
        Map<String, List<String>> controls = new LinkedHashMap<>();
    }

    static EditorControlsFixture read() throws IOException {
        try (InputStream input = EditorControlsFixture.class.getResourceAsStream("editor-controls-a6f5a3c8.json")) {
            if (input == null) throw new IOException("Missing editor controls golden fixture");
            return new Gson().fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), EditorControlsFixture.class);
        }
    }
}
