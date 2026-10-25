package android.content;

/** JVM-only component fixture: the two fields the bridge bind routes through. */
public class ComponentName {
    private final String packageName;
    private final String className;

    public ComponentName(String packageName, String className) {
        this.packageName = packageName;
        this.className = className;
    }

    public String getPackageName() { return packageName; }
    public String getClassName() { return className; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ComponentName)) return false;
        ComponentName name = (ComponentName) other;
        return java.util.Objects.equals(packageName, name.packageName)
                && java.util.Objects.equals(className, name.className);
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(packageName, className);
    }

    @Override public String toString() {
        return "ComponentName{" + packageName + "/" + className + "}";
    }
}
