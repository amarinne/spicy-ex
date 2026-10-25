package android.content;

import android.net.Uri;
import android.os.Bundle;

/**
 * Unit-test local ContentResolver replacement. The real class throws "Stub!" for every member
 * and its call lane is final, so the bridge publisher's provider path can neither be observed
 * nor steered through a subclass. This shadow replaces it on the unit-test classpath and
 * exposes exactly what the tests drive: the {@link #call} the publisher makes, routed through
 * a handler when one is installed, and refusing the call when none is, like an absent
 * companion provider.
 *
 * <p>Main code reaches other members (insert, query, delete) at runtime only in production
 * hook lanes that no unit test drives, so they keep their delegation-free stubs here.
 */
public class ContentResolver {
    /** Installed by the fake Android context under test; null refuses every call. */
    public static volatile Handler testHandler;

    /** The call the bridge publisher issues and the tests want to record. */
    public interface Handler {
        Bundle call(Uri uri, String method, String arg, Bundle extras);
    }

    public ContentResolver() {
    }

    public ContentResolver(Context context) {
    }

    public Bundle call(Uri uri, String method, String arg, Bundle extras) {
        Handler handler = testHandler;
        if (handler == null) throw new RuntimeException("provider stub refused " + method);
        return handler.call(uri, method, arg, extras);
    }
}
