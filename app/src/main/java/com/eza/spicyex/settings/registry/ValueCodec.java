package com.eza.spicyex.settings.registry;

/** Converts a numeric UI stop to and from the existing string persistence format. */
public interface ValueCodec {
    int decode(String stored);
    String encode(int value);
}
