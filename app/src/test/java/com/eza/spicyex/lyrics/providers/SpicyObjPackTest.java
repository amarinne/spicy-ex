package com.eza.spicyex.lyrics.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpicyObjPackTest {
    @Test
    public void unpackDecodesNestedLyricsPayload() {
        JsonArray values = new JsonArray();
        values.add("Type");
        values.add("Syllable");
        values.add("Content");
        values.add("Vocal");

        JsonArray stream = new JsonArray();
        stream.add(-1); // root object
        stream.add(2);
        stream.add(0); // Type
        stream.add(2); // Content
        stream.add(1); // Syllable
        stream.add(-5); // single-item array
        stream.add(-1); // nested object
        stream.add(1);
        stream.add(0); // Type
        stream.add(3); // Vocal

        JsonArray packed = new JsonArray();
        packed.add(values);
        packed.add(stream);

        JsonElement unpacked = SpicyObjPack.unpack(packed);

        assertTrue(unpacked.isJsonObject());
        assertEquals("Syllable", unpacked.getAsJsonObject().get("Type").getAsString());
        assertEquals("Vocal", unpacked.getAsJsonObject()
                .getAsJsonArray("Content")
                .get(0)
                .getAsJsonObject()
                .get("Type")
                .getAsString());
    }
}
