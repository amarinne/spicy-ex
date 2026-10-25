package com.eza.spicyex.settings.registry;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The panel rows recorded before the registry existed, and the snapshots they were recorded in.
 * This reader never loads the old builders, so the golden survives their deletion.
 */
final class PanelControlsFixture {
    List<String> sections = new ArrayList<>();
    List<Snapshot> snapshots = new ArrayList<>();

    static final class Snapshot {
        String name;
        Map<String, Object> values = new LinkedHashMap<>();
        @SerializedName("language_pack_ready") boolean languagePackReady = true;
        @SerializedName("rendered_sections") List<String> renderedSections = new ArrayList<>();
        List<String> rows = new ArrayList<>();
    }

    static PanelControlsFixture read() throws IOException {
        try (InputStream input = PanelControlsFixture.class
                .getResourceAsStream("panel-controls-e63d3209.json")) {
            if (input == null) throw new IOException("Missing panel controls golden fixture");
            return new Gson().fromJson(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), PanelControlsFixture.class);
        }
    }
}
