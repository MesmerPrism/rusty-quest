package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Real host OS-lock regressions; no Android/device or teardown claims. */
public final class EmbeddedDuplexProcessFenceHostTest {
    interface Attempt { void run() throws Exception; }
    static void rejects(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("expected fail-closed rejection");
    }
    static EmbeddedDuplexProcessFence acquire(Path path) throws Exception {
        return EmbeddedDuplexProcessFence.acquire(path.toFile(), null, null, () -> {});
    }
    static Process holder(Path path, boolean pending) throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                EmbeddedDuplexProcessFenceHostTest.class.getName(), "hold", path.toString(),
                Boolean.toString(pending)).redirectErrorStream(true).start();
        String ready = new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();
        if (!"held".equals(ready)) throw new AssertionError("child failed: " + ready);
        return process;
    }
    // Read only the held descriptor: opening another descriptor can release fcntl locks on Linux.
    static String heldRecord(EmbeddedDuplexProcessFence fence) throws Exception {
        java.lang.reflect.Field field = EmbeddedDuplexProcessFence.class.getDeclaredField("channel");
        field.setAccessible(true);
        java.nio.channels.FileChannel channel = (java.nio.channels.FileChannel) field.get(fence);
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate((int) channel.size());
        channel.position(0);
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new AssertionError("record truncated");
        return new String(bytes.array(), StandardCharsets.US_ASCII);
    }
    static EmbeddedDuplexProcessIncarnation.Identity identity(long ticks) {
        return new EmbeddedDuplexProcessIncarnation.Identity("00112233-4455-6677-8899-aabbccddeeff", 42, 10001, ticks);
    }
    // JNI resolves this public callback by name; exercise that same lookup on the real held writer.
    static String inspectionDirectory(EmbeddedDuplexProcessFence fence) throws Exception {
        try { return (String)EmbeddedDuplexProcessFence.class.getMethod("nativeInspectionDirectory").invoke(fence); }
        catch(java.lang.reflect.InvocationTargetException rejected) {
            if(rejected.getCause() instanceof IllegalStateException)throw (IllegalStateException)rejected.getCause();
            throw rejected;
        }
        catch(NoSuchMethodException absent) { throw new AssertionError("read-only inspection directory callback unavailable",absent); }
    }
    static void inspectionControls() throws Exception {
        Path directory=Files.createTempDirectory("duplex-cold-inspection");
        try(EmbeddedDuplexProcessFence initial=acquire(directory)){initial.beforeRuntimeEffects(null,null);}
        EmbeddedDuplexProcessFence recovered=acquire(directory);
        if(!recovered.recoveryOnly()||!recovered.effectsPending())throw new AssertionError("fixture did not restore actual Pending");
        String exact=recovered.nativeInspectionRecord();
        if(!directory.toFile().getCanonicalPath().equals(inspectionDirectory(recovered)))throw new AssertionError("inspection directory differs from held owner");
        if(!exact.equals(recovered.nativeInspectionRecord())||!recovered.effectsPending()||!recovered.recoveryOnly())
            throw new AssertionError("inspection changed pending/recovery/generation bytes");
        rejects(recovered::nativeFenceDirectory);rejects(recovered::nativeAdmissionRecord);
        rejects(recovered::requireFresh);rejects(recovered::callbacks);
        rejects(()->recovered.beforeRuntimeEffects(null,null));
        rejects(()->recovered.beforeNativeAbsenceRetirement(null,null));
        rejects(()->recovered.afterVerifiedNoMediaCleanup(null,null));
        rejects(()->recovered.afterVerifiedWholeProductCleanup(null,null,"a".repeat(64)));
        // Corrupt the already-held inode through its retained descriptor; never reopen the fcntl inode.
        var field=EmbeddedDuplexProcessFence.class.getDeclaredField("channel");field.setAccessible(true);
        var channel=(java.nio.channels.FileChannel)field.get(recovered);
        channel.position(0);channel.write(java.nio.ByteBuffer.wrap("x".getBytes(StandardCharsets.US_ASCII)));channel.force(true);
        rejects(()->inspectionDirectory(recovered));
        recovered.close();rejects(()->inspectionDirectory(recovered));
        System.out.println("Cold Pending inspection controls passed; actual writer verified, all effect gates retained.");
    }
    static void incarnationControls() throws Exception {
        Path dir = Files.createTempDirectory("duplex-bound-fence");
        try (EmbeddedDuplexProcessFence fence = acquire(dir)) {
            String before = heldRecord(fence);
            fence.bindIncarnation(identity(100));
            if (!before.equals(heldRecord(fence)) || fence.effectsPending())
                throw new AssertionError("binding changed fence phase/generation");
            fence.bindIncarnation(identity(100));
            rejects(() -> fence.bindIncarnation(identity(101)));
            rejects(() -> acquire(dir));
            fence.beforeRuntimeEffects("c", "e");
            String pending = fence.nativeAdmissionRecord();
            if (EmbeddedDuplexProcessIncarnation.load(dir.toFile(), pending) == null)
                throw new AssertionError("pending companion did not track exact phase");
            fence.afterVerifiedNoMediaCleanup(null, null);
            if (EmbeddedDuplexProcessIncarnation.load(dir.toFile(), heldRecord(fence)) == null)
                throw new AssertionError("clear companion did not track exact phase");
        }
        try (EmbeddedDuplexProcessFence sameActor = acquire(dir)) {
            sameActor.bindIncarnation(identity(100));
            if (sameActor.generation() != 2 || sameActor.effectsPending()) throw new AssertionError("binding rollover changed generation");
        }
        try (EmbeddedDuplexProcessFence otherActor = acquire(dir)) { rejects(() -> otherActor.bindIncarnation(identity(200))); }
        // Four-argument acquisition is intentionally unchanged even for a companion conflict.
        try (EmbeddedDuplexProcessFence legacy = acquire(dir)) { if (legacy.generation() != 4) throw new AssertionError("legacy acquire changed"); }
        for (String damage : new String[]{"missing", "torn", "corrupt", "witness", "duplicate"}) {
            Path corrupt = Files.createTempDirectory("duplex-incarnation-" + damage);
            try (EmbeddedDuplexProcessFence fence = acquire(corrupt)) { fence.bindIncarnation(identity(100)); }
            Path record = corrupt.resolve("process-incarnation.v1.json");
            if (damage.equals("missing")) Files.delete(record);
            if (damage.equals("torn")) Files.writeString(corrupt.resolve("process-incarnation.v1.json.new"), "partial");
            if (damage.equals("corrupt")) Files.writeString(record, "{}");
            if (damage.equals("witness")) Files.delete(corrupt.resolve("process-incarnation.v1.initialized"));
            if (damage.equals("duplicate")) {
                String exact = Files.readString(record);
                Files.writeString(record, exact.substring(0, 1) + "\"sha256\":\"duplicate\"," + exact.substring(1));
            }
            try (EmbeddedDuplexProcessFence fence = acquire(corrupt)) { rejects(() -> fence.bindIncarnation(identity(100))); }
        }
        Path pending = Files.createTempDirectory("duplex-incarnation-pending");
        try (EmbeddedDuplexProcessFence fence = acquire(pending)) { fence.beforeRuntimeEffects(null, null); }
        try (EmbeddedDuplexProcessFence fence = acquire(pending)) {
            fence.bindIncarnation(identity(100));
            if (!fence.effectsPending() || !fence.recoveryOnly()) throw new AssertionError("incarnation masqueraded as cleanup");
            rejects(fence::requireFresh);
        }
        Path vanished = Files.createTempDirectory("duplex-incarnation-vanished");
        try (EmbeddedDuplexProcessFence fence = acquire(vanished)) {
            fence.bindIncarnation(identity(100));
            Files.delete(vanished.resolve("process-incarnation.v1.json"));
            rejects(fence::requireFresh);
            rejects(() -> fence.beforeRuntimeEffects(null, null));
            rejects(fence::requireFresh);
        }
    }
    static void runControls() throws Exception {
        Path global=Files.createTempDirectory("run-global"),a=Files.createTempDirectory("run-a"),b=Files.createTempDirectory("run-b");
        try(EmbeddedDuplexProcessFence fence=acquire(global)) {
            Object inode=Files.getAttribute(global.resolve("process-fence.v1.lock"),"basic:fileKey");
            fence.selectOperationalRun("a".repeat(32),a.toFile(),null,null);
            fence.bindIncarnation(identity(100));String old=Files.readString(a.resolve("process-incarnation.v1.json"));
            EmbeddedDuplexProcessFence.CallbackGuard callback=fence.callbacks();
            fence.beforeRuntimeEffects(null,null);
            rejects(()->fence.selectOperationalRun("b".repeat(32),b.toFile(),null,null));
            fence.afterVerifiedWholeProductCleanup(null,null,"0".repeat(64));
            fence.selectOperationalRun("b".repeat(32),b.toFile(),null,null);
            rejects(callback::requireLive);fence.bindIncarnation(identity(200));
            if(!old.equals(Files.readString(a.resolve("process-incarnation.v1.json"))))throw new AssertionError("old incarnation changed");
            if(!"b".repeat(32).equals(fence.operationalRunId()))throw new AssertionError("run absent");
            String record=fence.nativeInspectionRecord();if(!record.startsWith("rusty.quest.embedded_duplex.app_process_fence.v2\n")||!record.contains("\nclear\n"))throw new AssertionError("v2 inspection");
            rejects(fence::nativeAdmissionRecord);
            if(!java.util.Objects.equals(inode,Files.getAttribute(global.resolve("process-fence.v1.lock"),"basic:fileKey")))throw new AssertionError("inode replaced");
        }
        rejects(()->acquire(global));
        try(EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(global.toFile(),null,null,()->{},"b".repeat(32),b.toFile())){
            if(!"b".repeat(32).equals(fence.operationalRunId()))throw new AssertionError("restart run mismatch");
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            EmbeddedDuplexProcessFence fence = acquire(Path.of(args[1]));
            if (Boolean.parseBoolean(args[2])) fence.beforeRuntimeEffects(null, null);
            System.out.println("held"); System.out.flush();
            System.in.read();
            fence.close();
            return;
        }
        incarnationControls();
        runControls();
        inspectionControls();
        predecessorPublicationCuts();
        Path directory = Files.createTempDirectory("duplex-fence-test");
        EmbeddedDuplexProcessFence first = acquire(directory);
        if (first.generation() != 1) throw new AssertionError("initial generation");
        rejects(() -> acquire(directory));
        EmbeddedDuplexProcessFence.CallbackGuard draining = first.callbacks();
        EmbeddedDuplexProcessFence.CallbackLease lease = draining.enter();
        java.util.concurrent.CompletableFuture<Void> drained = draining.retireAndDrain();
        if (drained.isDone()) throw new AssertionError("entered callback was credited as joined");
        rejects(draining::enter);
        lease.close();
        if (!drained.isDone() || drained.isCompletedExceptionally()) throw new AssertionError("actual callback return did not join");
        EmbeddedDuplexProcessFence.CallbackGuard liveCallback = first.callbacks();
        EmbeddedDuplexProcessFence.CallbackGuard retiredCallback = first.callbacks();
        liveCallback.requireLive();
        retiredCallback.retire();
        rejects(retiredCallback::requireLive);
        long retired = first.generation();
        first.close();
        rejects(() -> first.requireLive(retired));
        rejects(liveCallback::requireLive);
        EmbeddedDuplexProcessFence second = acquire(directory);
        if (second.generation() != 2 || second.recoveryOnly()) throw new AssertionError("durable generation");
        rejects(() -> second.requireLive(retired));
        second.beforeRuntimeEffects(null, null);
        rejects(() -> second.beforeRuntimeEffects(null, null));
        second.afterVerifiedNoMediaCleanup(null, null); // Simulated caller proof; no device assertion.
        second.close();
        Process live = holder(directory, false);
        rejects(() -> acquire(directory));
        live.getOutputStream().write(1); live.getOutputStream().flush();
        if (live.waitFor() != 0) throw new AssertionError("child release");
        Process crashed = holder(directory, true);
        crashed.destroyForcibly().waitFor();
        try (EmbeddedDuplexProcessFence recovered = acquire(directory)) {
            if (!recovered.recoveryOnly() || recovered.generation() != 5) throw new AssertionError("crash pending generation");
            rejects(recovered::requireFresh);
            rejects(recovered::callbacks);
            rejects(() -> recovered.afterVerifiedNoMediaCleanup(null, null));
        }
        // Releasing a pending fence never changes pending to clear.
        try (EmbeddedDuplexProcessFence recovered = acquire(directory)) {
            if (!recovered.recoveryOnly()) throw new AssertionError("release masqueraded as cleanup");
        }
        Path migration = Files.createTempDirectory("duplex-fence-migration");
        rejects(() -> EmbeddedDuplexProcessFence.acquire(migration.toFile(), "retained", null, () -> {}));
        rejects(() -> acquire(migration)); // Failed migration leaves missing state fail closed.
        Path binding = Files.createTempDirectory("duplex-fence-binding");
        try (EmbeddedDuplexProcessFence bound = acquire(binding)) {
            bound.beforeRuntimeEffects("checkpoint exact bytes", "evidence exact bytes");
        }
        rejects(() -> acquire(binding));
        try (EmbeddedDuplexProcessFence bound = EmbeddedDuplexProcessFence.acquire(binding.toFile(),
                "checkpoint exact bytes", "evidence exact bytes", () -> {})) {
            if (!bound.recoveryOnly()) throw new AssertionError("journal binding lost pending");
        }
        Path corrupt = Files.createTempDirectory("duplex-fence-corrupt");
        try (EmbeddedDuplexProcessFence unused = acquire(corrupt)) { }
        Path record = corrupt.resolve("process-fence.v1.lock");
        Files.writeString(record, "corrupt", StandardCharsets.US_ASCII);
        rejects(() -> acquire(corrupt));
        Files.writeString(record, "", StandardCharsets.US_ASCII);
        rejects(() -> acquire(corrupt));
        Path missing = Files.createTempDirectory("duplex-fence-missing");
        try (EmbeddedDuplexProcessFence unused = acquire(missing)) { }
        Files.delete(missing.resolve("process-fence.v1.lock"));
        rejects(() -> acquire(missing));
        preservedJournalQuarantine();
        System.out.println("App process fence host regressions passed; native/effect/device fencing unproven.");
    }

    static void preservedJournalQuarantine() throws Exception {
        Path global=Files.createTempDirectory("duplex-journal-quarantine"),replay=Files.createTempDirectory("duplex-journal-owner");
        String run="d".repeat(32),prior;
        EmbeddedDuplexProcessIncarnation.Identity old=new EmbeddedDuplexProcessIncarnation.Identity("00112233-4455-6677-8899-aabbccddeeff",55,10001,101);
        try(EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(global.toFile(),null,null,()->{},run,replay.toFile())){
            fence.bindIncarnation(old);
            fence.beforeRuntimeEffects("old checkpoint exact", "old evidence exact");
            prior=fence.nativeInspectionRecord();
        }
        rejects(()->EmbeddedDuplexProcessIncarnation.readForRecovery(replay.toFile()));
        byte[] companion=Files.readAllBytes(replay.resolve("process-incarnation.v1.json"));
        try(EmbeddedDuplexProcessFence quarantined=EmbeddedDuplexProcessFence.acquire(global.toFile(),"new checkpoint exact","new evidence exact",()->{},run,replay.toFile())){
            if(!quarantined.recoveryOnly()||!quarantined.effectsPending())throw new AssertionError("Pending journal drift was not quarantined");
            rejects(quarantined::requireFresh);
            if(!prior.equals(quarantined.acquisitionRecordForRecovery()))throw new AssertionError("exact prior phase lost");
            EmbeddedDuplexProcessIncarnation.RecoveryBinding last=EmbeddedDuplexProcessIncarnation.readForRecovery(replay.toFile(),quarantined.acquisitionRecordForRecovery());
            if(!last.phaseRecord.equals(prior)||!last.binding.owner.binding().equals(old.binding()))throw new AssertionError("prior companion owner changed");
            String[] fields=quarantined.nativeInspectionRecord().split("\n",-1);
            if(!fields[4].equals(EmbeddedDuplexProcessFence.digest("new checkpoint exact"))||!fields[5].equals(EmbeddedDuplexProcessFence.digest("new evidence exact")))throw new AssertionError("observed journal digests not bound");
            if(!java.util.Arrays.equals(companion,Files.readAllBytes(replay.resolve("process-incarnation.v1.json"))))throw new AssertionError("old companion rewritten");
        }
        try(EmbeddedDuplexProcessFence repeated=EmbeddedDuplexProcessFence.acquire(global.toFile(),"new checkpoint exact","new evidence exact",()->{},run,replay.toFile())){
            EmbeddedDuplexProcessIncarnation.RecoveryBinding last=EmbeddedDuplexProcessIncarnation.readForRecovery(replay.toFile(),repeated.acquisitionRecordForRecovery());
            if(!last.phaseRecord.equals(prior)||!repeated.recoveryOnly())throw new AssertionError("crash before prepare lost exact old phase");
            Path witness=replay.resolve("process-incarnation.phase."+EmbeddedDuplexProcessFence.digest(prior)+".record");
            byte[] exact=Files.readAllBytes(witness);Files.writeString(witness,EmbeddedDuplexProcessFence.recordedPhase(prior,"clear"));
            rejects(()->EmbeddedDuplexProcessIncarnation.readForRecovery(replay.toFile(),repeated.acquisitionRecordForRecovery()));Files.write(witness,exact);
        }
        Path clear=Files.createTempDirectory("duplex-clear-journal-binding"),clearReplay=Files.createTempDirectory("duplex-clear-journal-owner");
        try(EmbeddedDuplexProcessFence fence=EmbeddedDuplexProcessFence.acquire(clear.toFile(),null,null,()->{},run,clearReplay.toFile())){
            fence.beforeRuntimeEffects("retained checkpoint", "retained evidence");
            fence.afterVerifiedNoMediaCleanup("retained checkpoint", "retained evidence");
        }
        rejects(()->EmbeddedDuplexProcessFence.acquire(clear.toFile(),"changed checkpoint","changed evidence",()->{},run,clearReplay.toFile()));
    }
    static void predecessorPublicationCuts()throws Exception{
        for(int point=1;point<=3;point++){
            final int cut=point;
            Path global=Files.createTempDirectory("duplex-predecessor-global"),replay=Files.createTempDirectory("duplex-predecessor-owner");
            String run="f".repeat(32),prior;
            try(EmbeddedDuplexProcessFence f=EmbeddedDuplexProcessFence.acquire(global.toFile(),null,null,()->{},run,replay.toFile())){
                f.bindIncarnation(identity(101));f.beforeRuntimeEffects("prior checkpoint","prior evidence");prior=f.nativeInspectionRecord();
            }
            byte[] old=Files.readAllBytes(global.resolve("process-fence.v1.lock"));
            Path witness=replay.resolve("process-incarnation.phase."+EmbeddedDuplexProcessFence.digest(prior)+".record");
            int[] calls={0};
            rejects(()->EmbeddedDuplexProcessFence.acquire(global.toFile(),"new checkpoint","new evidence",()->{
                if(++calls[0]==cut)throw new IllegalStateException("injected durable publication cut");
            },run,replay.toFile()));
            if(cut==1&&Files.exists(witness))throw new AssertionError("predecessor published before complete staged durability");
            if(cut<=2&&!java.util.Arrays.equals(old,Files.readAllBytes(global.resolve("process-fence.v1.lock"))))throw new AssertionError("global overwritten before predecessor publication completed");
            if(cut>=2&&!java.util.Arrays.equals(old,Files.readAllBytes(witness)))throw new AssertionError("published predecessor bytes differ");
            // Partial unique staging bytes are never a checksum-named witness.
            Path orphan=replay.resolve(witness.getFileName()+".orphan.new");Files.writeString(orphan,"partial predecessor");
            try(EmbeddedDuplexProcessFence f=EmbeddedDuplexProcessFence.acquire(global.toFile(),"new checkpoint","new evidence",()->{},run,replay.toFile())){
                EmbeddedDuplexProcessIncarnation.RecoveryBinding recovered=EmbeddedDuplexProcessIncarnation.readForRecovery(replay.toFile(),f.acquisitionRecordForRecovery());
                if(!prior.equals(recovered.phaseRecord)||!f.recoveryOnly())throw new AssertionError("publication cut lost authenticated prior phase");
                if(!"partial predecessor".equals(Files.readString(orphan)))throw new AssertionError("orphan bytes changed");
            }
        }
    }

}
