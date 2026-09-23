package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** Durable app-private identity for the embedded-duplex authority. */
final class EmbeddedDuplexIdentity {
    private static final int RECORD_MAGIC = 0x45444931; // EDI1
    private static final int RECORD_VERSION = 1;
    private static final int MAX_RECORD_BYTES = 1024;
    private static final int MAX_PRIVATE_KEY_BYTES = 256;
    private static final byte[] X509_ED25519_PREFIX = new byte[] {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
    };
    private static final byte[] PAIRING_DOMAIN =
            "rusty.quest.embedded_duplex.authority.v1\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMMON_LAN_CONTEXT_DOMAIN =
            "rusty.manifold.peer.common_lan_reciprocal_ed25519_context.v1\u0000"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OWNER_DISPATCH_REQUEST_DOMAIN =
            "rusty.quest.android.media.owner_dispatch_envelope.v1\u0000request\u0000"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OWNER_DISPATCH_RESPONSE_DOMAIN =
            "rusty.quest.android.media.owner_dispatch_envelope.v1\u0000terminal_response\u0000"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PRODUCT_ACTIVATION_DOMAIN =
            "rusty.quest.android.media.owner_dispatch_envelope.v1\u0000product_activation\u0000"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PRODUCT_ACTIVATION_ACK_DOMAIN =
            "rusty.quest.android.media.owner_dispatch_envelope.v1\u0000product_activation_ack\u0000"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PAIRING_PROBE =
            "identity-pairing-check".getBytes(StandardCharsets.US_ASCII);
    // Longest RQOD1 signing domain: 128 KiB frame - 6-byte magic + 76-byte domain.
    private static final int MAX_AUTHORITY_BYTES = 131142;
    private static final String DIRECTORY_NAME = "embedded-duplex-identity";
    private static final String RECORD_NAME = "ed25519-identity.v1";

    private EmbeddedDuplexIdentity() {}

