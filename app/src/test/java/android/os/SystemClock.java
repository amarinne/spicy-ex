package android.os;

/** JVM-only clock fixture. Paused packet tests do not advance position with this clock. */
public final class SystemClock {
    /** Frozen default keeps every preexisting paused-clock test unchanged. */
    private static long elapsedRealtimeForTest = 1000L;

    private SystemClock() { }

    public static long elapsedRealtime() { return elapsedRealtimeForTest; }

    /** Bounded-attempt tests move the clock and restore the frozen default afterwards. */
    public static void setElapsedRealtimeForTest(long value) { elapsedRealtimeForTest = value; }
}
