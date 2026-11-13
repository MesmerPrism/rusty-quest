package io.github.mesmerprism.rustyquest;

import android.os.BadParcelableException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.view.Surface;

/** Closed, diagnostic-only app-owned producer transport. No platform-private parcel ABI. */
public final class NativeMetaInputContract {
    public static final String DESCRIPTOR = "io.github.mesmerprism.rustyquest.NativeMetaInputPublisher.v2";
    public static final int VERSION = 2;
    public static final int GET_SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION;
    public static final int SET_MODE = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int GET_STATUS = IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int MODE_RAW = 0;
    public static final int MODE_COLORAMA = 1;
    public static final int MODE_FULL_CORE3 = 2;

    static boolean isSupportedMode(int mode) {
        return mode == MODE_RAW || mode == MODE_COLORAMA || mode == MODE_FULL_CORE3;
    }
    static boolean isClosedInputDimensions(long width, long height) {
        return (width == 1280 && (height == 672 || height == 1280))
                || (width == 2560 && height == 1280);
    }

    private NativeMetaInputContract() {}

    public interface Publisher {
        Snapshot getSnapshot(long expectedGeneration) throws RemoteException;
        boolean setMode(long generation, int mode, long sequence) throws RemoteException;
        RenderStatus getRenderStatus(long generation) throws RemoteException;
    }

    public static Publisher asInterface(final IBinder binder) {
        if (binder == null) throw new IllegalArgumentException("publisher Binder missing");
        return new Publisher() {
            @Override public Snapshot getSnapshot(long expectedGeneration) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeLong(expectedGeneration);
                    if (!binder.transact(GET_SNAPSHOT, data, reply, 0)) {
                        throw new RemoteException("publisher snapshot transaction rejected");
                    }
                    reply.readException();
                    return reply.readInt() == 0 ? null : Snapshot.CREATOR.createFromParcel(reply);
                } finally { reply.recycle(); data.recycle(); }
            }
            @Override public boolean setMode(long generation, int mode, long sequence) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeLong(generation);
                    data.writeInt(mode);
                    data.writeLong(sequence);
                    if (!binder.transact(SET_MODE, data, reply, 0)) {
                        throw new RemoteException("publisher mode transaction rejected");
                    }
                    reply.readException();
                    return reply.readInt() != 0;
                } finally { reply.recycle(); data.recycle(); }
            }
            @Override public RenderStatus getRenderStatus(long generation) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR); data.writeLong(generation);
                    if (!binder.transact(GET_STATUS, data, reply, 0)) {
                        throw new RemoteException("publisher render-status transaction rejected");
                    }
                    reply.readException();
                    return reply.readInt() == 0 ? null : RenderStatus.CREATOR.createFromParcel(reply);
                } finally { reply.recycle(); data.recycle(); }
            }
        };
    }

    /** Own Surface reference; the receiver must release it. Fields describe reader configuration. */
    public static final class Snapshot implements Parcelable {
        public final long generation;
        public final int width, height, imageFormat, maxImages;
        public final long usage;
        public final Surface surface;

        Snapshot(long generation, long[] configured, Surface surface) {
            if (generation <= 0 || configured == null || configured.length != 5
                    || !isClosedInputDimensions(configured[0], configured[1])
                    || configured[0] <= 0 || configured[0] > Integer.MAX_VALUE
                    || configured[1] <= 0 || configured[1] > Integer.MAX_VALUE
                    || configured[2] <= 0 || configured[2] > Integer.MAX_VALUE
                    || configured[4] < 2 || configured[4] > Integer.MAX_VALUE
                    || surface == null || !surface.isValid()) {
                throw new IllegalArgumentException("invalid own reader snapshot");
            }
            this.generation = generation;
            width = (int) configured[0]; height = (int) configured[1];
            imageFormat = (int) configured[2]; usage = configured[3];
            maxImages = (int) configured[4]; this.surface = surface;
        }

        @Override public int describeContents() { return surface.describeContents(); }
        @Override public void writeToParcel(Parcel out, int flags) {
            out.writeInt(VERSION); out.writeLong(generation);
            out.writeLongArray(new long[] { width, height, imageFormat, usage, maxImages });
            // Keep our local wrapper alive through the synchronous reply; caller owns its copy.
            surface.writeToParcel(out, 0);
        }
        public static final Creator<Snapshot> CREATOR = new Creator<Snapshot>() {
            @Override public Snapshot createFromParcel(Parcel in) {
                if (in.readInt() != VERSION) throw new BadParcelableException("snapshot version");
                long generation = in.readLong();
                long[] configured = in.createLongArray();
                Surface surface = Surface.CREATOR.createFromParcel(in);
                try { return new Snapshot(generation, configured, surface); }
                catch (RuntimeException error) { surface.release(); throw error; }
            }
            @Override public Snapshot[] newArray(int count) { return new Snapshot[count]; }
        };
    }

    /** Native fence-completed status, separate from accepted mode requests. */
    public static final class RenderStatus implements Parcelable {
        public final long generation, acceptedSequence, acceptedMode, completedSequence, completedMode;
        public final long renderFrame, inputFrame, inputTimestampNs, witnessMask;
        public final boolean completedValid;
        RenderStatus(long expected, long[] values) {
            if (expected <= 0 || values == null || values.length != 10 || values[0] != expected
                    || values[1] < 0 || values[2] < 0 || values[2] > MODE_FULL_CORE3 || values[3] < 0
                    || values[3] > values[1] || values[4] < 0 || values[4] > MODE_FULL_CORE3
                    || values[8] < 0 || values[8] > 7 || values[9] < 0 || values[9] > 1
                    || (values[9] == 1 && (values[5] < 0 || values[6] <= 0 || values[7] <= 0))) {
                throw new IllegalArgumentException("invalid native render-status snapshot");
            }
            generation = values[0]; acceptedSequence = values[1]; acceptedMode = values[2];
            completedSequence = values[3]; completedMode = values[4]; renderFrame = values[5];
            inputFrame = values[6]; inputTimestampNs = values[7]; witnessMask = values[8];
            completedValid = values[9] == 1;
        }
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel out, int flags) {
            out.writeInt(VERSION); out.writeLong(generation);
            out.writeLongArray(new long[] { generation, acceptedSequence, acceptedMode, completedSequence,
                    completedMode, renderFrame, inputFrame, inputTimestampNs, witnessMask,
                    completedValid ? 1 : 0 });
        }
        public static final Creator<RenderStatus> CREATOR = new Creator<RenderStatus>() {
            @Override public RenderStatus createFromParcel(Parcel in) {
                if (in.readInt() != VERSION) throw new BadParcelableException("render-status version");
                return new RenderStatus(in.readLong(), in.createLongArray());
            }
            @Override public RenderStatus[] newArray(int count) { return new RenderStatus[count]; }
        };
    }
}
