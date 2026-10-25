package android.os;

/** JVM-only loop fixture. Loops carry no queues; the Handler decides dispatch. */
public final class Looper {
    private static final Looper MAIN = new Looper();

    private Looper() { }

    public static Looper getMainLooper() { return MAIN; }
}
