package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.os.Bundle;
import android.net.Uri;
import java.security.MessageDigest;
import java.util.Arrays;

/** Exact existing signature-scoped status call. It grants neither controller nor provider authority. */
final class HubReadiness {
    static final String PACKAGE="io.github.mesmerprism.rustymanifold.broker";
    static final String AUTHORITY=PACKAGE+".connection-hub-wearer-control";
    static final String PERMISSION="io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION";
    private final Context context;
    HubReadiness(Context context) {this.context=context.getApplicationContext();}
    void requireCurrent() throws Exception {
        PackageManager pm=context.getPackageManager();
        ProviderInfo provider=pm.resolveContentProvider(AUTHORITY,0);
        if(provider==null || !PACKAGE.equals(provider.packageName) || !(PACKAGE+".ConnectionHubWearerControlProvider").equals(provider.name) || !PERMISSION.equals(provider.readPermission) || !PERMISSION.equals(provider.writePermission) || context.checkSelfPermission(PERMISSION)!=PackageManager.PERMISSION_GRANTED) throw new SecurityException("exact Hub status provider unavailable");
        byte[] hub=singleSigner(pm.getPackageInfo(PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES));
        byte[] caller=singleSigner(pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES));
        if(!MessageDigest.isEqual(hub,caller)) throw new SecurityException("Hub signature permission unavailable");
        Bundle result=context.getContentResolver().call(Uri.parse("content://"+AUTHORITY),"status",null,null);
        if(result==null || !"rusty.quest.connection_hub.wearer_control_snapshot.v1".equals(result.getString("schema")) || !"status".equals(result.getString("action")) || !result.getBoolean("status_available",false) || !result.getBoolean("listener_enabled",false) || !"running".equals(result.getString("desired_connection_state")) || result.getBoolean("secrets_in_snapshot",true) || result.getBoolean("caller_selected_authority",true)) throw new SecurityException("wearer-started Hub listener unavailable");
    }
    private static byte[] singleSigner(PackageInfo info) throws Exception {
        if(info.signingInfo==null || info.signingInfo.hasMultipleSigners()) throw new SecurityException("single Hub signer required");
        Signature[] signatures=info.signingInfo.getApkContentsSigners();
        if(signatures==null || signatures.length!=1) throw new SecurityException("single Hub signer required");
        return MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
    }
}
