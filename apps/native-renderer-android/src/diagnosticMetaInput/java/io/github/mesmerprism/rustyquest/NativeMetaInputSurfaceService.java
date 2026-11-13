package io.github.mesmerprism.rustyquest;

import android.app.Service;
import android.content.Intent;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;

/** DUMP-protected in the opt-in diagnostic manifest; every Binder call also requires real Shell. */
public final class NativeMetaInputSurfaceService extends Service {
    private final NativeHeldReviewGate legacyGate = new NativeHeldReviewGate();
    private volatile boolean destroyed;
    private final Binder publisher = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (Binder.getCallingUid() != 2000) throw new SecurityException("actual Shell UID required");
            if ((flags & IBinder.FLAG_ONEWAY) != 0 || reply == null) {
                throw new IllegalArgumentException("synchronous publisher transaction required");
            }
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(NativeMetaInputContract.DESCRIPTOR); return true;
            }
            if (code == NativeHeldReviewContract.REQUEST_RETIRE || code == NativeHeldReviewContract.GET_REVIEW_STATUS) {
                if (!NativeHeldReviewGate.authorizeReviewCall(Binder.getCallingUid(),
                        checkCallingPermission(android.Manifest.permission.DUMP)==android.content.pm.PackageManager.PERMISSION_GRANTED,
                        (flags & IBinder.FLAG_ONEWAY)==0 && reply!=null)) {
                    throw new SecurityException("actual Shell plus per-call DUMP and synchronous reply required");
                }
                data.enforceInterface(NativeHeldReviewContract.DESCRIPTOR);
                if (destroyed || !NativeMetaInputDiagnostic.nativeHeldReviewEnabled()) return false;
                if (code == NativeHeldReviewContract.GET_REVIEW_STATUS) {
                    requireEnd(data); reply.writeNoException();
                    reply.writeString(NativeMetaInputDiagnostic.nativeHeldReviewStatus()); return true;
                }
                String request = data.readString(); requireEnd(data);
                long generation = NativeHeldReviewContract.validateOwnRequest(NativeMetaInputSurfaceService.this,request);
                boolean accepted = legacyGate.seal(generation,NativeMetaInputDiagnostic.nativeHeldReviewGeneration());
                if (accepted) {
                    NativeMetaInputDiagnostic.disarmForReview();
                    accepted=NativeMetaInputDiagnostic.nativeRequestHeldReview(request);
                }
                reply.writeNoException(); reply.writeInt(accepted ? 1 : 0); return true;
            }
            data.enforceInterface(NativeMetaInputContract.DESCRIPTOR);
            boolean admitted = legacyGate.enter();
            try {
            if (code == NativeMetaInputContract.GET_SNAPSHOT) {
                long expected = data.readLong();
                requireEnd(data);
                if (expected < 0) throw new IllegalArgumentException("generation must be nonnegative");
                NativeMetaInputContract.Snapshot snapshot = admitted ? snapshot(expected) : null;
                try {
                    reply.writeNoException(); reply.writeInt(snapshot == null ? 0 : 1);
                    if (snapshot != null) snapshot.writeToParcel(reply, 0);
                    return true;
                } finally { if (snapshot != null) snapshot.surface.release(); }
            }
            if (code == NativeMetaInputContract.SET_MODE) {
                long generation = data.readLong(); int mode = data.readInt(); long sequence = data.readLong();
                requireEnd(data);
                if (generation <= 0 || sequence <= 0 || !NativeMetaInputContract.isSupportedMode(mode)) {
                    throw new IllegalArgumentException("invalid generation/mode/sequence");
                }
                boolean accepted = admitted && !destroyed && NativeMetaInputDiagnostic.isArmed()
                        && NativeMetaInputDiagnostic.nativeSetMode(generation, mode, sequence);
                reply.writeNoException(); reply.writeInt(accepted ? 1 : 0); return true;
            }
            if (code == NativeMetaInputContract.GET_STATUS) {
                long generation = data.readLong(); requireEnd(data);
                if (generation <= 0) throw new IllegalArgumentException("exact positive generation required");
                NativeMetaInputContract.RenderStatus status = null;
                if (admitted && !destroyed && NativeMetaInputDiagnostic.isArmed()) {
                    long[] values = NativeMetaInputDiagnostic.nativeGetRenderStatus(generation);
                    if (values != null) status = new NativeMetaInputContract.RenderStatus(generation, values);
                }
                reply.writeNoException(); reply.writeInt(status == null ? 0 : 1);
                if (status != null) status.writeToParcel(reply, 0);
                return true;
            }
            return false;
            } finally { if (admitted) legacyGate.leave(); }
        }
    };

    private NativeMetaInputContract.Snapshot snapshot(long expected) {
        if (destroyed || !NativeMetaInputDiagnostic.isArmed()) return null;
        long generation = NativeMetaInputDiagnostic.nativeGetGeneration();
        if (generation <= 0 || (expected != 0 && expected != generation)) return null;
        long[] configured = NativeMetaInputDiagnostic.nativeGetDescriptor(generation);
        Surface surface = NativeMetaInputDiagnostic.nativeAcquireSurface(generation);
        if (surface == null) return null;
        try {
            if (destroyed || !NativeMetaInputDiagnostic.isArmed()) { surface.release(); return null; }
            return new NativeMetaInputContract.Snapshot(generation, configured, surface);
        } catch (RuntimeException error) { surface.release(); throw error; }
    }

    private static void requireEnd(Parcel data) {
        if (data.dataAvail() != 0) throw new BadParcelableException("unexpected publisher request fields");
    }

    @Override public IBinder onBind(Intent intent) {
        return !destroyed && NativeMetaInputDiagnostic.isArmed() ? publisher : null;
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        stopSelf(id); return START_NOT_STICKY; // No start-service command authority.
    }
    @Override public void onDestroy() { destroyed = true; super.onDestroy(); }
}
