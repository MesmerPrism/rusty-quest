// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import io.github.mesmerprism.rustyquest.packedinput.PackedInputReader;
import io.github.mesmerprism.rustyquest.packedinput.PackedSnapshotV2Shape;

/** Narrow compatibility Binder for the existing app-owned v2 helper, not a vendor capture interface. */
public final class PackedInputPublisherV2 extends Binder {
    private final Context context;private final PackedInputReader reader;
    public PackedInputPublisherV2(Context context,PackedInputReader reader) {
        if(context==null||reader==null)throw new IllegalArgumentException("actual context/reader required");
        this.context=context;this.reader=reader;
    }
    private static void end(Parcel data) { if(data.dataAvail()!=0)throw new BadParcelableException("unexpected v2 request fields"); }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
        if(Binder.getCallingUid()!=2000||context.checkCallingPermission(Manifest.permission.DUMP)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("actual Shell UID and per-call DUMP required");
        if((flags&IBinder.FLAG_ONEWAY)!=0||reply==null)throw new IllegalArgumentException("synchronous v2 reply required");
        if(code==IBinder.INTERFACE_TRANSACTION) { reply.writeString(PackedSnapshotV2Shape.DESCRIPTOR);return true; }
        data.enforceInterface(PackedSnapshotV2Shape.DESCRIPTOR);
        if(code==IBinder.FIRST_CALL_TRANSACTION) {
            long generation=data.readLong();end(data);PackedSnapshotV2Shape.requireExpected(generation,reader.descriptor().generation);
            Surface wrapper=reader.ownedSurface(generation);
            try {
                reply.writeNoException();reply.writeInt(1);reply.writeInt(PackedSnapshotV2Shape.VERSION);reply.writeLong(generation);
                reply.writeLongArray(PackedSnapshotV2Shape.configured(reader.descriptor()));wrapper.writeToParcel(reply,0);return true;
            } catch(RuntimeException error) { reader.invalidatePublication();throw error; }
            finally { wrapper.release(); }
        }
        if(code==IBinder.FIRST_CALL_TRANSACTION+2) {
            long generation=data.readLong();end(data);PackedSnapshotV2Shape.requireExpected(generation,reader.descriptor().generation);
            // Acquisition and source-to-pool copy completion are not final renderer completion.
            reply.writeNoException();reply.writeInt(PackedSnapshotV2Shape.PROVIDES_FINAL_RENDER_STATUS?1:0);
            return true;
        }
        return false; // No mode/effect/generic command authority.
    }
}
