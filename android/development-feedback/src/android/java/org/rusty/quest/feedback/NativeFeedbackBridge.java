package org.rusty.quest.feedback;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Source-only, opt-in resident CPU example; constructing it loads trusted code.
 * The application must separately adopt the bridge/artifact/feature closure.
 * Not a sandbox, signature verifier, package builder, device transport or DEX host.
 */
public final class NativeFeedbackBridge {
    public static final int ATTEMPT_LIMIT = 64;
    public static final long IMAGE_LIMIT = 4L * 1024 * 1024;
    public static final long STAGED_LIMIT = 64L * 1024 * 1024;
    private final long owner; // Private opaque process-lifetime owner, never a status field.
    private final Path incoming;
    private final Path staged;
    private int attempts;
    private long stagedBytes;

    /** Caller supplies an inspected actual bridge file and a fresh app-private session.
     * Packaging/extraction and application lifetime are deliberately outside this class.
     * There is no destroy/unload/finalizer/cleaner; retain the facade until process exit.
     */
    public NativeFeedbackBridge(File bridge, byte[] bridgeSha256, File privateSession) throws IOException {
        requireDigest(bridgeSha256);
        Path library = regular(bridge.toPath());
        if (Files.size(library) > STAGED_LIMIT || !MessageDigest.isEqual(hash(library), bridgeSha256)) {
            throw new IOException("Native bridge SHA256/size mismatch");
        }
        Path root = privateSession.toPath().toAbsolutePath().normalize();
        Files.createDirectories(root);
        physical(root);
        incoming = root.resolve("native-incoming");
        staged = root.resolve("native-generations");
        Files.createDirectories(incoming);
        Files.createDirectories(staged);
        physical(incoming);
        physical(staged);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(staged)) {
            if (children.iterator().hasNext()) throw new IOException("Native staging session must be fresh and empty");
        }
        System.load(library.toString());
        owner = nativeCreate();
        if (owner == 0) throw new IllegalStateException("Native owner creation failed");
    }

    /** Copies the derived incoming/<positive generation>.so create-new and readonly.
     * Attempts and declared bytes remain charged after any failure; disk is retained.
     * 64 attempts / 4MiB per image / 64MiB total are finite example policy, not product policy.
     */
    public synchronized Snapshot activate(long generation, byte[] sha256, long scratchExpected) throws IOException {
        if (attempts == ATTEMPT_LIMIT) throw new IllegalStateException("Native example attempt budget exhausted");
        attempts++;
        requireDigest(sha256);
        if (generation <= 0) throw new IllegalArgumentException("Native generation must be positive");
        Path source = regular(incoming.resolve(Long.toString(generation) + ".so"));
        if (Files.isWritable(source)) throw new IOException("Native incoming artifact must be readonly");
        long size = Files.size(source);
        if (size < 1 || size > IMAGE_LIMIT || size > STAGED_LIMIT - stagedBytes) {
            throw new IllegalStateException("Native example staged-byte budget exhausted");
        }
        stagedBytes += size;
        physical(staged);
        Path destination = staged.resolve(Long.toString(generation) + ".so");
        MessageDigest digest = sha256();
        long copied = 0;
        // The open descriptor remains writable after readonly is applied to the new file.
        // A failed copy is retained, charged, and never overwritten or retried in place.
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (!destination.toFile().setReadOnly()) throw new IOException("Cannot make native staged artifact readonly");
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) {
                copied += count;
                if (copied > size) throw new IOException("Native incoming size changed");
                output.write(bytes, 0, count);
                digest.update(bytes, 0, count);
            }
        }
        regular(destination);
        if (copied != size || Files.isWritable(destination)
                || !MessageDigest.isEqual(digest.digest(), sha256)
                || !MessageDigest.isEqual(hash(destination), sha256)) {
            throw new IOException("Native staged artifact SHA256/size/readonly mismatch");
        }
        byte[] path = destination.toString().getBytes(StandardCharsets.UTF_8);
        if (path.length == 0 || path.length > 4096) throw new IOException("Native staged path length");
        check(nativeActivate(owner, path, sha256, generation, scratchExpected));
        return observe();
    }

    public synchronized Snapshot compute(long input) {
        check(nativeCompute(owner, input));
        return observe();
    }
    public synchronized Snapshot snapshot() { return observe(); }
    private Snapshot observe() {
        long[] values = new long[5];
        byte[] bytes = new byte[8];
        check(nativeSnapshot(owner, values, bytes));
        return new Snapshot(values, bytes);
    }
    private static void check(int code) {
        if (code != 0) throw new IllegalStateException("Native feedback refused (code " + code + ")");
    }
    private static void requireDigest(byte[] digest) {
        if (digest == null || digest.length != 32) throw new IllegalArgumentException("Expected exact SHA256 bytes");
    }
    private static Path regular(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        physical(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected regular native artifact");
        return path;
    }
    private static void physical(Path path) throws IOException {
        if (!path.equals(path.toFile().getCanonicalFile().toPath())) throw new IOException("Native path alias");
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Native path symlink");
        }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static byte[] hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return digest.digest();
    }

    public static final class Snapshot {
        public final long completed, value, generation, retainedImages, allocationToken;
        private final byte[] payload;
        private Snapshot(long[] values, byte[] bytes) {
            completed = values[0]; value = values[1]; generation = values[2];
            retainedImages = values[3]; allocationToken = values[4]; payload = bytes.clone();
        }
        public String completedUnsigned() { return Long.toUnsignedString(completed); }
        public byte[] payload() { return payload.clone(); }
    }
    private static native long nativeCreate();
    private static native int nativeActivate(long owner, byte[] path, byte[] sha256, long generation, long expected);
    private static native int nativeCompute(long owner, long input);
    private static native int nativeSnapshot(long owner, long[] values, byte[] bytes);
}
