package com.eza.spicyex.sharecard;

import android.graphics.Bitmap;

/** A word in flight: its look in the list and on the card, and both places. */
final class FlyingWord {
    int order;
    Bitmap source;
    float sourceSize, sourceOriginX, sourceBaseline;
    float fromX, fromBaseline, fromScreenSize;
    Bitmap card;
    float cardSize, cardOriginX, cardBaseline;
    float toX, toBaseline, toScreenSize;
}
