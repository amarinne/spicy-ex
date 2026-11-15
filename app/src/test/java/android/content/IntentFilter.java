package android.content;

/** JVM fixture for the authenticated Auto host receiver registration. */
public final class IntentFilter {
    public final String action;
    public String scheme;
    public IntentFilter(String action) { this.action = action; }
    public void addDataScheme(String scheme) { this.scheme = scheme; }
}
