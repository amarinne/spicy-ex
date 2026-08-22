package com.eza.spicyex.lyrics.ai;

/** Privacy-safe linkage diagnostics for code running inside Spotify's class loader. */
public final class AiRuntimeFailureLog {

    private AiRuntimeFailureLog() {
    }

    public static String describe(Throwable failure) {
        if (failure == null) return "unknown";
        StringBuilder types = new StringBuilder();
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 6; depth++) {
            if (types.length() > 0) types.append('>');
            types.append(cursor.getClass().getSimpleName());
            Throwable next = cursor.getCause();
            if (next == cursor) break;
            cursor = next;
        }
        String at = firstAiFrame(failure);
        if (!at.isEmpty()) types.append(":at=").append(at);
        return types.toString();
    }

    private static String firstAiFrame(Throwable failure) {
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < 6; depth++) {
            for (StackTraceElement frame : cursor.getStackTrace()) {
                String owner = frame.getClassName();
                if (!owner.startsWith("com.eza.spicyex.lyrics.ai.")) continue;
                int split = owner.lastIndexOf('.');
                return owner.substring(split + 1) + "." + frame.getMethodName();
            }
            Throwable next = cursor.getCause();
            if (next == cursor) break;
            cursor = next;
        }
        return "";
    }
}
