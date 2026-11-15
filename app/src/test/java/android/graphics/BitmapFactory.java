package android.graphics;

/** JVM sentinel fixture only. This returns opaque bitmaps and decodes no image data. */
public class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public int outWidth;
        public int outHeight;
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options options) {
        if (data == null || length <= 0) return null;
        if (options != null && options.inJustDecodeBounds) {
            options.outWidth = 64;
            options.outHeight = 64;
            return null;
        }
        return Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
    }
}
