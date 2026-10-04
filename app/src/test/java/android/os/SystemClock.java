package android.os;

/** JVM-only clock fixture. Paused packet tests do not advance position with this clock. */
public final class SystemClock {
    private SystemClock() { }
    public static long elapsedRealtime() { return 1000L; }
}
