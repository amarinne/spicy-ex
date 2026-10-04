package android.os;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

/** JVM-only in-memory transport fixture. No parcel, Binder, or platform behavior is simulated. */
public class Bundle {
    private final HashMap<String, Object> values = new HashMap<>();
    public Bundle() { }
    public Bundle(Bundle source) { values.putAll(source.values); }
    public Set<String> keySet() { return values.keySet(); }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public void remove(String key) { values.remove(key); }
    public void putAll(Bundle source) { values.putAll(source.values); }
    public void putString(String key, String value) { values.put(key, value); }
    public String getString(String key) { return getString(key, null); }
    public String getString(String key, String fallback) { Object v = values.get(key); return v instanceof String ? (String) v : fallback; }
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public boolean getBoolean(String key) { return getBoolean(key, false); }
    public boolean getBoolean(String key, boolean fallback) { Object v = values.get(key); return v instanceof Boolean ? (Boolean) v : fallback; }
    public void putInt(String key, int value) { values.put(key, value); }
    public int getInt(String key) { return getInt(key, 0); }
    public int getInt(String key, int fallback) { Object v = values.get(key); return v instanceof Integer ? (Integer) v : fallback; }
    public void putDouble(String key, double value) { values.put(key, value); }
    public double getDouble(String key, double fallback) { Object v = values.get(key); return v instanceof Double ? (Double) v : fallback; }
    public void putLong(String key, long value) { values.put(key, value); }
    public long getLong(String key) { return getLong(key, 0); }
    public long getLong(String key, long fallback) { Object v = values.get(key); return v instanceof Long ? (Long) v : fallback; }
    public void putBundle(String key, Bundle value) { values.put(key, value); }
    public Bundle getBundle(String key) { return (Bundle) values.get(key); }
    public void putIntArray(String key, int[] value) { values.put(key, value); }
    public int[] getIntArray(String key) { return (int[]) values.get(key); }
    public void putStringArrayList(String key, ArrayList<String> value) { values.put(key, value); }
    @SuppressWarnings("unchecked") public ArrayList<String> getStringArrayList(String key) { return (ArrayList<String>) values.get(key); }
    public <T> void putParcelableArrayList(String key, ArrayList<T> value) { values.put(key, value); }
    @SuppressWarnings("unchecked") public <T> ArrayList<T> getParcelableArrayList(String key) { return (ArrayList<T>) values.get(key); }
}
