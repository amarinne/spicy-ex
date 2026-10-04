package com.eza.spicyex.auto;

import java.util.LinkedHashMap;
import java.util.Map;

/** Records independent Android Auto hook stages without letting one optional failure stop others. */
final class AutoHookInstallation {
    interface Action { void run() throws Throwable; }
    private final Map<String, String> results = new LinkedHashMap<>();

    boolean attempt(String role, Action action) {
        try {
            action.run();
            synchronized (results) { results.put(role, "installed"); }
            return true;
        } catch (Throwable error) {
            synchronized (results) { results.put(role, describe(error)); }
            return false;
        }
    }

    static String describe(Throwable error) {
        return error + (error.getCause() == null ? "" : "; caused by " + error.getCause());
    }

    String error(String role) { synchronized (results) { return results.get(role); } }
    String summary() { synchronized (results) { return results.toString(); } }
}
