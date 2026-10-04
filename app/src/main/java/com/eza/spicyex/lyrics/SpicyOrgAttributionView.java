package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import java.net.URI;

/** Response credit with the contributor links supplied by the provider. */
public final class SpicyOrgAttributionView extends TextView {
    private LyricsDocument boundDocument;
    private boolean boundIncludeProvider = true;

    public SpicyOrgAttributionView(Context context) {
        super(context);
        setTextSize(12);
        setTextColor(Color.rgb(210, 210, 210));
        setLinkTextColor(Color.WHITE);
        setGravity(Gravity.CENTER);
        int padding = Math.round(6 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        setMovementMethod(LinkMovementMethod.getInstance());
        setVisibility(View.GONE);
    }

    public void bind(LyricsDocument document) {
        bind(document, true);
    }

    public void bind(LyricsDocument document, boolean includeProvider) {
        if (boundDocument == document && boundIncludeProvider == includeProvider) return;
        SpannableStringBuilder text = new SpannableStringBuilder();
        boolean previousContributor = false;
        for (SpicyOrgAttribution.Credit credit : SpicyOrgAttribution.credits(document, includeProvider)) {
            if (text.length() > 0) text.append(previousContributor ? ", " : "\n");
            int start = text.length() + Math.max(0, credit.nameStart);
            text.append(credit.label);
            previousContributor = credit.nameStart >= 0;
            if (credit.nameStart >= 0 && webLink(credit.url)) text.setSpan(new URLSpan(credit.url), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        boundDocument = document;
        boundIncludeProvider = includeProvider;
        setText(text);
        setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private static boolean webLink(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) { return false; }
    }
}
