package com.eza.spicyex.lyrics.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.HashSet;
import java.util.Set;

/**
 * Java unpacker for Spicy Lyrics' SLObjPack payloads.
 *
 * The desktop 6.x client receives query result.data as [valuesList, opcodeStream] and unpacks it
 * before normal lyrics parsing. Keep this class Android-free so response fixtures can be tested.
 */
public final class SpicyObjPack {
    private static final int MAX_DEPTH = 512;
    private static final int MAX_ARRAY_LENGTH = 1 << 20;
    private static final int MAX_OBJECT_KEYS = 1 << 16;
    private static final int MAX_STREAM_LENGTH = 1 << 24;
    private static final int MAX_VALUES_LENGTH = 1 << 22;
    private static final int MAX_DECODE_OPS = 1 << 22;
    private static final Set<String> FORBIDDEN_KEYS = new HashSet<>();

    static {
        FORBIDDEN_KEYS.add("__proto__");
        FORBIDDEN_KEYS.add("constructor");
        FORBIDDEN_KEYS.add("prototype");
    }

    private SpicyObjPack() {
    }

    public static boolean isPackedPayload(JsonElement element) {
        if (element == null || !element.isJsonArray()) return false;
        JsonArray payload = element.getAsJsonArray();
        if (payload.size() != 2) return false;
        return payload.get(0).isJsonArray() && payload.get(1).isJsonArray();
    }

    public static JsonElement unpack(JsonElement packed) {
        return new Decoder(packed).unpack();
    }

    private static final class Decoder {
        private final JsonArray values;
        private final JsonArray stream;
        private int cursor;

        Decoder(JsonElement packed) {
            if (!isPackedPayload(packed)) {
                throw new IllegalArgumentException("SLObjPack unpack: Invalid payload structure");
            }
            JsonArray payload = packed.getAsJsonArray();
            values = payload.get(0).getAsJsonArray();
            stream = payload.get(1).getAsJsonArray();
            if (values.size() > MAX_VALUES_LENGTH) {
                throw new IllegalArgumentException("SLObjPack unpack: valuesList exceeds limit");
            }
            if (stream.size() > MAX_STREAM_LENGTH) {
                throw new IllegalArgumentException("SLObjPack unpack: stream exceeds limit");
            }
            validateValues();
        }

        JsonElement unpack() {
            JsonElement result = decode(0);
            if (cursor != stream.size()) {
                throw new IllegalArgumentException("SLObjPack unpack: Extra data after decoding");
            }
            return result;
        }

        private void validateValues() {
            for (int i = 0; i < values.size(); i++) {
                JsonElement value = values.get(i);
                if (value == null || value.isJsonNull()) continue;
                if (!value.isJsonPrimitive()) {
                    throw new IllegalArgumentException("SLObjPack unpack: Invalid valuesList entry at " + i);
                }
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (primitive.isString() || primitive.isBoolean()) continue;
                if (primitive.isNumber()) {
                    double number = primitive.getAsDouble();
                    if (Double.isNaN(number) || Double.isInfinite(number)) {
                        throw new IllegalArgumentException("SLObjPack unpack: Non-finite number in valuesList at " + i);
                    }
                    continue;
                }
                throw new IllegalArgumentException("SLObjPack unpack: Invalid valuesList entry at " + i);
            }
        }

        private JsonElement readStream() {
            if (cursor >= stream.size()) {
                throw new IllegalArgumentException("SLObjPack unpack: Unexpected end of stream");
            }
            return stream.get(cursor++);
        }

        private int readInt(String label) {
            JsonElement element = readStream();
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("SLObjPack unpack: Invalid " + label + " " + element);
            }
            double number = element.getAsDouble();
            int asInt = (int) number;
            if (Double.isNaN(number) || Double.isInfinite(number) || asInt != number) {
                throw new IllegalArgumentException("SLObjPack unpack: Invalid " + label + " " + element);
            }
            return asInt;
        }

        private JsonElement resolvePointer(int pointer) {
            if (pointer < 0 || pointer >= values.size()) {
                throw new IllegalArgumentException("SLObjPack unpack: Invalid value pointer " + pointer);
            }
            JsonElement value = values.get(pointer);
            return value == null ? JsonNull.INSTANCE : value.deepCopy();
        }

        private String readKey() {
            JsonElement keyElement = resolvePointer(readInt("key pointer"));
            if (keyElement == null || !keyElement.isJsonPrimitive() || !keyElement.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("SLObjPack unpack: Keys must be strings");
            }
            String key = keyElement.getAsString();
            if (FORBIDDEN_KEYS.contains(key)) {
                throw new IllegalArgumentException("SLObjPack unpack: Forbidden key: " + key);
            }
            return key;
        }

        private int readCount(int max, String label) {
            int count = readInt(label + " count");
            if (count < 0 || count > max) {
                throw new IllegalArgumentException("SLObjPack unpack: Invalid " + label + " count: " + count);
            }
            return count;
        }

        private void requireStream(int min, String label) {
            if (min > stream.size() - cursor) {
                throw new IllegalArgumentException("SLObjPack unpack: " + label + " exceeds remaining stream");
            }
        }

        private JsonElement decode(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("SLObjPack unpack: Max depth exceeded");
            }
            int op = readInt("opcode");
            if (op >= 0) return resolvePointer(op);

            switch (op) {
                case -1:
                    return decodeObject(depth);
                case -2:
                    return decodeArray(depth);
                case -3:
                    return decodeSchemaArray(depth);
                case -4:
                    return new JsonArray();
                case -5: {
                    JsonArray one = new JsonArray();
                    one.add(decode(depth + 1));
                    return one;
                }
                case -6:
                    return new JsonObject();
                default:
                    throw new IllegalArgumentException("SLObjPack unpack: Unknown opcode " + op);
            }
        }

        private JsonElement decodeObject(int depth) {
            int count = readCount(MAX_OBJECT_KEYS, "object key");
            requireStream(count * 2, "object");
            String[] keys = new String[count];
            for (int i = 0; i < count; i++) keys[i] = readKey();
            JsonObject object = new JsonObject();
            for (int i = 0; i < count; i++) object.add(keys[i], decode(depth + 1));
            return object;
        }

        private JsonElement decodeArray(int depth) {
            int count = readCount(MAX_ARRAY_LENGTH, "array item");
            requireStream(count, "array");
            JsonArray array = new JsonArray();
            for (int i = 0; i < count; i++) array.add(decode(depth + 1));
            return array;
        }

        private JsonElement decodeSchemaArray(int depth) {
            int items = readCount(MAX_ARRAY_LENGTH, "schema array item");
            int keysCount = readCount(MAX_OBJECT_KEYS, "schema key");
            if ((long) items * (long) keysCount > MAX_DECODE_OPS) {
                throw new IllegalArgumentException("SLObjPack unpack: Schema array decode budget exceeded");
            }
            requireStream(keysCount + items * keysCount, "schema array");
            String[] keys = new String[keysCount];
            for (int i = 0; i < keysCount; i++) keys[i] = readKey();
            JsonArray array = new JsonArray();
            for (int i = 0; i < items; i++) {
                JsonObject object = new JsonObject();
                for (int k = 0; k < keysCount; k++) {
                    object.add(keys[k], decode(depth + 1));
                }
                array.add(object);
            }
            return array;
        }
    }
}
