package io.github.mesmerprism.rustyquest.packageupdater;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class PackageUpdaterCanonicalVectorTest {
    private static final String EXPECTED_JCS =
            "{\"artifact\":{\"apk_sha256\":\"sha256:1111111111111111111111111111111111111111111111111111111111111111\",\"apk_size_bytes\":123456,\"apk_url\":\"https://updates.mesmerprism.com/rusty-kiosk/alpha/rusty-kiosk-0.1.1.apk\",\"package_name\":\"io.github.mesmerprism.rustykiosk\",\"signer_sha256\":\"sha256:23bb7bb81143a81f216118af35960aaee2468e9880b94e07574cac0a9239dcf6\",\"version_code\":101,\"version_name\":\"0.1.1\"},\"expires_at_ms\":2000000500000,\"issued_at_ms\":1999999900000,\"manifest_id\":\"rusty-kiosk.alpha.101\",\"rollout_ring\":\"alpha\",\"schema\":\"rusty.quest.package_update_manifest.v1\",\"sequence\":41}";
    private static final String PUBLIC_KEY =
            "6kpsY-KcUgq-9VB7Ey7F-ZVHdq6-vnuSQh7qaRRG0iw";
    private static final String SIGNATURE =
            "qUmvnTwfXQWt3ANq-dRR1JAst5LSm7PLYkYbniZc9W_6x38hL9uA26ao8h5iasMpp9B_Q_idhjcBzflg1sTxDA";
    private static final byte[] ED25519_X509_PREFIX = new byte[] {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    public static void main(String[] arguments) throws Exception {
        UpdateArtifact artifact = new UpdateArtifact(
                "io.github.mesmerprism.rustykiosk",
                101L,
                "0.1.1",
                URI.create(
                        "https://updates.mesmerprism.com/rusty-kiosk/alpha/"
                                + "rusty-kiosk-0.1.1.apk"),
                123456L,
                "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                "sha256:23bb7bb81143a81f216118af35960aaee2468e9880b94e07574cac0a9239dcf6");
        String canonical = UpdateManifestCanonicalizer.canonicalSignedManifest(
                "rusty-kiosk.alpha.101",
                41L,
                1999999900000L,
                2000000500000L,
                "alpha",
                artifact);
        if (!EXPECTED_JCS.equals(canonical)) {
            throw new AssertionError("Java JCS projection differs from Rust fixed vector");
        }

        byte[] rawKey = Base64.getUrlDecoder().decode(PUBLIC_KEY);
        byte[] encodedKey = new byte[ED25519_X509_PREFIX.length + rawKey.length];
        System.arraycopy(
                ED25519_X509_PREFIX, 0, encodedKey, 0, ED25519_X509_PREFIX.length);
        System.arraycopy(
                rawKey, 0, encodedKey, ED25519_X509_PREFIX.length, rawKey.length);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(
                KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(encodedKey)));
        verifier.update(UpdateManifestCanonicalizer.signatureDomain());
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(Base64.getUrlDecoder().decode(SIGNATURE))) {
            throw new AssertionError("Rust fixed-vector signature did not verify");
        }
        System.out.println("Package Updater Java/Rust canonical vector passed");
    }
}
