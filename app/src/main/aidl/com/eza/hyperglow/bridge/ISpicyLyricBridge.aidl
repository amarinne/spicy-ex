package com.eza.hyperglow.bridge;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

oneway interface ISpicyLyricBridge {
    void publishState(in Bundle state);
    void publishDocument(in Bundle metadata, in ParcelFileDescriptor document);
    void clearState(String producerId, long generation);
}
