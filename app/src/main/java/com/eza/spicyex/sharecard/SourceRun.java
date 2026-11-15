package com.eza.spicyex.sharecard;

import android.widget.TextView;

/** One of the row's text views and where its text sits in the line. */
final class SourceRun {
    final TextView view;
    final int quoteStart;
    final int viewStart;
    final int length;

    SourceRun(TextView view, int quoteStart, int viewStart, int length) {
        this.view = view;
        this.quoteStart = quoteStart;
        this.viewStart = viewStart;
        this.length = length;
    }
}