    static Identity loadOrCreate(Context context) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        File noBackup = context.getNoBackupFilesDir();
        if (noBackup == null || !noBackup.isDirectory()) {
            throw new IllegalStateException("no-backup directory is unavailable");
        }
        File directory = new File(noBackup, DIRECTORY_NAME);
        ensurePrivateDirectory(directory);
        File record = new File(directory, RECORD_NAME);
        rejectSymbolicLink(record);
        if (record.exists()) {
            return load(record);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                availableEd25519Name("KeyPairGenerator"));
        KeyPair pair = generator.generateKeyPair();
        byte[] encoded = encodeRecord(pair);
        File temporary = new File(directory, RECORD_NAME + ".pending-" + randomSuffix());
        rejectSymbolicLink(temporary);
        try {
            writeNewPrivateFile(temporary, encoded);
            try {
                // link(2) publishes the complete fsynced bytes without replacing an existing identity.
                Os.link(temporary.getAbsolutePath(), record.getAbsolutePath());
            } catch (ErrnoException raced) {
                if (raced.errno != OsConstants.EEXIST) {
                    throw raced;
                }
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("failed to remove identity staging file");
            }
        }
        return load(record);
    }

    static byte[] signExactAuthorityBytes(Identity identity, byte[] signingBytes) throws Exception {
        if (identity == null) {
            throw new IllegalArgumentException("identity is required");
        }
        if (signingBytes == null || signingBytes.length == 0
                || signingBytes.length > MAX_AUTHORITY_BYTES
                || !hasSupportedAuthorityDomain(signingBytes)) {
            throw new IllegalArgumentException("unsupported or out-of-bounds authority signing bytes");
        }
        return sign(identity, signingBytes);
    }

    /** Called only after the native host validates the live enrolled Common-LAN context. */
    static byte[] signValidatedCommonLanBytes(Identity identity, byte[] signingBytes) throws Exception {
        if (identity == null || signingBytes == null || signingBytes.length == 0
                || signingBytes.length > MAX_AUTHORITY_BYTES
                || !startsWith(signingBytes, COMMON_LAN_CONTEXT_DOMAIN)) {
            throw new IllegalArgumentException("unvalidated Common-LAN signing bytes");
        }
        return sign(identity, signingBytes);
    }

    private static byte[] sign(Identity identity, byte[] signingBytes) throws Exception {
        Signature signer = Signature.getInstance(availableEd25519Name("Signature"));
        signer.initSign(identity.privateKey);
        signer.update(signingBytes);
        byte[] result = signer.sign();
        if (result.length != 64) {
            throw new IllegalStateException("Ed25519 provider returned a noncanonical signature length");
        }
        return result;
    }

    static final class Identity {
        private final PrivateKey privateKey;
        private final byte[] rawPublicKey;
        private final String publicKeySha256;
        private final String keyId;

        private Identity(PrivateKey privateKey, byte[] rawPublicKey) throws Exception {
            this.privateKey = privateKey;
            this.rawPublicKey = rawPublicKey.clone();
            this.publicKeySha256 = hex(sha256(rawPublicKey));
            this.keyId = "ed25519." + publicKeySha256;
        }

        String keyId() {
            return keyId;
        }

        String publicKeySha256() {
            return publicKeySha256;
        }

        byte[] rawPublicKey() {
            return rawPublicKey.clone();
        }

        String rawPublicKeyBase64() {
            return Base64.encodeToString(rawPublicKey, Base64.NO_WRAP);
        }
    }

    private static Identity load(File record) throws Exception {
        rejectSymbolicLink(record);
        if (!Files.readAttributes(record.toPath(), java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
            throw new IllegalStateException("identity record is not a regular file");
        }
        int mode = Os.lstat(record.getAbsolutePath()).st_mode;
        if ((mode & (OsConstants.S_IRWXG | OsConstants.S_IRWXO)) != 0) {
            throw new IllegalStateException("identity record is accessible outside its owner");
        }
        long length = record.length();
        if (length <= 0 || length > MAX_RECORD_BYTES) {
            throw new IllegalStateException("identity record length is invalid");
        }
        byte[] encoded = Files.readAllBytes(record.toPath());
        if (encoded.length != length) {
            throw new IllegalStateException("identity record changed while being read");
        }
        byte[] privateEncoded = null;
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != RECORD_MAGIC || input.readInt() != RECORD_VERSION) {
                throw new IllegalStateException("identity record header is invalid");
            }
            int privateLength = input.readInt();
            int publicLength = input.readInt();
            if (privateLength <= 0 || privateLength > MAX_PRIVATE_KEY_BYTES
                    || publicLength != X509_ED25519_PREFIX.length + 32
                    || privateLength + publicLength + 16 != encoded.length) {
                throw new IllegalStateException("identity record key lengths are invalid");
            }
            privateEncoded = new byte[privateLength];
            byte[] publicEncoded = new byte[publicLength];
            input.readFully(privateEncoded);
            input.readFully(publicEncoded);
            if (input.read() != -1) {
                throw new IllegalStateException("identity record has trailing bytes");
            }
            KeyFactory factory = KeyFactory.getInstance(availableEd25519Name("KeyFactory"));
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateEncoded));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicEncoded));
            byte[] rawPublicKey = strictRawEd25519PublicKey(publicKey.getEncoded());
            verifyPair(privateKey, publicKey);
            return new Identity(privateKey, rawPublicKey);
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (privateEncoded != null) {
                Arrays.fill(privateEncoded, (byte) 0);
            }
        }
    }

    private static byte[] encodeRecord(KeyPair pair) throws Exception {
        byte[] privateEncoded = pair.getPrivate().getEncoded();
        byte[] publicEncoded = pair.getPublic().getEncoded();
        strictRawEd25519PublicKey(publicEncoded);
        if (privateEncoded == null || privateEncoded.length == 0
                || privateEncoded.length > MAX_PRIVATE_KEY_BYTES) {
            throw new IllegalStateException("Ed25519 provider returned an invalid private key encoding");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(RECORD_MAGIC);
        output.writeInt(RECORD_VERSION);
        output.writeInt(privateEncoded.length);
        output.writeInt(publicEncoded.length);
        output.write(privateEncoded);
        output.write(publicEncoded);
        output.flush();
        byte[] record = bytes.toByteArray();
        Arrays.fill(privateEncoded, (byte) 0);
        if (record.length > MAX_RECORD_BYTES) {
            throw new IllegalStateException("identity record exceeds its bound");
        }
        return record;
    }

    private static void verifyPair(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        Signature signer = Signature.getInstance(availableEd25519Name("Signature"));
        signer.initSign(privateKey);
        signer.update(PAIRING_DOMAIN);
        signer.update(PAIRING_PROBE);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance(availableEd25519Name("Signature"));
        verifier.initVerify(publicKey);
        verifier.update(PAIRING_DOMAIN);
        verifier.update(PAIRING_PROBE);
        if (!verifier.verify(signature)) {
            throw new IllegalStateException("identity public/private key pairing is invalid");
        }
    }

    private static byte[] strictRawEd25519PublicKey(byte[] encoded) {
        if (encoded == null || encoded.length != X509_ED25519_PREFIX.length + 32) {
            throw new IllegalArgumentException("Ed25519 X.509 encoding length is invalid");
        }
        for (int i = 0; i < X509_ED25519_PREFIX.length; i++) {
            if (encoded[i] != X509_ED25519_PREFIX[i]) {
                throw new IllegalArgumentException("Ed25519 X.509 encoding is noncanonical");
            }
        }
        return Arrays.copyOfRange(encoded, X509_ED25519_PREFIX.length, encoded.length);
    }

    static boolean hasSupportedAuthorityDomain(byte[] value) {
        return startsWith(value, OWNER_DISPATCH_REQUEST_DOMAIN)
                || startsWith(value, OWNER_DISPATCH_RESPONSE_DOMAIN)
                || startsWith(value, PRODUCT_ACTIVATION_DOMAIN)
                || startsWith(value, PRODUCT_ACTIVATION_ACK_DOMAIN);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length <= prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
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
                    throw new IllegalArgumentException("unknown Ed25519 service");
                }
                return name;
            } catch (Exception unavailable) {
                last = unavailable;
            }
        }
        throw last == null ? new IllegalStateException("Ed25519 provider unavailable") : last;
    }

    private static void ensurePrivateDirectory(File directory) throws Exception {
        rejectSymbolicLink(directory);
        if (!directory.exists()) {
            try {
                Files.createDirectory(directory.toPath());
                Os.chmod(directory.getAbsolutePath(), 0700);
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // Validate the winner below; never replace it.
            }
        }
        rejectSymbolicLink(directory);
        if (!directory.isDirectory()) {
            throw new IllegalStateException("identity path is not a directory");
        }
        int mode = Os.lstat(directory.getAbsolutePath()).st_mode;
        if ((mode & (OsConstants.S_IRWXG | OsConstants.S_IRWXO)) != 0) {
            throw new IllegalStateException("identity directory is accessible outside its owner");
        }
    }

    private static void writeNewPrivateFile(File file, byte[] value) throws Exception {
        if (!file.createNewFile()) {
            throw new IllegalStateException("identity staging path already exists");
        }
        Os.chmod(file.getAbsolutePath(), 0600);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void rejectSymbolicLink(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath())) {
            throw new IllegalStateException("symbolic links are forbidden in identity storage");
        }
    }

    private static String randomSuffix() throws Exception {
        byte[] value = new byte[16];
        new java.security.SecureRandom().nextBytes(value);
        return hex(value);
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static String hex(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            int current = value[i] & 0xff;
            result[i * 2] = alphabet[current >>> 4];
            result[i * 2 + 1] = alphabet[current & 0x0f];
        }
        return new String(result);
    }
}
