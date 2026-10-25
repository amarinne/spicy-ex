package com.eza.spicyex.ambient;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Metadata for the pinned Paper Shaders Android AGSL catalog.
 *
 * <p>The AGSL text, {@code catalog.tsv}, {@code source-hashes.tsv} and {@code ps_noise.png} are
 * generated from {@code AndreFrelicot/paper-shaders-android} at
 * {@code fc2d75d4dad54b63bbb07df16d8a2d9a2cc467f3} (tag {@code 0.0.3}) by
 * {@code app/src/test/tools/import_paper_shaders.py}. They ship as Java resources so this class
 * stays free of Android types: the renderer, the selection layer and JVM tests all read the same
 * immutable values.
 *
 * <p>{@link Spec#uniforms} carries the upstream defaults, including the raw sizing uniforms
 * ({@code u_fit}, {@code u_scale}, {@code u_rotation}, {@code u_originX/Y}, {@code u_offsetX/Y},
 * {@code u_worldWidth/Height}). {@link Spec#colorCapacities} carries the packed color-array
 * capacities in declaration order. Dynamic uniforms ({@code u_resolution}, {@code u_time},
 * {@code u_pixelRatio}, image and noise sizes) are bound by the renderer, not stored here.
 */
public final class PaperShaderCatalog {
    /** Paper's maximum packed color count. */
    public static final int MAX_COLORS = 10;

    private static final String RESOURCE_ROOT = "paper-shaders/";
    private static final String CATALOG_RESOURCE = RESOURCE_ROOT + "catalog.tsv";
    private static final int CATALOG_COLUMNS = 7;

    private static final Object LOCK = new Object();
    private static volatile Model model;

    private PaperShaderCatalog() {
    }

    /** Immutable metadata for one upstream shader. */
    public static final class Spec {
        /** Stable upstream kebab-case identifier and AGSL resource basename. */
        public final String id;
        /** The complete AGSL program, including the shared ambient wrapper. */
        public final String source;
        /** Default values keyed by uniform name; color arrays use {@code capacity * 4} floats. */
        public final Map<String, float[]> uniforms;
        /** Packed color-array capacities keyed by uniform name, in catalog order. */
        public final Map<String, Integer> colorCapacities;
        /** Whether the upstream shader animates over time. */
        public final boolean animated;
        /** Whether the AGSL declares {@code uniform shader u_image}. */
        public final boolean usesImageTexture;
        /** Whether the AGSL declares {@code uniform shader u_noiseTexture}. */
        public final boolean usesNoiseTexture;
        /** Upstream time multiplier; zero for static shaders. */
        public final float speed;

        private Spec(String id, String source, Map<String, float[]> uniforms,
                     Map<String, Integer> colorCapacities, boolean animated,
                     boolean usesImageTexture, boolean usesNoiseTexture, float speed) {
            this.id = id;
            this.source = source;
            this.uniforms = immutableUniforms(uniforms);
            this.colorCapacities = Collections.unmodifiableMap(
                    new LinkedHashMap<>(colorCapacities));
            this.animated = animated;
            this.usesImageTexture = usesImageTexture;
            this.usesNoiseTexture = usesNoiseTexture;
            this.speed = speed;
        }

        private static Map<String, float[]> immutableUniforms(Map<String, float[]> source) {
            Map<String, float[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, float[]> entry : source.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().clone());
            }
            return Collections.unmodifiableMap(copy);
        }
    }

    private static final class Model {
        final List<Spec> specs;
        final Map<String, Spec> byId;
        final boolean noiseTextureAvailable;

        Model(List<Spec> specs, Map<String, Spec> byId, boolean noiseTextureAvailable) {
            this.specs = specs;
            this.byId = byId;
            this.noiseTextureAvailable = noiseTextureAvailable;
        }
    }

    /** @return the spec for an exact upstream id (trimmed, case-insensitive), or null. */
    public static Spec get(String id) {
        if (id == null) return null;
        String value = id.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        return model().byId.get(value);
    }

    /** @return every spec in upstream showcase order. */
    public static List<Spec> all() {
        return model().specs;
    }

    /** @return whether {@code ps_noise.png} is packaged for noise-backed shaders. */
    public static boolean hasNoiseTexture() {
        return model().noiseTextureAvailable;
    }

    private static Model model() {
        Model current = model;
        if (current == null) {
            synchronized (LOCK) {
                current = model;
                if (current == null) {
                    current = load();
                    model = current;
                }
            }
        }
        return current;
    }

    private static Model load() {
        List<Spec> specs = new ArrayList<>();
        Map<String, Spec> byId = new LinkedHashMap<>();
        for (String[] row : catalogRows()) {
            String id = row[0].trim();
            if (id.isEmpty()) {
                throw new IllegalStateException("Paper shader catalog has an empty id");
            }
            Map<String, Integer> capacities = parseCapacities(id, row[5]);
            Map<String, float[]> uniforms = parseUniforms(id, row[6], capacities);
            Spec spec = new Spec(
                    id,
                    readText(RESOURCE_ROOT + id + ".agsl"),
                    uniforms,
                    capacities,
                    parseBoolean(id, "animated", row[1]),
                    parseBoolean(id, "image", row[2]),
                    parseBoolean(id, "noise", row[3]),
                    parseFloat(id, "speed", row[4]));
            Spec previous = byId.put(id, spec);
            if (previous != null) {
                throw new IllegalStateException("Duplicate paper shader id: " + id);
            }
            specs.add(spec);
        }
        if (specs.isEmpty()) {
            throw new IllegalStateException("Paper shader catalog is empty");
        }
        boolean noise = hasResource(RESOURCE_ROOT + "ps_noise.png");
        return new Model(
                Collections.unmodifiableList(specs),
                Collections.unmodifiableMap(byId),
                noise);
    }

    private static List<String[]> catalogRows() {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                open(CATALOG_RESOURCE), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] columns = line.split("\t", -1);
                if (columns.length != CATALOG_COLUMNS) {
                    throw new IllegalStateException(
                            "Paper shader catalog row has " + columns.length + " columns: " + line);
                }
                rows.add(columns);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + CATALOG_RESOURCE, e);
        }
        return rows;
    }

    private static Map<String, Integer> parseCapacities(String id, String raw) {
        Map<String, Integer> capacities = new LinkedHashMap<>();
        if (raw.trim().isEmpty()) return capacities;
        for (String token : raw.split(",")) {
            int equals = token.indexOf('=');
            if (equals <= 0) {
                throw new IllegalStateException("Malformed color capacity for " + id + ": " + token);
            }
            String name = token.substring(0, equals).trim();
            int capacity;
            try {
                capacity = Integer.parseInt(token.substring(equals + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Malformed color capacity for " + id + ": " + token, e);
            }
            if (capacity < 1 || capacity > MAX_COLORS) {
                throw new IllegalStateException(
                        "Color capacity out of range for " + id + ": " + token);
            }
            if (capacities.put(name, capacity) != null) {
                throw new IllegalStateException("Duplicate color capacity for " + id + ": " + name);
            }
        }
        return capacities;
    }

    private static Map<String, float[]> parseUniforms(String id, String raw,
                                                      Map<String, Integer> capacities) {
        Map<String, float[]> uniforms = new LinkedHashMap<>();
        if (raw.trim().isEmpty()) return uniforms;
        for (String token : raw.split(";")) {
            int equals = token.indexOf('=');
            if (equals <= 0) {
                throw new IllegalStateException("Malformed uniform default for " + id + ": " + token);
            }
            String name = token.substring(0, equals).trim();
            String value = token.substring(equals + 1).trim();
            Integer capacity = capacities.get(name);
            float[] packed = capacity == null
                    ? new float[] {parseFloat(id, name, value)}
                    : parseColorArray(id, name, value, capacity);
            if (uniforms.put(name, packed) != null) {
                throw new IllegalStateException("Duplicate uniform default for " + id + ": " + name);
            }
        }
        return uniforms;
    }

    private static float[] parseColorArray(String id, String name, String raw, int capacity) {
        String[] colors = raw.split(",");
        if (colors.length == 0 || colors.length > capacity) {
            throw new IllegalStateException(
                    "Color count exceeds capacity " + capacity + " for " + id + "." + name);
        }
        float[] packed = new float[capacity * 4];
        for (int i = 0; i < colors.length; i++) {
            int[] rgba = parseColor(id, colors[i].trim());
            packed[i * 4] = rgba[0] / 255f;
            packed[i * 4 + 1] = rgba[1] / 255f;
            packed[i * 4 + 2] = rgba[2] / 255f;
            packed[i * 4 + 3] = rgba[3] / 255f;
        }
        return packed;
    }

    private static int[] parseColor(String id, String value) {
        boolean rgb = value.length() == 7;
        boolean rgba = value.length() == 9;
        if (!(rgb || rgba) || value.charAt(0) != '#') {
            throw new IllegalStateException("Malformed color for " + id + ": " + value);
        }
        long packed;
        try {
            packed = Long.parseLong(value.substring(1), 16);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Malformed color for " + id + ": " + value, e);
        }
        int alpha = rgba ? (int) (packed & 0xff) : 0xff;
        int red = (int) ((packed >> (rgba ? 24 : 16)) & 0xff);
        int green = (int) ((packed >> (rgba ? 16 : 8)) & 0xff);
        int blue = (int) ((packed >> (rgba ? 8 : 0)) & 0xff);
        return new int[] {red, green, blue, alpha};
    }

    private static boolean parseBoolean(String id, String field, String raw) {
        String value = raw.trim();
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalStateException("Malformed " + field + " flag for " + id + ": " + raw);
    }

    private static float parseFloat(String id, String field, String raw) {
        float value;
        try {
            value = Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Malformed " + field + " for " + id + ": " + raw, e);
        }
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalStateException("Non-finite " + field + " for " + id + ": " + raw);
        }
        return value;
    }

    private static String readText(String resource) {
        try (InputStream input = open(resource)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    private static InputStream open(String resource) {
        InputStream input = resource(resource);
        if (input == null) {
            throw new IllegalStateException("Missing packaged resource " + resource);
        }
        return input;
    }

    private static InputStream resource(String resource) {
        ClassLoader loader = PaperShaderCatalog.class.getClassLoader();
        InputStream input = loader == null ? null : loader.getResourceAsStream(resource);
        return input != null ? input : PaperShaderCatalog.class.getResourceAsStream("/" + resource);
    }

    private static boolean hasResource(String resource) {
        try (InputStream input = resource(resource)) {
            return input != null;
        } catch (IOException e) {
            return false;
        }
    }
}
