package android.os;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Unit-test local parcel pipes. The real class throws "not mocked" for createPipe, which the
 * bridge publisher needs to hand a document across the AIDL surface it shares with the fake
 * bridge binder: this shadow only supports creating a pipe, closing a side, and handing the
 * file descriptor to the asynchronous writer, whose write failures the publisher logs away.
 * CREATOR exists for the AIDL-generated code that compiles against this class.
 */
public final class ParcelFileDescriptor implements AutoCloseable {
    public static final android.os.Parcelable.Creator<ParcelFileDescriptor> CREATOR =
            new android.os.Parcelable.Creator<ParcelFileDescriptor>() {
                @Override public ParcelFileDescriptor createFromParcel(Parcel in) {
                    return new ParcelFileDescriptor(new FileDescriptor());
                }

                @Override public ParcelFileDescriptor[] newArray(int size) {
                    return new ParcelFileDescriptor[size];
                }
            };

    private final FileDescriptor descriptor;

    private ParcelFileDescriptor(FileDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    public static ParcelFileDescriptor[] createPipe() throws IOException {
        FileDescriptor fileDescriptor = new FileDescriptor();
        return new ParcelFileDescriptor[] {
                new ParcelFileDescriptor(fileDescriptor),
                new ParcelFileDescriptor(fileDescriptor),
        };
    }

    public FileDescriptor getFileDescriptor() {
        return descriptor;
    }

    @Override public void close() throws IOException {
    }
}
