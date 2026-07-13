package io.github.mesmerprism.rustyquest.peer_rendezvous;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

final class PeerAuthorityIdentity {
    static final String ACTION_GENERATE =
            "io.github.mesmerprism.rustyquest.peer_rendezvous.AUTHORITY_GENERATE";
    static final String ACTION_SIGN =
            "io.github.mesmerprism.rustyquest.peer_rendezvous.AUTHORITY_SIGN";

    private static final String KEY_FILE = "peer-authority-ed25519.pkcs8";
    private static final String IDENTITY_FILE = "peer-authority-identity.json";
    private static final String SIGNATURE_FILE = "peer-authority-signature.json";

    private PeerAuthorityIdentity() {
    }

    static String generate(Context context, Intent intent) throws Exception {
        String runId = safeToken(intent.getStringExtra("run_id"), "run");
        String peerId = safeToken(intent.getStringExtra("peer_id"), "peer");
        String serial = safeToken(intent.getStringExtra("serial"), "serial");
        String algorithm = availableEd25519Name("KeyPairGenerator");
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        KeyPair pair = generator.generateKeyPair();
        writeBytes(new File(context.getFilesDir(), KEY_FILE), pair.getPrivate().getEncoded());
        byte[] publicKey = pair.getPublic().getEncoded();
        byte[] publicRaw = rawEd25519PublicKey(publicKey);
        String keyId = "key." + peerId + "." + runId;
        String publicBase64 = Base64.encodeToString(publicRaw, Base64.NO_WRAP);
        String publicSha256 = "sha256:" + hex(sha256(publicRaw));
        String receipt = "{"
                + "\"schema\":\"rusty.quest.peer_authority_identity.v1\","
                + "\"generation\":\"on-device\","
                + "\"run_id\":\"" + json(runId) + "\","
                + "\"serial\":\"" + json(serial) + "\","
                + "\"peer_id\":\"" + json(peerId) + "\","
                + "\"key_id\":\"" + json(keyId) + "\","
                + "\"algorithm\":\"Ed25519\","
                + "\"android_provider_algorithm\":\"" + json(algorithm) + "\","
                + "\"public_key_ed25519_base64\":\"" + json(publicBase64) + "\","
                + "\"public_key_sha256\":\"" + json(publicSha256) + "\","
                + "\"private_key_exported_to_host\":false"
                + "}";
        writeText(new File(context.getFilesDir(), IDENTITY_FILE), receipt);
        return IDENTITY_FILE;
    }

    static String sign(Context context, Intent intent) throws Exception {
        String runId = safeToken(intent.getStringExtra("run_id"), "run");
        String peerId = safeToken(intent.getStringExtra("peer_id"), "peer");
        String peerSerial = safeToken(intent.getStringExtra("peer_serial"), "peer_serial");
        String contextBase64 = intent.getStringExtra("context_base64");
        if (contextBase64 == null || contextBase64.length() == 0) {
            throw new IllegalArgumentException("context_base64 is required");
        }
        byte[] contextBytes = Base64.decode(contextBase64, Base64.DEFAULT);
        String algorithm = availableEd25519Name("KeyFactory");
        PrivateKey privateKey = KeyFactory.getInstance(algorithm)
                .generatePrivate(new PKCS8EncodedKeySpec(readBytes(new File(context.getFilesDir(), KEY_FILE))));
        String signatureAlgorithm = availableEd25519Name("Signature");
        Signature signature = Signature.getInstance(signatureAlgorithm);
        signature.initSign(privateKey);
        signature.update(contextBytes);
        String signatureBase64 = Base64.encodeToString(signature.sign(), Base64.NO_WRAP);
        String receipt = "{"
                + "\"schema\":\"rusty.quest.peer_authority_signature.v1\","
                + "\"run_id\":\"" + json(runId) + "\","
                + "\"peer_id\":\"" + json(peerId) + "\","
                + "\"peer_serial\":\"" + json(peerSerial) + "\","
                + "\"algorithm\":\"Ed25519\","
                + "\"android_key_factory_algorithm\":\"" + json(algorithm) + "\","
                + "\"android_signature_algorithm\":\"" + json(signatureAlgorithm) + "\","
                + "\"context_sha256\":\"sha256:" + hex(sha256(contextBytes)) + "\","
                + "\"signature_base64\":\"" + json(signatureBase64) + "\""
                + "}";
        writeText(new File(context.getFilesDir(), SIGNATURE_FILE), receipt);
        return SIGNATURE_FILE;
    }

    private static String safeToken(String value, String fallback) {
        if (value == null || value.length() == 0) {
            return fallback;
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String availableEd25519Name(String service) throws Exception {
        String[] names = new String[] {"Ed25519", "EdDSA", "1.3.101.112"};
        Exception last = null;
        for (String name : names) {
            try {
                if ("KeyPairGenerator".equals(service)) {
                    KeyPairGenerator.getInstance(name);
                } else if ("KeyFactory".equals(service)) {
                    KeyFactory.getInstance(name);
                } else if ("Signature".equals(service)) {
                    Signature.getInstance(name);
                } else {
                    throw new IllegalArgumentException("unknown service " + service);
                }
                return name;
            } catch (Exception error) {
                last = error;
            }
        }
        throw last == null ? new IllegalStateException("Ed25519 provider unavailable") : last;
    }

    private static byte[] rawEd25519PublicKey(byte[] encoded) {
        if (encoded.length < 32) {
            throw new IllegalArgumentException("encoded Ed25519 public key too short");
        }
        byte[] raw = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);
        return raw;
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static String hex(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] out = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            int b = value[i] & 0xff;
            out[i * 2] = alphabet[b >>> 4];
            out[i * 2 + 1] = alphabet[b & 0x0f];
        }
        return new String(out);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] readBytes(File file) throws Exception {
        FileInputStream stream = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = stream.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset != data.length) {
                throw new IllegalStateException("short read");
            }
            return data;
        } finally {
            stream.close();
        }
    }

    private static void writeBytes(File file, byte[] value) throws Exception {
        FileOutputStream stream = new FileOutputStream(file, false);
        try {
            stream.write(value);
        } finally {
            stream.close();
        }
    }

    private static void writeText(File file, String value) throws Exception {
        writeBytes(file, value.getBytes(StandardCharsets.UTF_8));
    }
}
