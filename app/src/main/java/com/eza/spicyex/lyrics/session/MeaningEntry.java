package com.eza.spicyex.lyrics.session;

/** Translation output for one canonical row. Never carries reading text. */
public final class MeaningEntry implements LayerEntry {
    public final String rowId;
    public final String text;
    public final String targetLanguage;

    public MeaningEntry(String rowId, String text, String targetLanguage) {
        this.rowId = Digests.nz(rowId);
        this.text = Digests.nz(text);
        this.targetLanguage = Digests.nz(targetLanguage);
    }

    @Override public String rowId() {
        return rowId;
    }

    @Override public String digestPayload() {
        return rowId + Digests.SEP + targetLanguage + Digests.SEP + text;
    }
}
