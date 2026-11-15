package android.graphics;

/** JVM sentinel fixture only. This does not simulate rendering or image export. */
public class Bitmap {
    public enum Config { ARGB_8888 }

    private boolean recycled;

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap();
    }

    public boolean isRecycled() {
        return recycled;
    }

    public void recycle() {
        recycled = true;
    }

    public Config getConfig() {
        return Config.ARGB_8888;
    }

    public Bitmap copy(Config config, boolean mutable) {
        Bitmap copy = new Bitmap();
        copy.recycled = recycled;
        return copy;
    }
}
