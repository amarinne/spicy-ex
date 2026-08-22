package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON reader for provider responses.
 *
 * <p>Strict on purpose, and hand-written rather than delegated for the same reason. A lenient
 * parser accepts unquoted keys, single quotes, trailing commas, and text after the closing brace —
 * so a model that half-followed the contract would look like a model that followed it, and the one
 * signal that tells us the response shape is drifting would be swallowed. Only the single code
 * fence is tolerated, and only in {@link AiResponseReader}.
 *
 * <p>Values come back as {@link Map}, {@link List}, {@link String}, {@link Double}, {@link Boolean},
 * or null, so the validator can check types itself rather than trusting a binding.
 */
public final class AiJson {
    private final String source;
    private int at;

    private AiJson(String source) {
        this.source = source;
    }

    /** @throws AiProtocolException {@code invalid_json} on anything that is not exactly one value */
    public static Object parseStrict(String raw) {
        AiJson parser = new AiJson(AiText.nz(raw));
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.at != parser.source.length()) throw invalid();
        return value;
    }

    private static AiProtocolException invalid() {
        return new AiProtocolException("invalid_json");
    }

    private Object readValue(int depth) {
        if (depth > 64) throw invalid();
        if (at >= source.length()) throw invalid();
        char c = source.charAt(at);
        switch (c) {
            case '{': return readObject(depth);
            case '[': return readArray(depth);
            case '"': return readString();
            case 't': return readLiteral("true", Boolean.TRUE);
            case 'f': return readLiteral("false", Boolean.FALSE);
            case 'n': return readLiteral("null", null);
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        at++;
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return out;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw invalid();
            String key = readString();
            skipWhitespace();
            if (peek() != ':') throw invalid();
            at++;
            skipWhitespace();
            out.put(key, readValue(depth + 1));
            skipWhitespace();
            char next = peek();
            at++;
            if (next == '}') return out;
            if (next != ',') throw invalid();
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> out = new ArrayList<>();
        at++;
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            skipWhitespace();
            out.add(readValue(depth + 1));
            skipWhitespace();
            char next = peek();
            at++;
            if (next == ']') return out;
            if (next != ',') throw invalid();
        }
    }

    private String readString() {
        if (peek() != '"') throw invalid();
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= source.length()) throw invalid();
            char c = source.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw invalid();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= source.length()) throw invalid();
            char escape = source.charAt(at++);
            switch (escape) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (at + 4 > source.length()) throw invalid();
                    String hex = source.substring(at, at + 4);
                    for (int i = 0; i < 4; i++) {
                        if (Character.digit(hex.charAt(i), 16) < 0) throw invalid();
                    }
                    out.append((char) Integer.parseInt(hex, 16));
                    at += 4;
                    break;
                default: throw invalid();
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!source.startsWith(literal, at)) throw invalid();
        at += literal.length();
        return value;
    }

    private Double readNumber() {
        int start = at;
        if (peek() == '-') at++;
        int digits = at;
        while (at < source.length() && isDigit(source.charAt(at))) at++;
        if (at == digits) throw invalid();
        if (source.charAt(digits) == '0' && at - digits > 1) throw invalid();
        if (at < source.length() && source.charAt(at) == '.') {
            at++;
            int fraction = at;
            while (at < source.length() && isDigit(source.charAt(at))) at++;
            if (at == fraction) throw invalid();
        }
        if (at < source.length() && (source.charAt(at) == 'e' || source.charAt(at) == 'E')) {
            at++;
            if (at < source.length() && (source.charAt(at) == '+' || source.charAt(at) == '-')) at++;
            int exponent = at;
            while (at < source.length() && isDigit(source.charAt(at))) at++;
            if (at == exponent) throw invalid();
        }
        try {
            return Double.valueOf(source.substring(start, at));
        } catch (NumberFormatException notANumber) {
            throw invalid();
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private char peek() {
        if (at >= source.length()) throw invalid();
        return source.charAt(at);
    }

    private void skipWhitespace() {
        while (at < source.length()) {
            char c = source.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            at++;
        }
    }
}
