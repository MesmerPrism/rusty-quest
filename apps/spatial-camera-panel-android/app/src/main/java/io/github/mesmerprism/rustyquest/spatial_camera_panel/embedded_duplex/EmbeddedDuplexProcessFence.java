package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import android.system.Os;
import android.system.OsConstants;
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
    private final FileDescriptor descriptor;
    private final FileOutputStream descriptorView;
    private final FileChannel channel;
    private final FileLock lock;
    private final DirectorySync directorySync;
    private final long generation;
    private final String nonce;
    private boolean recoveryOnly;
    private String predecessorRecord;
    private boolean pending;
    private String checkpointDigest;
    private String evidenceDigest;
    private EmbeddedDuplexProcessIncarnation.Identity incarnation;
    private EmbeddedDuplexProcessIncarnation.Binding incarnationBinding;

    static EmbeddedDuplexProcessFence acquire(File directory, String checkpoint,
            String evidence, DirectorySync sync) throws Exception {
        return acquire(directory,checkpoint,evidence,sync,null,null);
    }
    static EmbeddedDuplexProcessFence acquire(File directory, String checkpoint,
            String evidence, DirectorySync sync,EmbeddedDuplexProcessIncarnation.Identity incarnation,
            EmbeddedDuplexProcessIncarnation.Probe probe) throws Exception {
        if((incarnation==null)!=(probe==null))throw new IllegalArgumentException("process observation incomplete");
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
        // One no-follow, no-create descriptor for both the lock and all record I/O.
        // FileChannel data operations close this lifetime descriptor on interrupt.
        FileDescriptor descriptor = Os.open(file.getAbsolutePath(),
                OsConstants.O_RDWR | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        FileOutputStream descriptorView = null;
        FileLock lock = null;
        try {
            if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) {
                throw new IllegalStateException("process fence opened file is not regular");
            }
            descriptorView = new FileOutputStream(descriptor);
            FileChannel channel = descriptorView.getChannel();
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException competing) {
                throw new IllegalStateException("embedded process writer already held", competing);
            }
            if (lock == null) throw new IllegalStateException("embedded process writer already held");
            long priorGeneration = 0;
            boolean pending = false;
            String predecessorRecord = null;
            EmbeddedDuplexProcessIncarnation.Binding binding=null;
            boolean localTerminationCleared=false;
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
                long size = Os.fstat(descriptor).st_size;
                if (size <= 0 || size > 512) throw new IllegalStateException("process fence record missing or corrupt");
                String record = new String(readRecord(descriptor, (int) size, "process fence truncated"), StandardCharsets.US_ASCII);
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
                predecessorRecord = record;
                priorGeneration = Long.parseLong(fields[1]);
                if (EmbeddedDuplexAbandonment.restore(directory, record, checkpoint, evidence)) {
                    checkpointHash = "-"; evidenceHash = "-";
                }
                pending = "pending".equals(fields[3]);
                if (!fields[4].equals(checkpointHash) || !fields[5].equals(evidenceHash)) {
                    throw new IllegalStateException("process fence journal binding differs; migration unsupported");
                }
                if(pending && incarnation!=null){
                    binding=EmbeddedDuplexProcessIncarnation.load(directory,record);
                    if(binding!=null && EmbeddedDuplexProcessIncarnation.observeTermination(directory,binding,incarnation,probe,sync)
                            && !EmbeddedDuplexProcessIncarnation.remoteRecordsPresent(directory,checkpoint,evidence)){
                        pending=false;localTerminationCleared=true;
                    }
                }
            }
            EmbeddedDuplexProcessFence result = new EmbeddedDuplexProcessFence(directory, descriptor,
                    descriptorView, channel, lock,
                    sync, Math.addExact(priorGeneration, 1), pending, checkpointHash, evidenceHash);
            result.predecessorRecord = predecessorRecord;
            result.incarnation=incarnation;
            result.incarnationBinding=pending?binding:null;
            result.persist();
            if(localTerminationCleared)EmbeddedDuplexProcessIncarnation.commitClear(directory,binding,result.serializedRecord(),sync);
            return result;
        } catch (Throwable failure) {
            try { if (lock != null) lock.release(); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            try { closeDescriptor(descriptorView, descriptor); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private EmbeddedDuplexProcessFence(File directory, FileDescriptor descriptor,
            FileOutputStream descriptorView, FileChannel channel, FileLock lock, DirectorySync sync,
            long generation, boolean pending, String checkpoint, String evidence) {
        this.directory = directory;
        this.descriptor = descriptor; this.descriptorView = descriptorView;
        this.channel = channel; this.lock = lock; this.directorySync = sync;
        this.generation = generation; this.nonce = UUID.randomUUID().toString();
        this.pending = pending; this.recoveryOnly = pending;
        this.checkpointDigest = checkpoint; this.evidenceDigest = evidence;
    }
    static final class CallbackGuard {
        private final EmbeddedDuplexProcessFence owner;
        private final long generation;
        private final Object gate=new Object();
        private volatile boolean retired;
        private int entered;
        private final java.util.concurrent.CompletableFuture<Void> drained=new java.util.concurrent.CompletableFuture<>();
        private CallbackGuard(EmbeddedDuplexProcessFence owner){this.owner=owner;this.generation=owner.generation;}
        void requireLive(){
            if(retired)throw new IllegalStateException("stale app callback");
            owner.requireLive(generation);owner.requireFresh();
        }
        CallbackLease enter(){
            synchronized(gate){if(retired)throw new IllegalStateException("stale app callback");entered++;}
            CallbackLease lease=new CallbackLease(this);
            try { requireLive();return lease; }
            catch(RuntimeException | Error failure){lease.close();throw failure;}
        }
        java.util.concurrent.CompletableFuture<Void> retireAndDrain(){
            boolean done;
            synchronized(gate){retired=true;done=entered==0;}
            if(done)drained.complete(null);
            return drained;
        }
        void retire(){retireAndDrain();}
        private void leave(){
            boolean done;
            synchronized(gate){if(entered<=0)throw new IllegalStateException("callback accounting underflow");entered--;done=retired&&entered==0;}
            if(done)drained.complete(null);
        }
    }
    static final class CallbackLease implements AutoCloseable {
        private CallbackGuard guard;
        private CallbackLease(CallbackGuard guard){this.guard=guard;}
        @Override public void close(){CallbackGuard value=guard;if(value!=null){guard=null;value.leave();}}
    }
    CallbackGuard callbacks() {
        requireFresh();
        return new CallbackGuard(this);
    }
    long generation() { return generation; }
    String epochId() { requireLive(generation); return "app-process." + generation + "." + nonce; }
    synchronized boolean effectsPending() { return pending; }
    synchronized boolean recoveryOnly() { return recoveryOnly; }
    synchronized boolean priorOriginalScopeDisposed(String scope,String packageId)throws Exception{
        requireFresh();
        if(incarnation==null)return false;
        return EmbeddedDuplexOriginalScopeDisposition.disposed(directory,scope,serializedRecord(),generation,incarnation.uid,packageId);
    }
    synchronized void requireLive(long expectedGeneration) {
        if (persistenceFailed || !lock.isValid() || !channel.isOpen() || expectedGeneration != generation) {
            throw new IllegalStateException("stale app process generation");
        }
    }
    synchronized void requireFresh() {
        requireLive(generation);
        if (recoveryOnly) throw new IllegalStateException("process recovery pending; cleanup handler unavailable");
    }
    /** Actual app-owned closing entry, including failure before native claim; allocates no native capability. */
    synchronized void beforeNativeAbsenceRetirement(String checkpoint,String evidence) throws Exception {
        requireFresh();
        if(!pending){pending=true;bind(checkpoint,evidence);bindIncarnation();persist();}
        else requireLive(generation);
    }
    synchronized void beforeRuntimeEffects(String checkpoint, String evidence) throws Exception {
        requireFresh();
        if (pending) throw new IllegalStateException("previous app effects require cleanup");
        pending = true;
        bind(checkpoint, evidence);
        bindIncarnation();
        persist();
    }
    private void bindIncarnation() throws Exception {
        if(incarnation!=null)incarnationBinding=new EmbeddedDuplexProcessIncarnation.Binding(incarnation,serializedRecord());
    }
    /** Caller must first validate native no-media closure and Java/display barriers.
     * Never call based on an empty registry, force-stop, or lock acquisition. */
    synchronized void afterVerifiedNoMediaCleanup(String checkpoint, String evidence) throws Exception {
        requireFresh();
        bind(checkpoint, evidence);
        pending = false;
        persist();
    }
    /** Called after the process owner joined authoritative native effects, physical owners and control closure. */
    synchronized void afterVerifiedWholeProductCleanup(String checkpoint, String evidence, String joinedProofSha256) throws Exception {
        requireFresh();
        if (joinedProofSha256 == null || !joinedProofSha256.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("whole-product cleanup proof absent");
        bind(checkpoint, evidence); pending = false; persist();
    }
    private void bind(String checkpoint, String evidence) throws Exception {
        checkpointDigest = digest(checkpoint); evidenceDigest = digest(evidence);
    }
    synchronized String retainedRecord() throws Exception { requireLive(generation); return serializedRecord(); }
    synchronized String recoveryStatus() throws Exception {
        requireLive(generation);
        return new org.json.JSONObject().put("$schema","rusty.quest.embedded_duplex.recovery_status.v1")
                .put("recovery_only",recoveryOnly).put("generation",generation)
                .put("predecessor_sha256",digest(predecessorRecord)).put("current_fence_sha256",digest(serializedRecord()))
                .put("checkpoint_sha256",checkpointDigest).put("evidence_sha256",evidenceDigest).toString();
    }
    /** Explicit shell-authorized nonaccepting disposition; never a cleanup receipt. */
    synchronized String abandon(String checkpoint,String evidence,EmbeddedDuplexAbandonmentRequest request,EmbeddedDuplexAbandonment.Protection protection) throws Exception {
        requireLive(generation);
        org.json.JSONObject authorization=request.json();
        if (!digest(serializedRecord()).equals(authorization.getString("expected_current_fence_sha256"))
                || !recoveryOnly || !pending || predecessorRecord == null
                || !checkpointDigest.equals(digest(checkpoint)) || !evidenceDigest.equals(digest(evidence)))
            throw new IllegalStateException("retained abandonment scope unavailable");
        request.bind(predecessorRecord,serializedRecord(),checkpoint,evidence);
        org.json.JSONObject receipt = EmbeddedDuplexAbandonment.prepare(directory,predecessorRecord,
                serializedRecord(),checkpoint,evidence,generation,authorization,directorySync,protection);
        // Archive and disposition are flushed first. A torn fence remains poisoned.
        checkpointDigest="-"; evidenceDigest="-"; pending=false;
        persist();
        EmbeddedDuplexAbandonment.committed(directory,receipt,serializedRecord(),protection);
        directorySync.sync();
        recoveryOnly=false;
        return receipt.toString();
    }
    /** Called only by the captured native owner; reads the already held descriptor.
     * Opening/closing another descriptor for Java's fcntl inode could release it. */
    public synchronized String nativeAdmissionRecord() throws Exception {
        requireFresh();
        if (!pending) throw new IllegalStateException("native admission requires durable pending marker");
        try {
            long size = Os.fstat(descriptor).st_size;
            if (size <= 0 || size > 512) throw new IllegalStateException("native app record bounds");
            String record = new String(readRecord(descriptor, (int) size, "native app record truncated"), StandardCharsets.US_ASCII);
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
    private static byte[] readRecord(FileDescriptor descriptor, int size, String truncated) throws Exception {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = Os.pread(descriptor, bytes, offset, size - offset, offset);
            if (count <= 0) throw new IllegalStateException(truncated);
            offset += count;
        }
        return bytes;
    }
    private static void closeDescriptor(FileOutputStream view, FileDescriptor descriptor) throws Exception {
        // Android's public FileOutputStream(fd) borrows fd. The fence owns it.
        try { if (view != null) view.close(); }
        catch (Throwable failure) {
            try { if (descriptor.valid()) Os.close(descriptor); }
            catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            throw new IllegalStateException("process fence descriptor close failed", failure);
        }
        if (descriptor.valid()) Os.close(descriptor);
    }
    private void persist() throws Exception {
        requireLive(generation);
        try {
        byte[] bytes = serializedRecord().getBytes(StandardCharsets.US_ASCII);
        int offset = 0;
        while (offset < bytes.length) {
            int count = Os.pwrite(descriptor, bytes, offset, bytes.length - offset, offset);
            if (count <= 0) throw new IllegalStateException("process fence write incomplete");
            offset += count;
        }
        Os.ftruncate(descriptor, bytes.length);
        Os.fsync(descriptor);
        directorySync.sync();
        // The primary remains write-ahead. No caller can enter an effect until
        // the exact companion also commits; a crash in between stays unknown.
        if(pending && incarnationBinding!=null)
            EmbeddedDuplexProcessIncarnation.persist(directory,incarnationBinding,serializedRecord(),directorySync);
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
        try { if (lock.isValid()) lock.release(); } finally { closeDescriptor(descriptorView, descriptor); }
    }
}
