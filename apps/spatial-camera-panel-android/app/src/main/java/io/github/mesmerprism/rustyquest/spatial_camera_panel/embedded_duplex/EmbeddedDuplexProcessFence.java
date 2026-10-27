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
    private long generation;
    private String nonce;
    private boolean recoveryOnly;
    private Object coldRecoveryOwner;
    private java.util.concurrent.Callable<String> coldRecoveryEvidence;
    private String coldRecoveryPendingRecord;
    private boolean pending;
    private String checkpointDigest;
    private String evidenceDigest;
    private String acquisitionRecord;
    private String persistedRecord;
    private EmbeddedDuplexProcessIncarnation.Binding incarnation;
    private File incarnationDirectory;
    private String operationalRunId;
    private String handoffRunId;
    private java.util.concurrent.Callable<org.json.JSONObject> handoffSource;
    private boolean newlyInitialized;
    private long acquisitionGeneration;

    static EmbeddedDuplexProcessFence acquire(File directory, String checkpoint,
            String evidence, DirectorySync sync) throws Exception {
        return acquire(directory,checkpoint,evidence,sync,null,directory);
    }
    static EmbeddedDuplexProcessFence acquire(File directory,String checkpoint,String evidence,
            DirectorySync sync,String runId,File incarnationDirectory)throws Exception {
        if(runId!=null && !runId.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("fence run id");
        if(incarnationDirectory==null || !Files.isDirectory(incarnationDirectory.toPath(),LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(incarnationDirectory.toPath()))throw new IllegalStateException("run incarnation directory");
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
            String priorRecord = null;
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
                priorRecord = record;
                String[] fields = record.split("\\n", -1);
                boolean v2="rusty.quest.embedded_duplex.app_process_fence.v2".equals(fields[0]);
                int checksum=v2?7:6;
                if (fields.length != (v2?9:8) || !(v2 || "rusty.quest.embedded_duplex.app_process_fence.v1".equals(fields[0]))
                        || !fields[1].matches("[1-9][0-9]{0,18}")
                        || !fields[2].matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !("pending".equals(fields[3]) || "clear".equals(fields[3]))
                        || !fields[4].matches("-|[0-9a-f]{64}") || !fields[5].matches("-|[0-9a-f]{64}")
                        || v2 && !fields[6].matches("[0-9a-f]{32}")
                        || !fields[checksum].matches("[0-9a-f]{64}")
                        || !fields[checksum].equals(digest(String.join("\n", java.util.Arrays.copyOf(fields, checksum)) + "\n"))
                        || !fields[checksum+1].isEmpty()) {
                    throw new IllegalStateException("process fence record corrupt");
                }
                if(!java.util.Objects.equals(runId,v2?fields[6]:null))throw new IllegalStateException("fence/selected operational run differs");
                priorGeneration = Long.parseLong(fields[1]);
                pending = "pending".equals(fields[3]);
                if (!fields[4].equals(checkpointHash) || !fields[5].equals(evidenceHash)) {
                    throw new IllegalStateException("process fence journal binding differs; migration unsupported");
                }
            }
            EmbeddedDuplexProcessFence result = new EmbeddedDuplexProcessFence(directory, channel, lock,
                    sync, Math.addExact(priorGeneration, 1), pending, checkpointHash, evidenceHash);
            result.newlyInitialized=created;result.acquisitionGeneration=priorGeneration;
            result.operationalRunId=runId;result.incarnationDirectory=incarnationDirectory;
            result.acquisitionRecord = priorRecord;
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
    /** Explicit command-lane opt-in only. A held legacy fence may acquire its first
     * process stamp, but binding never increments generation or clears pending.
     * A witnessed companion must match its owner and the exact prior/current phase;
     * process death or a different birth never authorizes replacing that binding. */
    synchronized void bindIncarnation(EmbeddedDuplexProcessIncarnation.Identity identity) throws Exception {
        requireLive(generation);
        if (identity == null) throw new IllegalArgumentException("process incarnation absent");
        verifyHeldRecord();
        if (incarnation != null) {
            verifyIncarnation(persistedRecord);
            if (!incarnation.owner.binding().equals(identity.binding()))
                throw new IllegalStateException("process incarnation owner differs");
            return;
        }
        EmbeddedDuplexProcessIncarnation.Binding binding;
        if (EmbeddedDuplexProcessIncarnation.present(incarnationDirectory)) {
            binding = EmbeddedDuplexProcessIncarnation.load(incarnationDirectory, acquisitionRecord);
            if (binding == null || !binding.owner.binding().equals(identity.binding()))
                throw new IllegalStateException("process incarnation companion differs or unavailable");
        } else binding = new EmbeddedDuplexProcessIncarnation.Binding(identity, persistedRecord);
        try {
            EmbeddedDuplexProcessIncarnation.persist(incarnationDirectory, binding, persistedRecord, directorySync);
            incarnation = binding;
        } catch (Exception failure) { persistenceFailed = true; throw failure; }
    }
    /** Verify an already bound owner without stamping or replacing any custody. */
    synchronized void requireIncarnation(EmbeddedDuplexProcessIncarnation.Identity identity) {
        requireLive(generation);
        if (incarnation == null || identity == null || !incarnation.owner.binding().equals(identity.binding()))
            throw new IllegalStateException("process incarnation owner unavailable");
    }
    private void verifyIncarnation(String record) {
        EmbeddedDuplexProcessIncarnation.Binding observed = EmbeddedDuplexProcessIncarnation.load(incarnationDirectory, record);
        if (observed == null || !observed.owner.binding().equals(incarnation.owner.binding())
                || !observed.originalRecord.equals(incarnation.originalRecord))
            throw new IllegalStateException("process incarnation companion changed");
    }
    private void verifyHeldRecord() throws Exception {
        try {
            long size = channel.size();
            if (size <= 0 || size > 512) throw new IllegalStateException("held app record bounds");
            ByteBuffer bytes = ByteBuffer.allocate((int) size);
            channel.position(0);
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IllegalStateException("held app record truncated");
            if (!persistedRecord.equals(new String(bytes.array(), StandardCharsets.US_ASCII)))
                throw new IllegalStateException("held app record changed");
        } catch (Exception failure) { persistenceFailed = true; throw failure; }
    }
    static final class CallbackGuard {
        private final EmbeddedDuplexProcessFence owner;
        private long generation;
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
    synchronized void requireLive(long expectedGeneration) {
        if (persistenceFailed || !lock.isValid() || !channel.isOpen() || expectedGeneration != generation) {
            throw new IllegalStateException("stale app process generation");
        }
        if (incarnation != null) {
            try { verifyHeldRecord(); verifyIncarnation(persistedRecord); }
            catch (Exception failure) {
                persistenceFailed = true;
                throw new IllegalStateException("bound process fence custody unavailable", failure);
            }
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
    /** Read-only inspection, including a cold-restored Pending owner; never execution admission. */
    public synchronized String nativeInspectionDirectory() throws Exception {
        requireLive(generation);
        verifyHeldRecord();
        return directory.getCanonicalPath();
    }
    /** Fixed internal recovery callback; never available as ordinary admission. */
    public String nativeColdRecoveryRecord() throws Exception {
        return verifyColdRecoveryCallback(false);
    }
    public String nativeColdRecoveryEvidence() throws Exception {
        return verifyColdRecoveryCallback(true);
    }
    private String verifyColdRecoveryCallback(boolean evidenceOnly)throws Exception{
        java.util.concurrent.Callable<String> verifier;String expected;Object owner;
        synchronized(this){requireColdRecovery();verifier=coldRecoveryEvidence;expected=coldRecoveryPendingRecord;owner=coldRecoveryOwner;}
        String evidence=verifier.call(); // Physical owners may call Native; never hold this monitor here.
        synchronized(this){requireColdRecovery();if(owner!=coldRecoveryOwner||!expected.equals(persistedRecord))throw new IllegalStateException("cold recovery owner changed");}
        return evidenceOnly?evidence:expected;
    }
    synchronized void armColdRecovery(Object actualOwner,java.util.concurrent.Callable<String> verifier)throws Exception{
        requireLive(generation);verifyHeldRecord();
        if(!recoveryOnly||!pending||actualOwner==null||verifier==null||coldRecoveryOwner!=null&&coldRecoveryOwner!=actualOwner)
            throw new IllegalStateException("cold recovery scope unavailable");
        coldRecoveryOwner=actualOwner;coldRecoveryEvidence=verifier;coldRecoveryPendingRecord=persistedRecord;
    }
    synchronized void enterInterruptedColdRecovery()throws Exception{
        requireFresh();verifyHeldRecord();if(pending)throw new IllegalStateException("interrupted recovery overlaps effects");
        pending=true;recoveryOnly=true;persist();
    }
    private void requireColdRecovery()throws Exception{
        requireLive(generation);verifyHeldRecord();
        if(!recoveryOnly||!pending||coldRecoveryOwner==null||coldRecoveryEvidence==null||!persistedRecord.equals(coldRecoveryPendingRecord))
            throw new IllegalStateException("cold recovery admission unavailable");
    }
    String coldRecoveryClearRecord(Object actualOwner)throws Exception{
        synchronized(this){requireColdRecovery();if(coldRecoveryOwner!=actualOwner)throw new IllegalStateException("cold recovery token differs");
            return recordedPhase(persistedRecord,"clear");}
    }
    void finishColdRecovery(Object actualOwner,EmbeddedDuplexProcessIncarnation.Identity current)throws Exception{
        verifyColdRecoveryCallback(false);
        synchronized(this){requireColdRecovery();if(coldRecoveryOwner!=actualOwner)throw new IllegalStateException("cold recovery token differs");
            // The caller retained the verified Native/local retirement before authorizing this transition.
            pending=false;recoveryOnly=false;incarnation=null;
            try{persist();EmbeddedDuplexProcessIncarnation.Binding replacement=new EmbeddedDuplexProcessIncarnation.Binding(current,persistedRecord);
                EmbeddedDuplexProcessIncarnation.persist(incarnationDirectory,replacement,persistedRecord,directorySync);incarnation=replacement;
                coldRecoveryOwner=null;coldRecoveryEvidence=null;coldRecoveryPendingRecord=null;
            }catch(Exception failure){persistenceFailed=true;throw failure;}
        }
    }
    static String recordedPhase(String record,String state)throws Exception{
        String[] f=record.split("\n",-1);boolean explicit=f.length==9&&f[0].equals("rusty.quest.embedded_duplex.app_process_fence.v2");int checksum=explicit?7:6;
        if(f.length!=checksum+2||!explicit&&!f[0].equals("rusty.quest.embedded_duplex.app_process_fence.v1")
                ||!f[1].matches("[1-9][0-9]{0,18}")||Long.parseLong(f[1])<=0
                ||!f[2].matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                ||!java.util.Set.of("pending","clear").contains(f[3])||!java.util.Set.of("pending","clear").contains(state)
                ||!f[4].matches("-|[0-9a-f]{64}")||!f[5].matches("-|[0-9a-f]{64}")
                ||explicit&&!f[6].matches("[0-9a-f]{32}")||!f[checksum].equals(digest(String.join("\n",java.util.Arrays.copyOf(f,checksum))+"\n"))||!f[checksum+1].isEmpty())
            throw new IllegalStateException("recorded app phase invalid");
        f[3]=state;String body=String.join("\n",java.util.Arrays.copyOf(f,checksum))+"\n";return body+digest(body)+"\n";
    }
    synchronized boolean newlyInitialized(){return newlyInitialized;}
    synchronized long previousGeneration(){return acquisitionGeneration;}
    synchronized void operationalHandoff(String nextRunId,java.util.concurrent.Callable<org.json.JSONObject> exactSource){
        if(nextRunId!=null&&!nextRunId.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("handoff run id");
        handoffRunId=nextRunId;handoffSource=exactSource;
    }
    /** Exact retained source is revalidated on every read. A stored locator is never a reusable permit. */
    public synchronized String nativeOperationalRunHandoff()throws Exception{
        requireLive(generation);verifyHeldRecord();
        if(handoffSource==null)return "";
        org.json.JSONObject source=handoffSource.call();
        return EmbeddedDuplexCanonicalJson.encode(new org.json.JSONObject(source.toString())
            .put("$schema","rusty.quest.embedded_duplex.operational_run_handoff.v1")
            .put("operational_run_id",handoffRunId).put("app_record_sha256",digest(persistedRecord)));
    }
    synchronized String operationalRunId(){requireLive(generation);return operationalRunId;}
    /** Readback only, including clear records. Never substitutes for pending-only admission. */
    public synchronized String nativeInspectionRecord()throws Exception{requireLive(generation);verifyHeldRecord();return persistedRecord;}
    /** The command owner first joins actual global resources/Native owners; a clear bit alone is insufficient. */
    synchronized void selectOperationalRun(String runId,File companion,String checkpoint,String evidence)throws Exception{
        requireFresh();verifyHeldRecord();
        if(pending)throw new IllegalStateException("current operational effects pending");
        if(runId==null||!runId.matches("[0-9a-f]{32}")||companion==null
                ||!Files.isDirectory(companion.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(companion.toPath()))throw new IllegalArgumentException("run fence binding");
        if(runId.equals(operationalRunId))return;
        generation=Math.addExact(generation,1);nonce=UUID.randomUUID().toString();
        operationalRunId=runId;incarnationDirectory=companion;incarnation=null;acquisitionRecord=null;
        bind(checkpoint,evidence);persist();
    }
    private String serializedRecord() throws Exception {
        String body = (operationalRunId==null?"rusty.quest.embedded_duplex.app_process_fence.v1\n":"rusty.quest.embedded_duplex.app_process_fence.v2\n") + generation + "\n"
                + nonce + "\n" + (pending ? "pending" : "clear") + "\n"
                + checkpointDigest + "\n" + evidenceDigest + "\n" + (operationalRunId==null?"":operationalRunId+"\n");
        return body + digest(body) + "\n";
    }
    private void persist() throws Exception {
        requireLive(generation);
        try {
        String record = serializedRecord();
        byte[] bytes = record.getBytes(StandardCharsets.US_ASCII);
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.truncate(bytes.length);
        channel.force(true);
        directorySync.sync();
        if (incarnation != null) EmbeddedDuplexProcessIncarnation.persist(incarnationDirectory, incarnation, record, directorySync);
        persistedRecord = record;
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

    /** Cleanup-only admission, never a new Start or a claim of absent physical effects. */
    synchronized void beforeNativeAbsenceRetirement(String checkpoint, String evidence) throws Exception {
        requireFresh();
        if (!pending) { pending=true; bind(checkpoint,evidence); persist(); }
        else requireLive(generation);
    }
}
