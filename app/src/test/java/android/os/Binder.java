package android.os;

/**
 * Unit-test local Binder replacement. The real attachInterface, queryLocalInterface, and
 * asBinder throw "Stub!", which makes the generated AIDL bridge Stub unconstructable inside the
 * JVM, so a successful bind can never deliver its connection. This shadow supports only the
 * local-interface pair the publisher needs: wiring an owner with attachInterface and finding it
 * back through queryLocalInterface, which is how ISpicyLyricBridge.Stub.asInterface resolves a
 * same-process binder. Transacted remote calls stay refused.
 */
public class Binder implements IBinder {
    private IInterface owner;
    private String descriptor;

    public void attachInterface(IInterface localInterface, String descriptor) {
        this.owner = localInterface;
        this.descriptor = descriptor;
    }

    public IInterface queryLocalInterface(String descriptor) {
        return descriptor != null && descriptor.equals(this.descriptor) ? owner : null;
    }

    public IBinder asBinder() {
        return this;
    }

    public String getInterfaceDescriptor() {
        return descriptor;
    }

    public boolean isBinderAlive() {
        return true;
    }

    public boolean pingBinder() {
        return true;
    }

    public void dump(java.io.FileDescriptor fd, String[] args) {
        throw new RuntimeException("Stub!");
    }

    public void dumpAsync(java.io.FileDescriptor fd, String[] args) {
        throw new RuntimeException("Stub!");
    }

    public boolean transact(int code, Parcel data, Parcel reply, int flags) {
        throw new RuntimeException("Stub!");
    }

    public void linkToDeath(IBinder.DeathRecipient recipient, int flags) {
        throw new RuntimeException("Stub!");
    }

    public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
        return false;
    }
}
