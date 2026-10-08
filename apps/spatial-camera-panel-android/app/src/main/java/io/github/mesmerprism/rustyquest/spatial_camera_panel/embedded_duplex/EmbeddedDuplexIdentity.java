package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** Durable app-private identity for the embedded-duplex authority. */
final class EmbeddedDuplexIdentity {
    private static final int RECORD_MAGIC = 0x45444931; // EDI1
    private static final int LEGACY_RECORD_VERSION = 1;
    private static final int RECORD_VERSION = 2;
    private static final int SEED_BYTES = 32;
    private static final int MAX_RECORD_BYTES = 1024;
    private static final int MAX_PRIVATE_KEY_BYTES = 256;
    private static final byte[] X509_ED25519_PREFIX = new byte[] {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
    };
    private static final byte[] PKCS8_ED25519_SEED_PREFIX = new byte[] {
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
            0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    };
    private static final byte[] PAIRING_DOMAIN =
            "rusty.quest.embedded_duplex.authority.v1\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMMON_LAN_CONTEXT_DOMAIN =
            "rusty.manifold.peer.common_lan_reciprocal_ed25519_context.v1\u0000"
                    .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PAIR_CEREMONY_DOMAIN =
            "rusty.quest.embedded_duplex.pair_ceremony.v1\u0000"
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
    private static final String PUBLICATION_LOCK_NAME = "ed25519-identity.publish.lock";
    private static final Object PUBLICATION_MONITOR = new Object();

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

        byte[] seed = new byte[SEED_BYTES];
        byte[] encoded;
        try {
            new SecureRandom().nextBytes(seed);
            byte[] rawPublicKey = EmbeddedDuplexNative.ed25519PublicFromSeed(seed);
            if (rawPublicKey == null || rawPublicKey.length != 32) {
                throw new IllegalStateException("native Ed25519 public derivation failed");
            }
            encoded = encodeSeedRecord(seed, rawPublicKey);
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
        File temporary = new File(directory, RECORD_NAME + ".pending-" + randomSuffix());
        rejectSymbolicLink(temporary);
        try {
            writeNewPrivateFile(temporary, encoded);
            try (FileOutputStream lock = openPrivatePublicationLock(directory)) {
                publishCompleteRecord(lock.getChannel(), temporary, record,
                        () -> syncPrivateDirectory(directory));
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("failed to remove identity staging file");
            }
        }
        return load(record);
    }

    interface DirectorySync { void sync() throws Exception; }

    /** The OS lock coordinates app processes; the monitor avoids overlapping locks in one VM. */
    static boolean publishCompleteRecord(FileChannel lockChannel, File temporary, File record,
            DirectorySync directorySync) throws Exception {
        synchronized (PUBLICATION_MONITOR) {
            try (FileLock ignored = lockChannel.lock()) {
                rejectSymbolicLink(record);
                if (record.exists()) {
                    return false;
                }
                // Both paths are in the same private directory. A crash sees either no record or
                // the complete fsynced staging file; the lock prevents a second app publisher.
                Files.move(temporary.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE);
                directorySync.sync();
                return true;
            }
        }
    }

    private static FileOutputStream openPrivatePublicationLock(File directory) throws Exception {
        File lock = new File(directory, PUBLICATION_LOCK_NAME);
        FileDescriptor descriptor = Os.open(lock.getAbsolutePath(),
                OsConstants.O_CREAT | OsConstants.O_RDWR | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,
                0600);
        try {
            int mode = Os.fstat(descriptor).st_mode;
            if ((mode & OsConstants.S_IFMT) != OsConstants.S_IFREG ||
                    (mode & (OsConstants.S_IRWXG | OsConstants.S_IRWXO)) != 0) {
                throw new IllegalStateException("identity publication lock is not private and regular");
            }
            return new FileOutputStream(descriptor);
        } catch (Exception failure) {
            Os.close(descriptor);
            throw failure;
        }
    }

