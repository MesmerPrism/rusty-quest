package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.UUID;

/** App writer exclusion only. Neither acquiring nor releasing proves physical cleanup.
 * The fixed lock inode must never be replaced, renamed, or unlinked. A torn record
 * fails closed. No elapsed/reboot clock is restored from this record. */
final class EmbeddedDuplexProcessFence implements AutoCloseable {
    interface DirectorySync { void sync() throws Exception; }
    private final File directory;
    private boolean persistenceFailed;
    private final FileChannel channel;
    private final FileLock lock;
    private final DirectorySync directorySync;
    private final long generation;
    private final String nonce;
    private final boolean recoveryOnly;
    private boolean pending;
    private String checkpointDigest;
    private String evidenceDigest;

    static EmbeddedDuplexProcessFence acquire(File directory, String checkpoint,
            String evidence, DirectorySync sync) throws Exception {
        if (directory == null || sync == null || !directory.isDirectory()
                || Files.isSymbolicLink(directory.toPath())) {
            throw new IllegalStateException("process fence directory unavailable");
        }
        File file = new File(directory, "process-fence.v1.lock");
        File witness = new File(directory, "process-fence.v1.initialized");
        boolean witnessed = Files.exists(witness.toPath(), LinkOption.NOFOLLOW_LINKS);
        if (witnessed && (!Files.isRegularFile(witness.toPath(), LinkOption.NOFOLLOW_LINKS)
                || Files.size(witness.toPath()) > 128
                || !"rusty.quest.embedded_duplex.app_process_fence.v1\n".equals(
                        new String(Files.readAllBytes(witness.toPath()), StandardCharsets.US_ASCII)))) {
            throw new IllegalStateException("process fence initialization witness corrupt");
        }
        if (witnessed && !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("process fence state missing");
        }
        boolean created = false;
        try { Files.createFile(file.toPath()); created = true; }
        catch (java.nio.file.FileAlreadyExistsException exists) { /* Validate below. */ }
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file.toPath())) {
            throw new IllegalStateException("process fence path invalid");
        }
        FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException competing) {
                throw new IllegalStateException("embedded process writer already held", competing);
            }
            if (lock == null) throw new IllegalStateException("embedded process writer already held");
            long priorGeneration = 0;
            boolean pending = false;
            String checkpointHash = digest(checkpoint), evidenceHash = digest(evidence);
            if (created) {
                if (checkpoint != null || evidence != null) {
                    throw new IllegalStateException("process fence migration unsupported: retained journals");
                }
                byte[] marker = "rusty.quest.embedded_duplex.app_process_fence.v1\n".getBytes(StandardCharsets.US_ASCII);
                try (FileChannel initialized = FileChannel.open(witness.toPath(),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(marker);
                    while (buffer.hasRemaining()) initialized.write(buffer);
                    initialized.force(true);
                }
                sync.sync();
            } else {
                if (!witnessed) throw new IllegalStateException("process fence initialization witness missing");
                long size = channel.size();
                if (size <= 0 || size > 512) throw new IllegalStateException("process fence record missing or corrupt");
                ByteBuffer bytes = ByteBuffer.allocate((int) size);
                while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IllegalStateException("process fence truncated");
                String record = new String(bytes.array(), StandardCharsets.US_ASCII);
                String[] fields = record.split("\\n", -1);
                if (fields.length != 8 || !"rusty.quest.embedded_duplex.app_process_fence.v1".equals(fields[0])
                        || !fields[1].matches("[1-9][0-9]{0,18}")
                        || !fields[2].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !("pending".equals(fields[3]) || "clear".equals(fields[3]))
                        || !fields[4].matches("-|[0-9a-f]{64}") || !fields[5].matches("-|[0-9a-f]{64}")
                        || !fields[6].matches("[0-9a-f]{64}")
                        || !fields[6].equals(digest(String.join("\n", java.util.Arrays.copyOf(fields, 6)) + "\n"))
                        || !fields[7].isEmpty()) {
                    throw new IllegalStateException("process fence record corrupt");
                }
                priorGeneration = Long.parseLong(fields[1]);
                pending = "pending".equals(fields[3]);
                if (!fields[4].equals(checkpointHash) || !fields[5].equals(evidenceHash)) {
                    throw new IllegalStateException("process fence journal binding differs; migration unsupported");
                }
            }
            EmbeddedDuplexProcessFence result = new EmbeddedDuplexProcessFence(directory, channel, lock,
                    sync, Math.addExact(priorGeneration, 1), pending, checkpointHash, evidenceHash);
            result.persist();
            return result;
        } catch (Throwable failure) {
            if (lock != null) lock.release();
            channel.close();
            throw failure;
        }
    }

    private EmbeddedDuplexProcessFence(File directory, FileChannel channel, FileLock lock, DirectorySync sync,
            long generation, boolean pending, String checkpoint, String evidence) {
        this.directory = directory;
        this.channel = channel; this.lock = lock; this.directorySync = sync;
        this.generation = generation; this.nonce = UUID.randomUUID().toString();
        this.pending = pending; this.recoveryOnly = pending;
        this.checkpointDigest = checkpoint; this.evidenceDigest = evidence;
    }
    static final class CallbackGuard {
        private final EmbeddedDuplexProcessFence owner;
        private final long generation;
        private volatile boolean retired;
        private CallbackGuard(EmbeddedDuplexProcessFence owner) {
            this.owner = owner; this.generation = owner.generation;
        }
        void requireLive() {
            if (retired) throw new IllegalStateException("stale app callback");
            owner.requireLive(generation);
            owner.requireFresh();
        }
        void retire() { retired = true; }
    }
    CallbackGuard callbacks() {
        requireFresh();
        return new CallbackGuard(this);
    }
    long generation() { return generation; }
    synchronized boolean effectsPending() { return pending; }
    synchronized boolean recoveryOnly() { return recoveryOnly; }
    synchronized void requireLive(long expectedGeneration) {
        if (persistenceFailed || !lock.isValid() || !channel.isOpen() || expectedGeneration != generation) {
            throw new IllegalStateException("stale app process generation");
        }
    }
    synchronized void requireFresh() {
        requireLive(generation);
        if (recoveryOnly) throw new IllegalStateException("process recovery pending; cleanup handler unavailable");
    }
    synchronized void beforeRuntimeEffects(String checkpoint, String evidence) throws Exception {
        requireFresh();
        if (pending) throw new IllegalStateException("previous app effects require cleanup");
        pending = true;
        bind(checkpoint, evidence);
        persist();
    }
    /** Caller must first validate native no-media closure and Java/display barriers.
     * Never call based on an empty registry, force-stop, or lock acquisition. */
    synchronized void afterVerifiedNoMediaCleanup(String checkpoint, String evidence) throws Exception {
        requireFresh();
        bind(checkpoint, evidence);
        pending = false;
        persist();
    }
    private void bind(String checkpoint, String evidence) throws Exception {
        checkpointDigest = digest(checkpoint); evidenceDigest = digest(evidence);
    }
    /** Called only by the captured native owner; reads the already held channel.
     * Opening/closing another descriptor for Java's fcntl inode could release it. */
    public synchronized String nativeAdmissionRecord() throws Exception {
        requireFresh();
        if (!pending) throw new IllegalStateException("native admission requires durable pending marker");
        try {
            long size = channel.size();
            if (size <= 0 || size > 512) throw new IllegalStateException("native app record bounds");
            ByteBuffer bytes = ByteBuffer.allocate((int) size);
            channel.position(0);
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IllegalStateException("native app record truncated");
            String record = new String(bytes.array(), StandardCharsets.US_ASCII);
            if (!record.equals(serializedRecord())) throw new IllegalStateException("native app record changed");
            return record;
        } catch (Exception failure) { persistenceFailed = true; throw failure; }
    }
    public synchronized String nativeFenceDirectory() throws Exception {
        requireFresh();
        return directory.getCanonicalPath();
    }
    private String serializedRecord() throws Exception {
        String body = "rusty.quest.embedded_duplex.app_process_fence.v1\n" + generation + "\n"
                + nonce + "\n" + (pending ? "pending" : "clear") + "\n"
                + checkpointDigest + "\n" + evidenceDigest + "\n";
        return body + digest(body) + "\n";
    }
    private void persist() throws Exception {
        requireLive(generation);
        try {
        byte[] bytes = serializedRecord().getBytes(StandardCharsets.US_ASCII);
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.truncate(bytes.length);
        channel.force(true);
        directorySync.sync();
        } catch (Exception failure) { persistenceFailed = true; throw failure; }
    }
    static String digest(String exact) throws Exception {
        if (exact == null) return "-";
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(exact.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte value : hash) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    /** Release is resource management only; pending remains durable. */
    public synchronized void close() throws Exception {
        try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
    }
}
