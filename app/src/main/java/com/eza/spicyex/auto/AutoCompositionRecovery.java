package com.eza.spicyex.auto;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** One deferred invalidation of existing native scopes per observed composition, per connection. */
final class AutoCompositionRecovery {
    private static final int MAX_COMPOSERS = 8, MAX_SLOTS = 32768;
    private final Field[] tables;
    private final Field slots;
    private final Method invalidate;
    private final List<WeakReference<Object>> composers = new ArrayList<>(MAX_COMPOSERS);
    private final Set<Object> recovered = Collections.newSetFromMap(new IdentityHashMap<>());
    long compositions, scopes, errors;

    AutoCompositionRecovery(Field[] tables, Field slots, Method invalidate) {
        this.tables = tables;
        this.slots = slots;
        this.invalidate = invalidate;
    }

    void remember(Object composer) {
        if (composers.size() >= MAX_COMPOSERS || !tables[0].getDeclaringClass().isInstance(composer)) return;
        for (WeakReference<Object> ref : composers) if (ref.get() == composer) return;
        composers.add(new WeakReference<>(composer));
    }

    /**
     * A new car connection is a new chance: native state may have composed while no hook was
     * installed, and the previous connection's attempt proves nothing about this one.
     */
    void reset() { recovered.clear(); }

    /** Run on the main thread after composition returns, never inside a hooked composable. */
    void recover() {
        for (Object composer : live()) {
            if (!recovered.add(composer)) continue;
            compositions++;
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                for (Field tableField : tables) {
                    Object table = tableField.get(composer);
                    if (table == null) continue;
                    Object[] values = (Object[]) slots.get(table);
                    if (values == null) continue;
                    if (values.length > MAX_SLOTS) throw new IllegalStateException("Composition slot budget exceeded");
                    for (Object scope : values.clone()) {
                        if (!invalidate.getDeclaringClass().isInstance(scope) || !seen.add(scope)) continue;
                        try { invalidate.invoke(scope); scopes++; }
                        catch (ReflectiveOperationException error) { errors++; }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) { errors++; }
        }
    }

    private List<Object> live() {
        List<Object> live = new ArrayList<>(composers.size());
        for (WeakReference<Object> ref : composers) {
            Object composer = ref.get();
            if (composer != null) live.add(composer);
        }
        return live;
    }
}
