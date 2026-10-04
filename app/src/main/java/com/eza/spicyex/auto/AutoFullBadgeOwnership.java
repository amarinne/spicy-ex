package com.eza.spicyex.auto;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Remembers a full-metadata painter across independent child composition calls. */
final class AutoFullBadgeOwnership {
    private static final class Painter {
        final WeakReference<Object> value;
        boolean full, compact;
        Painter(Object value) { this.value = new WeakReference<>(value); }
    }
    private final List<Painter> painters = new ArrayList<>();
    private final ThreadLocal<List<Boolean>> activePainters = new ThreadLocal<>();

    synchronized boolean enter(Object painter, boolean fullMetadata, boolean compactMetadata) {
        Painter owner = null;
        for (int i = painters.size() - 1; i >= 0; i--) {
            Object existing = painters.get(i).value.get();
            if (existing == null) painters.remove(i);
            else if (existing == painter) owner = painters.get(i);
        }
        if (owner == null) { owner = new Painter(painter); painters.add(owner); }
        owner.full |= fullMetadata;
        owner.compact |= compactMetadata;
        boolean owned = owner.full && !owner.compact;
        List<Boolean> stack = activePainters.get();
        if (stack == null) { stack = new ArrayList<>(); activePainters.set(stack); }
        stack.add(owned);
        return owned;
    }

    void exit() {
        List<Boolean> stack = activePainters.get();
        if (stack == null) return;
        stack.remove(stack.size() - 1);
        if (stack.isEmpty()) activePainters.remove();
    }

    boolean suppress(boolean fullTakeover, boolean spotify) {
        List<Boolean> stack = activePainters.get();
        return fullTakeover && spotify && stack != null && stack.get(stack.size() - 1);
    }
}
