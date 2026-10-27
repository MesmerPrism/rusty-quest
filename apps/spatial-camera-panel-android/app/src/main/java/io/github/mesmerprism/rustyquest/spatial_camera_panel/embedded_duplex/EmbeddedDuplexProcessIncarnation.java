package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import org.json.JSONObject;

/** Actual app-process birth identity and a fence-owned companion; confers no cleanup authority. */
final class EmbeddedDuplexProcessIncarnation {
    private static final String NAME = "process-incarnation.v1.json";
    private static final String WITNESS = "process-incarnation.v1.initialized";
    private static final String SCHEMA = "rusty.quest.embedded_duplex.process_incarnation.v1";
    static final class Identity {
        final String boot;
        final int pid, uid;
        final long ticks;
        Identity(String boot, int pid, int uid, long ticks) {
            if (boot == null || !boot.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                    || pid <= 0 || uid < 10000 || ticks <= 0)
                throw new IllegalArgumentException("invalid process incarnation");
            this.boot = boot; this.pid = pid; this.uid = uid; this.ticks = ticks;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("boot_id", boot).put("pid", pid).put("uid", uid).put("start_ticks", ticks);
        }
        static Identity read(JSONObject value) throws Exception {
            if (!keys(value).equals(Set.of("boot_id", "pid", "uid", "start_ticks")))
                throw new IllegalStateException("incarnation fields");
            for (String key : new String[]{"pid", "uid", "start_ticks"}) {
                Object number = value.get(key);
                if (!(number instanceof Integer || number instanceof Long))
                    throw new IllegalStateException("incarnation integer type");
            }
            if (value.getLong("pid") > Integer.MAX_VALUE || value.getLong("uid") > Integer.MAX_VALUE
                    || value.getLong("pid") <= 0 || value.getLong("uid") < 10000)
                throw new IllegalStateException("incarnation integer range");
            return new Identity(value.getString("boot_id"), value.getInt("pid"), value.getInt("uid"), value.getLong("start_ticks"));
        }
        String binding() { return boot + "\n" + pid + "\n" + uid + "\n" + ticks + "\n"; }
    }
    static Identity current(int pid, int uid) throws Exception { return current(pid, uid, Paths.get("/proc")); }
    static Identity current(int pid, int uid, Path proc) throws Exception {
        String first = boot(proc);
        Long ticks = kernelStartTicks(pid, proc);
        String last = boot(proc);
        if (ticks == null || !first.equals(last)) throw new IllegalStateException("current process incarnation unavailable");
        return new Identity(first, pid, uid, ticks);
    }
    private static String boot(Path proc) throws Exception {
        Path path = proc.resolve("sys/kernel/random/boot_id");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > 128) throw new IllegalStateException("boot identity bounds");
        return new String(bytes, StandardCharsets.US_ASCII).trim();
    }
    static Long kernelStartTicks(int pid) throws Exception { return kernelStartTicks(pid, Paths.get("/proc")); }
    static String retirementBasis(Identity old,Identity current)throws Exception{
        if(old.uid!=current.uid)throw new IllegalStateException("retired owner UID differs");
        String first=boot(Paths.get("/proc"));Long actual=kernelStartTicks(old.pid);String last=boot(Paths.get("/proc"));
        if(!first.equals(last)||!first.equals(current.boot))throw new IllegalStateException("retirement boot observation changed");
        if(!old.boot.equals(first))return "boot_changed";
        if(actual==null)return "not_running";
        if(actual.longValue()!=old.ticks)return "pid_reused";
        throw new IllegalStateException("old app process remains live");
    }
    static final class RecoveryBinding {
        final Binding binding;final String exact,phaseRecord;
        RecoveryBinding(Binding binding,String exact,String phaseRecord){this.binding=binding;this.exact=exact;this.phaseRecord=phaseRecord;}
    }
    /** Read one exact owner companion, without rewriting an old actor or requiring its callback. */
    static RecoveryBinding readForRecovery(File directory)throws Exception{
        String exact=read(new File(directory,NAME).toPath(),4096);JSONObject value=EmbeddedDuplexCanonicalJson.readOwned(exact.getBytes(StandardCharsets.UTF_8));
        String original=value.getString("original_fence_record");
        String clear=EmbeddedDuplexProcessFence.recordedPhase(original,"clear"),pending=EmbeddedDuplexProcessFence.recordedPhase(original,"pending");
        String current=value.getString("current_fence_sha256");
        String phase=current.equals(EmbeddedDuplexProcessFence.digest(pending))?pending:current.equals(EmbeddedDuplexProcessFence.digest(clear))?clear:null;
        if(phase==null)throw new IllegalStateException("incarnation retained phase unsupported");
        Binding binding=load(directory,phase);if(binding==null)throw new IllegalStateException("incarnation recovery witness invalid");
        return new RecoveryBinding(binding,exact,phase);
    }
    /** The held fence supplies the exact prior phase; callers never supply a replacement owner. */
    static RecoveryBinding readForRecovery(File directory,String priorRecord)throws Exception{
        EmbeddedDuplexProcessFence.recordedPhase(priorRecord,"pending");
        String exact=read(new File(directory,NAME).toPath(),4096);
        JSONObject value=EmbeddedDuplexCanonicalJson.readOwned(exact.getBytes(StandardCharsets.UTF_8));
        String original=value.getString("original_fence_record");
        EmbeddedDuplexProcessFence.recordedPhase(original,"pending");
        String expected=value.getString("current_fence_sha256");
        if(!expected.matches("[0-9a-f]{64}"))throw new IllegalStateException("incarnation retained digest invalid");
        if(!expected.equals(EmbeddedDuplexProcessFence.digest(priorRecord))){
            String acquisition=priorRecord;
            priorRecord=read(phasePath(directory,expected),512);
            EmbeddedDuplexProcessFence.recordedPhase(priorRecord,"pending");
            String[] acquired=acquisition.split("\n",-1),witness=priorRecord.split("\n",-1);
            if(!expected.equals(EmbeddedDuplexProcessFence.digest(priorRecord))||acquired.length!=witness.length
                    ||!acquired[0].endsWith(".v2")||!acquired[6].equals(witness[6])||Long.parseLong(witness[1])>=Long.parseLong(acquired[1]))
                throw new IllegalStateException("incarnation predecessor witness differs");
        }
        String[] before=original.split("\n",-1),prior=priorRecord.split("\n",-1);
        String originalRun=before[0].endsWith(".v2")?before[6]:null;
        String priorRun=prior[0].endsWith(".v2")?prior[6]:null;
        if(!java.util.Objects.equals(originalRun,priorRun)||Long.parseLong(before[1])>Long.parseLong(prior[1])
                ||!value.getString("current_fence_sha256").equals(EmbeddedDuplexProcessFence.digest(priorRecord)))
            throw new IllegalStateException("incarnation exact retained phase differs");
        Binding binding=load(directory,priorRecord);
        if(binding==null||!binding.originalRecord.equals(original)
                ||!exact.equals(read(new File(directory,NAME).toPath(),4096)))
            throw new IllegalStateException("incarnation exact recovery witness invalid");
        return new RecoveryBinding(binding,exact,priorRecord);
    }
    /** Retain one immutable exact predecessor before a cold acquisition overwrites the global record. */
    static void retainRecoveryPhase(File directory,String record,EmbeddedDuplexProcessFence.DirectorySync sync)throws Exception{
        EmbeddedDuplexProcessFence.recordedPhase(record,"pending");
        byte[] exact=record.getBytes(StandardCharsets.UTF_8);
        if(exact.length==0||exact.length>512||!"pending".equals(record.split("\n",-1)[3])
                ||!Files.isDirectory(directory.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory.toPath()))
            throw new IllegalStateException("incarnation predecessor scope invalid");
        Path path=phasePath(directory,EmbeddedDuplexProcessFence.digest(record));
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)){
            Set<OpenOption> options=Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            java.nio.file.attribute.FileAttribute<?>[] permissions=Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix")
                ?new java.nio.file.attribute.FileAttribute<?>[]{java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}
                :new java.nio.file.attribute.FileAttribute<?>[0];
            Path staged=new File(directory,path.getFileName()+"."+java.util.UUID.randomUUID()+".new").toPath();
            try(FileChannel file=FileChannel.open(staged,options,permissions)){
                ByteBuffer bytes=ByteBuffer.wrap(exact);while(bytes.hasRemaining())file.write(bytes);file.force(true);
            }
            // A killed writer may leave its unique staging bytes, but never a
            // partially published checksum-named predecessor. The caller holds
            // the global app fence lock through both durability boundaries.
            sync.sync();
            try{Files.move(staged,path,StandardCopyOption.ATOMIC_MOVE);}
            catch(FileAlreadyExistsException raced){/* Only identical final bytes are accepted below. */}
        }
        if(!java.util.Arrays.equals(exact,readBytes(path,512)))throw new IllegalStateException("incarnation predecessor bytes changed");
        sync.sync();
    }
    private static Path phasePath(File directory,String digest){return new File(directory,"process-incarnation.phase."+digest+".record").toPath();}
    private static Long kernelStartTicks(int pid, Path proc) throws Exception {
        if (pid <= 0) throw new IllegalArgumentException("invalid PID");
        String stat;
        try { stat = new String(Files.readAllBytes(proc.resolve(Integer.toString(pid)).resolve("stat")), StandardCharsets.US_ASCII); }
        catch (NoSuchFileException absent) { return null; }
        int first = stat.indexOf('('), end = stat.lastIndexOf(')');
        if (stat.length() > 16384 || first < 1 || end <= first || !Integer.toString(pid).equals(stat.substring(0, first).trim()))
            throw new IllegalStateException("process stat identity unavailable");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length < 20 || !fields[19].matches("[1-9][0-9]{0,18}"))
            throw new IllegalStateException("process stat birth unavailable");
        long ticks;
        try { ticks = Long.parseLong(fields[19]); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("process stat birth invalid", invalid); }
        return ticks;
    }
    static final class Binding {
        final Identity owner;
        final String originalRecord;
        String currentSha;
        Binding(Identity owner, String record) throws Exception {
            this.owner = owner; originalRecord = record; currentSha = EmbeddedDuplexProcessFence.digest(record);
        }
        String checksum() throws Exception {
            return EmbeddedDuplexProcessFence.digest(owner.binding() + originalRecord + currentSha
                    + "\nfalse\n-\nprocess_local_resources\n");
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("$schema", SCHEMA).put("original_owner", owner.json())
                    .put("effect_category", "process_local_resources").put("original_fence_record", originalRecord)
                    .put("current_fence_sha256", currentSha).put("local_resources_terminated", false)
                    .put("termination_receipt_sha256", "-").put("sha256", checksum());
        }
    }
    static boolean present(File directory) {
        for (String name : new String[]{NAME, NAME + ".new", WITNESS})
            if (Files.exists(new File(directory, name).toPath(), LinkOption.NOFOLLOW_LINKS)) return true;
        return false;
    }
    /** Unknown or interrupted state never becomes an unwitnessed replacement binding. */
    static Binding load(File directory, String record) {
        try {
            Path path = new File(directory, NAME).toPath();
            if (Files.exists(Paths.get(path + ".new"), LinkOption.NOFOLLOW_LINKS)
                    || !(SCHEMA + "\n").equals(read(new File(directory, WITNESS).toPath(), 128))) return null;
            JSONObject value = EmbeddedDuplexCanonicalJson.readOwned(readBytes(path, 4096));
            if (!keys(value).equals(Set.of("$schema", "original_owner", "effect_category", "original_fence_record",
                    "current_fence_sha256", "local_resources_terminated", "termination_receipt_sha256", "sha256"))
                    || !SCHEMA.equals(value.getString("$schema"))
                    || !"process_local_resources".equals(value.getString("effect_category"))
                    || !Boolean.FALSE.equals(value.get("local_resources_terminated"))
                    || !"-".equals(value.getString("termination_receipt_sha256"))) return null;
            Binding binding = new Binding(Identity.read(value.getJSONObject("original_owner")), value.getString("original_fence_record"));
            binding.currentSha = value.getString("current_fence_sha256");
            if (!binding.currentSha.equals(EmbeddedDuplexProcessFence.digest(record))
                    || !binding.checksum().equals(value.getString("sha256"))) return null;
            return binding;
        } catch (Exception unknown) { return null; }
    }
    static void persist(File directory, Binding binding, String record, EmbeddedDuplexProcessFence.DirectorySync sync) throws Exception {
        binding.currentSha = EmbeddedDuplexProcessFence.digest(record);
        Path path = new File(directory, NAME).toPath(), temporary = Paths.get(path + ".new");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)))
            throw new IllegalStateException("incarnation companion path invalid");
        writeNew(temporary, EmbeddedDuplexCanonicalJson.encode(binding.json()));
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        sync.sync();
        Path witness = new File(directory, WITNESS).toPath();
        if (!Files.exists(witness, LinkOption.NOFOLLOW_LINKS)) { writeNew(witness, SCHEMA + "\n"); sync.sync(); }
    }
    private static String read(Path path, int bound) throws Exception {
        return new String(readBytes(path, bound), StandardCharsets.UTF_8);
    }
    private static byte[] readBytes(Path path, int bound) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path) > bound)
            throw new IllegalStateException("incarnation record unavailable");
        return Files.readAllBytes(path);
    }
    private static Set<String> keys(JSONObject value) {
        Set<String> result = new java.util.HashSet<>();
        for (java.util.Iterator<String> i = value.keys(); i.hasNext();) result.add(i.next());
        return result;
    }
    private static void writeNew(Path path, String exact) throws Exception {
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.wrap(exact.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) file.write(bytes);
            file.force(true);
        }
    }
}
