package android.net;

/** JVM-only value fixture for the provider's static transport address. */
public final class Uri {
    private final String value;
    private Uri(String value) { this.value = value; }
    public static Uri parse(String value) { return new Uri(value); }
    // Transport tests only need equivalent query values, not Android's exact escape spelling.
    public static String encode(String value) {
        return value == null ? null : java.net.URLEncoder.encode(value,
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
    @Override public String toString() { return value; }
}
