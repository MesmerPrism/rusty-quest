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
    /** Base manifest/route fields above retain installed identity; effective data has separate lineage. */
    public final String effectiveInputLineageJson;

    private EmbeddedDuplexEnrollmentRequest(String roleId, String packageId,
            String signerSha256, String manifestSha256, String routeSha256,
            String localKeyId, String localPublicKeyHex, String localDeviceId,
            String localPeerId, String remoteDeviceId, String remotePeerId, String effectiveInputLineageJson) {
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
        this.effectiveInputLineageJson = effectiveInputLineageJson;
    }

    /** This factory checks installed APK bytes and signer before exposing any route facts. */
    public static EmbeddedDuplexEnrollmentRequest localSnapshot(Context context, String roleId)
            throws Exception {
        return localSnapshot(context, roleId, false);
    }

    static EmbeddedDuplexEnrollmentRequest existingSnapshot(Context context, String roleId)
            throws Exception {
        return localSnapshot(context, roleId, true);
    }

    private static EmbeddedDuplexEnrollmentRequest localSnapshot(Context context, String roleId,
            boolean existingOnly) throws Exception {
        if (context == null || context.getApplicationContext() == null) {
            throw new IllegalArgumentException("application context required");
        }
        Context app = context.getApplicationContext();
        EmbeddedDuplexPackagedInputs.InstalledRole role =
                EmbeddedDuplexPackagedInputs.InstalledRole.parse(roleId);
        EmbeddedDuplexPackagedInputs inputs = EmbeddedDuplexPackagedInputs.load(app,
                BuildConfig.EMBEDDED_DUPLEX_PRODUCT_MANIFEST_SHA256, role);
        return localSnapshot(app, roleId, existingOnly, FrozenEffectiveInputs.packaged(inputs));
    }

    static EmbeddedDuplexEnrollmentRequest localSnapshot(Context context, String roleId,
            FrozenEffectiveInputs inputs) throws Exception {
        return localSnapshot(context, roleId, false, inputs);
    }

    static EmbeddedDuplexEnrollmentRequest existingSnapshot(Context context, String roleId,
            FrozenEffectiveInputs inputs) throws Exception {
        return localSnapshot(context, roleId, true, inputs);
    }

    private static EmbeddedDuplexEnrollmentRequest localSnapshot(Context context, String roleId,
            boolean existingOnly, FrozenEffectiveInputs inputs) throws Exception {
        if (context == null || context.getApplicationContext() == null || inputs == null)
            throw new IllegalArgumentException("application context and frozen inputs required");
        inputs.requireConsumable();
        Context app = context.getApplicationContext();
        EmbeddedDuplexPackagedInputs.InstalledRole role = EmbeddedDuplexPackagedInputs.InstalledRole.parse(roleId);
        if (role != inputs.role()) throw new IllegalArgumentException("frozen installed role differs");
        JSONObject manifest = new JSONObject(inputs.verifiedBase.manifestJson());
        JSONObject packagedIdentity = manifest.getJSONObject("package");
        if (!app.getPackageName().equals(packagedIdentity.getString("application_id")))
            throw new IllegalArgumentException("frozen application package differs");
        EmbeddedDuplexIdentity.Identity identity = existingOnly
                ? EmbeddedDuplexIdentity.loadExisting(app) : EmbeddedDuplexIdentity.loadOrCreate(app);
        return projectPublicFacts(inputs, identity.keyId(), identity.rawPublicKey());
    }

    /** Public enrollment facts only; the Context path above exclusively loads the actual identity. */
    static EmbeddedDuplexEnrollmentRequest projectPublicFacts(FrozenEffectiveInputs inputs,
            String keyId, byte[] publicKey) throws Exception {
        if (inputs == null || keyId == null || keyId.isEmpty() || publicKey == null || publicKey.length != 32)
            throw new IllegalArgumentException("public enrollment facts required");
        inputs.requireConsumable();
        JSONObject packagedIdentity = new JSONObject(inputs.verifiedBase.manifestJson()).getJSONObject("package");
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
        return new EmbeddedDuplexEnrollmentRequest(inputs.role().id,
                packagedIdentity.getString("application_id"),
                packagedIdentity.getString("signing_certificate_sha256"),
                FrozenEffectiveInputs.packaged(inputs.verifiedBase).digest("product-input-manifest.json"),
                inputs.verifiedBase.digest("route-configuration.json"), keyId,
                hex(publicKey), local.getString("device_id"),
                local.getString("peer_id"), remote.getString("device_id"),
                remote.getString("peer_id"), inputs.lineageJson);
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
                && remotePeerId.equals(other.remotePeerId)
                && effectiveInputLineageJson.equals(other.effectiveInputLineageJson);
    }
}
