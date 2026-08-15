package com.eza.spicyex.lyrics.session;

/** Which producer owns a derived layer's current artifact. */
public enum LayerAuthority {
    /** On-device deterministic packages (romanizers, reading processors, provider translations). */
    DETERMINISTIC,
    /** Machine backends reached over the network (Google today). */
    MACHINE,
    /** Model-generated output. Not implemented on mobile yet; reserved so guards stay total. */
    AI
}
