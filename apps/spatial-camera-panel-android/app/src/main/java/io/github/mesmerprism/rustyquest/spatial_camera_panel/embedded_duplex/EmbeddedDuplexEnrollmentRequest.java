package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;
import org.json.JSONArray;
import org.json.JSONObject;

/** Verified, immutable public facts supplied to the optional private enrollment capsule. */
public final class EmbeddedDuplexEnrollmentRequest {
    public final String roleId;
    public final String packageId;
    public final String signerSha256;
    public final String manifestSha256;
    public final String routeSha256;
    public final String localKeyId;
    public final String localPublicKeyHex;
    public final String localDeviceId;
    public final String localPeerId;
    public final String remoteDeviceId;
    public final String remotePeerId;

    private EmbeddedDuplexEnrollmentRequest(String roleId, String packageId,
            String signerSha256, String manifestSha256, String routeSha256,
            String localKeyId, String localPublicKeyHex, String localDeviceId,
            String localPeerId, String remoteDeviceId, String remotePeerId) {
        this.roleId = roleId;
        this.packageId = packageId;
        this.signerSha256 = signerSha256;
        this.manifestSha256 = manifestSha256;
        this.routeSha256 = routeSha256;
        this.localKeyId = localKeyId;
        this.localPublicKeyHex = localPublicKeyHex;
        this.localDeviceId = localDeviceId;
        this.localPeerId = localPeerId;
        this.remoteDeviceId = remoteDeviceId;
        this.remotePeerId = remotePeerId;
    }

    /** This factory checks installed APK bytes and signer before exposing any route facts. */
    public static EmbeddedDuplexEnrollmentRequest localSnapshot(Context context, String roleId)
            throws Exception {
        if (context == null || context.getApplicationContext() == null) {
            throw new IllegalArgumentException("application context required");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexPackagedInputs.InstalledRole role =
                EmbeddedDuplexPackagedInputs.InstalledRole.parse(roleId);
        EmbeddedDuplexPackagedInputs inputs = EmbeddedDuplexPackagedInputs.load(app,
                BuildConfig.EMBEDDED_DUPLEX_PRODUCT_MANIFEST_SHA256, role);
        JSONObject manifest = new JSONObject(inputs.manifestJson());
        JSONObject packagedIdentity = manifest.getJSONObject("package");
        JSONArray peers = new JSONObject(inputs.json("route-configuration.json")).getJSONArray("peers");
        if (peers.length() != 2) throw new IllegalStateException("packaged route peer count");
        JSONObject local = null, remote = null;
        for (int i = 0; i < peers.length(); i++) {
            JSONObject peer = peers.getJSONObject(i);
            if (inputs.selectedRoleId().equals(peer.getString("installed_role_id"))) local = peer;
            else remote = peer;
        }
        if (local == null || remote == null) {
            throw new IllegalStateException("installed role has no exact route peers");
        }
        EmbeddedDuplexIdentity.Identity identity = EmbeddedDuplexIdentity.loadOrCreate(app);
        return new EmbeddedDuplexEnrollmentRequest(role.id,
                packagedIdentity.getString("application_id"),
                packagedIdentity.getString("signing_certificate_sha256"),
                BuildConfig.EMBEDDED_DUPLEX_PRODUCT_MANIFEST_SHA256,
                inputs.digest("route-configuration.json"), identity.keyId(),
                hex(identity.rawPublicKey()), local.getString("device_id"),
                local.getString("peer_id"), remote.getString("device_id"),
                remote.getString("peer_id"));
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(Character.forDigit((item >>> 4) & 15, 16));
            value.append(Character.forDigit(item & 15, 16));
        }
        return value.toString();
    }

    boolean sameFacts(EmbeddedDuplexEnrollmentRequest other) {
        return other != null && roleId.equals(other.roleId)
                && packageId.equals(other.packageId) && signerSha256.equals(other.signerSha256)
                && manifestSha256.equals(other.manifestSha256)
                && routeSha256.equals(other.routeSha256) && localKeyId.equals(other.localKeyId)
                && localPublicKeyHex.equals(other.localPublicKeyHex)
                && localDeviceId.equals(other.localDeviceId) && localPeerId.equals(other.localPeerId)
                && remoteDeviceId.equals(other.remoteDeviceId)
                && remotePeerId.equals(other.remotePeerId);
    }
}
