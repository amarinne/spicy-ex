package com.eza.spicyex.sharecard;

import android.graphics.Bitmap;

/** The shared card, drawn once however often it is shared or saved. */
final class CardRecipe {
    interface Maker {
        Bitmap make(boolean rounded) throws Exception;
    }

    private final Maker make;
    private Bitmap square;

    CardRecipe(Maker make) {
        this.make = make;
    }

    /** The image shared, saved and sent to chats: square corners, the backdrop to the edge. */
    synchronized Bitmap get() throws Exception {
        if (square == null) square = make.make(false);
        return square;
    }
}
