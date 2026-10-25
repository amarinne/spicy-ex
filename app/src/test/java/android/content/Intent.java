package android.content;

/** JVM-only intent fixture: explicit components only, as the bridge publisher builds them. */
public class Intent {
    private ComponentName component;

    public Intent() { }

    public Intent setComponent(ComponentName name) {
        component = name;
        return this;
    }

    public ComponentName getComponent() { return component; }
}
