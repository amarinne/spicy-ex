package com.eza.spicyex.xposed;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Small, build-scoped symbol records. Only completed discoveries are persisted. */
public final class ResolvedSymbolCache {
    public interface Discovery<T> { T run() throws Exception; }
    private final File file;
    private final ClassLoader loader;
    private final Properties records = new Properties();
    private int hits;
    private int misses;

    public ResolvedSymbolCache(File file, String identity, ClassLoader loader) {
        this.file = file;
        this.loader = loader;
        try {
            if (file != null && file.length() <= 256 * 1024) {
                try (FileInputStream input = new FileInputStream(file)) { records.load(input); }
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            records.clear();
        }
        if (!identity.equals(records.getProperty("identity"))) records.clear();
        records.setProperty("identity", identity);
    }

    public synchronized List<Class<?>> classes(String key, Discovery<List<String>> discover)
            throws Exception {
        String recordKey = "classes." + key;
        String saved = records.getProperty(recordKey);
        if (saved != null) {
            try {
                List<Class<?>> result = new ArrayList<>();
                if (!saved.isEmpty()) {
                    for (String name : saved.split("\n", -1)) {
                        Class<?> cls = Class.forName(name, false, loader);
                        // Also verify referenced method types before accepting a cached candidate.
                        cls.getDeclaredMethods();
                        result.add(cls);
                    }
                }
                hits++;
                return result;
            } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
                records.remove(recordKey);
            }
        }
        misses++;
        List<String> names = discover.run();
        List<Class<?>> result = new ArrayList<>();
        boolean complete = true;
        for (String name : names) {
            try {
                Class<?> cls = Class.forName(name, false, loader);
                cls.getDeclaredMethods();
                result.add(cls);
            } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
                complete = false;
            }
        }
        // Preserve usable candidates, but retry incomplete class loading on the next startup.
        if (complete) records.setProperty(recordKey, String.join("\n", names));
        save();
        return result;
    }

    public synchronized Method method(String key, Discovery<Method> discover) throws Exception {
        String recordKey = "method." + key;
        String saved = records.getProperty(recordKey);
        if (saved != null) {
            try {
                int split = saved.indexOf('|');
                if (split > 0) {
                    Class<?> owner = Class.forName(saved.substring(0, split), false, loader);
                    for (Method method : owner.getDeclaredMethods()) {
                        if (signature(method).equals(saved)) {
                            hits++;
                            return method;
                        }
                    }
                }
            } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
                // Re-discover stale or incomplete records with the current loader.
            }
            records.remove(recordKey);
        }
        misses++;
        Method result = discover.run();
        records.setProperty(recordKey, signature(result));
        save();
        return result;
    }

    static String signature(Method method) {
        StringBuilder value = new StringBuilder(method.getDeclaringClass().getName())
                .append('|').append(method.getName()).append('|').append(method.getReturnType().getName())
                .append('|').append(method.getModifiers());
        for (Class<?> type : method.getParameterTypes()) value.append('|').append(type.getName());
        return value.toString();
    }

    private void save() {
        if (file == null) return;
        File temporary = null;
        try {
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) return;
            temporary = File.createTempFile("symbols-", ".tmp", parent);
            try (FileOutputStream output = new FileOutputStream(temporary)) { records.store(output, null); }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException ignored) {
            // Cache storage is optional; resolved runtime symbols remain usable.
        } finally {
            if (temporary != null) temporary.delete();
        }
    }

    public synchronized String stats() { return "hits=" + hits + " misses=" + misses; }
}
