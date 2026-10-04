package android.graphics;

/** JVM sentinel fixture only. This does not simulate rendering or image export. */
public class Bitmap {
    public enum Config { ARGB_8888 }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap();
    }
}
