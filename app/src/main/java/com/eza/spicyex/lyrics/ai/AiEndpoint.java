package com.eza.spicyex.lyrics.ai;

import java.util.Locale;

/**
 * Validates and normalizes a custom OpenAI-compatible base URL.
 *
 * <p>The rules are narrow on purpose, and each one closes a way a key could leak or a request could
 * go somewhere the owner did not intend:
 *
 * <ul>
 *   <li><b>HTTPS, or loopback HTTP.</b> Plain HTTP to a remote host would put a bearer token on the
 *       wire in clear text. Loopback is exempt because a local proxy is the whole point of this
 *       option and there is no network to intercept.</li>
 *   <li><b>No embedded credentials.</b> A {@code user:pass@host} URL puts secrets into every log
 *       line and error message that ever prints the endpoint.</li>
 *   <li><b>No query and no fragment.</b> Those are where an API key ends up when someone pastes the
 *       example from a provider's quickstart, and they travel into proxy logs.</li>
 * </ul>
 *
 * <p>The normalized form enters {@code configId}, so switching endpoints cannot silently reuse
 * another endpoint's answers.
 */
public final class AiEndpoint {

    private AiEndpoint() {
    }

    /** Why an endpoint was refused. The token is shown to the owner; it names the rule, not the URL. */
    public enum Problem {
        NONE,
        EMPTY,
        MALFORMED,
        INSECURE,
        HAS_CREDENTIALS,
        HAS_QUERY_OR_FRAGMENT
    }

    public static final class Validated {
        public final String normalized;
        public final Problem problem;

        private Validated(String normalized, Problem problem) {
            this.normalized = AiText.nz(normalized);
            this.problem = problem;
        }

        public boolean ok() {
            return problem == Problem.NONE;
        }
    }

    public static Validated validate(String raw) {
        String value = AiText.nz(raw).trim();
        if (value.isEmpty()) return new Validated("", Problem.EMPTY);
        if (value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            return new Validated("", Problem.HAS_QUERY_OR_FRAGMENT);
        }

        java.net.URI uri;
        try {
            uri = new java.net.URI(value);
        } catch (java.net.URISyntaxException malformed) {
            return new Validated("", Problem.MALFORMED);
        }
        String scheme = AiText.nz(uri.getScheme()).toLowerCase(Locale.ROOT);
        String host = AiText.nz(uri.getHost()).toLowerCase(Locale.ROOT);
        if (host.isEmpty() || scheme.isEmpty()) return new Validated("", Problem.MALFORMED);
        if (uri.getUserInfo() != null) return new Validated("", Problem.HAS_CREDENTIALS);
        if (!"https".equals(scheme) && !("http".equals(scheme) && isLoopback(host))) {
            return new Validated("", Problem.INSECURE);
        }

        StringBuilder normalized = new StringBuilder(scheme).append("://").append(host);
        if (uri.getPort() > 0) normalized.append(':').append(uri.getPort());
        String path = AiText.nz(uri.getPath());
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        normalized.append(path);
        return new Validated(normalized.toString(), Problem.NONE);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                || "[::1]".equals(host);
    }

    /** The host alone, for the consent scope and for anything shown to the owner. */
    public static String hostOf(String normalized) {
        Validated validated = validate(normalized);
        if (!validated.ok()) return "";
        try {
            return AiText.nz(new java.net.URI(validated.normalized).getHost());
        } catch (java.net.URISyntaxException malformed) {
            return "";
        }
    }
}