    private static void syncPrivateDirectory(File directory) throws Exception {
        FileDescriptor descriptor = Os.open(directory.getAbsolutePath(),
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        try {
            if ((Os.fstat(descriptor).st_mode & OsConstants.S_IFMT) != OsConstants.S_IFDIR) {
                throw new IllegalStateException("identity directory changed during publication");
            }
            Os.fsync(descriptor);
        } finally {
            Os.close(descriptor);
        }
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

    /** Only the app-owned native pair coordinator supplies this closed domain. */
    static byte[] signPairCeremonyBytes(Identity identity, byte[] signingBytes) throws Exception {
        if (identity == null || signingBytes == null || signingBytes.length == 0
                || signingBytes.length > MAX_AUTHORITY_BYTES
                || !startsWith(signingBytes, PAIR_CEREMONY_DOMAIN)) {
            throw new IllegalArgumentException("invalid pair ceremony signing bytes");
        }
        return sign(identity, signingBytes);
    }

    private static byte[] sign(Identity identity, byte[] signingBytes) throws Exception {
        if (identity.seed != null) {
            byte[] result = EmbeddedDuplexNative.ed25519SignAuthorityBytes(identity.seed, signingBytes);
            if (result == null || result.length != 64) {
                throw new IllegalStateException("native Ed25519 authority signing failed");
            }
            return result;
        }
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
        private final byte[] seed;
        private final byte[] rawPublicKey;
        private final String publicKeySha256;
        private final String keyId;

        private Identity(PrivateKey privateKey, byte[] seed, byte[] rawPublicKey) throws Exception {
            if ((privateKey == null) == (seed == null)) {
                throw new IllegalArgumentException("exactly one Ed25519 private-key representation is required");
            }
            this.privateKey = privateKey;
            this.seed = seed == null ? null : seed.clone();
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
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != RECORD_MAGIC ||
                    (version != LEGACY_RECORD_VERSION && version != RECORD_VERSION)) {
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
            byte[] rawPublicKey = strictRawEd25519PublicKey(publicEncoded);
            if (version == RECORD_VERSION) {
                if (privateLength != SEED_BYTES ||
                        !Arrays.equals(rawPublicKey, EmbeddedDuplexNative.ed25519PublicFromSeed(privateEncoded))) {
                    throw new IllegalStateException("durable Ed25519 seed/public pairing is invalid");
                }
                return new Identity(null, privateEncoded, rawPublicKey);
            }
            byte[] legacySeed = strictLegacySeed(privateEncoded);
            if (legacySeed != null) {
                try {
                    if (!Arrays.equals(rawPublicKey, EmbeddedDuplexNative.ed25519PublicFromSeed(legacySeed))) {
                        throw new IllegalStateException("legacy Ed25519 seed/public pairing is invalid");
                    }
                    return new Identity(null, legacySeed, rawPublicKey);
                } finally {
                    Arrays.fill(legacySeed, (byte) 0);
                }
            }
            KeyFactory factory = KeyFactory.getInstance(availableEd25519Name("KeyFactory"));
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateEncoded));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicEncoded));
            verifyPair(privateKey, publicKey);
            return new Identity(privateKey, null, rawPublicKey);
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (privateEncoded != null) {
                Arrays.fill(privateEncoded, (byte) 0);
            }
        }
    }

    static byte[] encodeSeedRecord(byte[] seed, byte[] rawPublicKey) throws Exception {
        if (seed == null || seed.length != SEED_BYTES || rawPublicKey == null || rawPublicKey.length != 32) {
            throw new IllegalArgumentException("native Ed25519 identity encoding is invalid");
        }
        byte[] publicEncoded = new byte[X509_ED25519_PREFIX.length + rawPublicKey.length];
        System.arraycopy(X509_ED25519_PREFIX, 0, publicEncoded, 0, X509_ED25519_PREFIX.length);
        System.arraycopy(rawPublicKey, 0, publicEncoded, X509_ED25519_PREFIX.length, rawPublicKey.length);
        byte[] record = ByteBuffer.allocate(16 + seed.length + publicEncoded.length)
                .putInt(RECORD_MAGIC).putInt(RECORD_VERSION)
                .putInt(seed.length).putInt(publicEncoded.length)
                .put(seed).put(publicEncoded).array();
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

    static byte[] strictLegacySeed(byte[] encoded) {
        if (encoded == null || encoded.length != PKCS8_ED25519_SEED_PREFIX.length + SEED_BYTES) {
            return null;
        }
        for (int i = 0; i < PKCS8_ED25519_SEED_PREFIX.length; i++) {
            if (encoded[i] != PKCS8_ED25519_SEED_PREFIX[i]) {
                return null;
            }
        }
        return Arrays.copyOfRange(encoded, PKCS8_ED25519_SEED_PREFIX.length, encoded.length);
    }

    static boolean hasSupportedAuthorityDomain(byte[] value) {
        return startsWith(value, "rusty.quest.android.media.retained_cleanup_dispatch.v2\0request\0".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                || startsWith(value, "rusty.quest.android.media.retained_cleanup_dispatch.v2\0response\0".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                || startsWith(value, "rusty.quest.android.media.retained_cleanup_prepare.v1\0".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                || startsWith(value, "rusty.quest.android.media.retained_abort_prepare.v2\0".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                || startsWith(value, "rusty.quest.android.media.retained_cleanup_prepare.v3\0".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                || startsWith(value, "rusty.quest.android.media.retained_abort_prepare.v4\0".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                || startsWith(value, OWNER_DISPATCH_REQUEST_DOMAIN)
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
                if ("KeyFactory".equals(service)) {
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
