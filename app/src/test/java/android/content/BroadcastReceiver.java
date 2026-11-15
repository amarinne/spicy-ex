package android.content;

/** JVM fixture. Tests deliver host notices through the production receiver. */
public abstract class BroadcastReceiver {
    public BroadcastReceiver() { }
    public abstract void onReceive(Context context, Intent intent);
}
