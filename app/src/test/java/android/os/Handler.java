package android.os;

/**
 * JVM-only dispatch fixture. The coordinator ships its configuration flips through a main-loop
 * handler; the fake runs {@code post} inline so a test observes the flip synchronously.
 * Delayed messages neither loop nor queue, so polling chains cannot spin on the JVM.
 */
public class Handler {
    public Handler() { }
    public Handler(Looper looper) { }

    public boolean post(Runnable runnable) {
        if (runnable != null) runnable.run();
        return true;
    }

    /** No queues on the JVM: a delayed step stays scheduled-out unless its caller runs it. */
    public boolean postDelayed(Runnable runnable, long delayMillis) { return false; }

    public void removeCallbacks(Runnable runnable) { }
}
