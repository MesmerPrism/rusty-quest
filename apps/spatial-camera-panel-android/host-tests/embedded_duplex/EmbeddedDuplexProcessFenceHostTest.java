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
    static void rejectsRequest(Attempt attempt) throws Exception {
        try { attempt.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("expected closed request rejection");
    }
    static org.json.JSONObject authorization(EmbeddedDuplexProcessFence fence) throws Exception {
        org.json.JSONObject status=new org.json.JSONObject(fence.recoveryStatus());
        return new org.json.JSONObject().put("$schema","rusty.quest.embedded_duplex.abandonment_authorization.v1")
                    .put("role_id","peer_a").put("package_id","test.product.app").put("component_id","test.product.app/test.Activity")
                    .put("installed_apk_sha256","a".repeat(64)).put("signer_sha256","b".repeat(64))
                    .put("physical_serial","HOST_FIXTURE").put("boot_id","00000000-0000-0000-0000-000000000001")
                    .put("user_id",0).put("package_uid",10001).put("old_pid",123).put("old_start_ticks",456)
                    .put("process_absence_evidence_sha256","c".repeat(64)).put("authorize_abandonment",true)
                    .put("expected_predecessor_sha256",status.getString("predecessor_sha256"))
                    .put("expected_current_fence_sha256",status.getString("current_fence_sha256"));
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
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            EmbeddedDuplexProcessFence fence = acquire(Path.of(args[1]));
            if (Boolean.parseBoolean(args[2])) fence.beforeRuntimeEffects(null, null);
            System.out.println("held"); System.out.flush();
            System.in.read();
            fence.close();
            return;
        }
        Path directory = Files.createTempDirectory("duplex-fence-test");
        EmbeddedDuplexProcessFence first = acquire(directory);
        if (first.generation() != 1) throw new AssertionError("initial generation");
        rejects(() -> acquire(directory));
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
        Path abandoned = Files.createTempDirectory("duplex-abandon");
        String checkpoint="original pending checkpoint",evidence="original retained evidence";
        try(EmbeddedDuplexProcessFence original=acquire(abandoned)) { original.beforeRuntimeEffects(checkpoint,evidence); }
        Files.writeString(abandoned.resolve("start-checkpoint.v1.json"),checkpoint);
        Files.writeString(abandoned.resolve("recovery-evidence.v1.json"),evidence);
        try(EmbeddedDuplexProcessFence fresh=EmbeddedDuplexProcessFence.acquire(abandoned.toFile(),checkpoint,evidence,()->{})) {
            org.json.JSONObject status=new org.json.JSONObject(fresh.recoveryStatus());
            org.json.JSONObject auth=authorization(fresh);
            EmbeddedDuplexAbandonmentRequest request=new EmbeddedDuplexAbandonmentRequest(auth.toString());
            request.requireInstalled("test.product.app","b".repeat(64),"a".repeat(64),"test.product.app/test.Activity",10001,999);
            rejects(()->request.requireInstalled("test.product.app","b".repeat(64),"a".repeat(64),"test.product.app/test.Activity",10001,123));
            rejects(()->request.requireInstalled("other.product.app","b".repeat(64),"a".repeat(64),"test.product.app/test.Activity",10001,999));
            rejects(()->request.requireInstalled("test.product.app","d".repeat(64),"a".repeat(64),"test.product.app/test.Activity",10001,999));
            rejects(()->request.requireInstalled("test.product.app","b".repeat(64),"d".repeat(64),"test.product.app/test.Activity",10001,999));
            rejectsRequest(()->new EmbeddedDuplexAbandonmentRequest(new org.json.JSONObject(auth.toString()).put("package_uid",110001).toString()));
            rejectsRequest(()->new EmbeddedDuplexAbandonmentRequest(new org.json.JSONObject(auth.toString()).put("authorize_abandonment",false).toString()));
            rejectsRequest(()->new EmbeddedDuplexAbandonmentRequest(new org.json.JSONObject(auth.toString()).put("old_start_ticks",0).toString()));
            rejectsRequest(()->new EmbeddedDuplexAbandonmentRequest(new org.json.JSONObject(auth.toString()).put("grant","fake").toString()));
            rejects(()->fresh.abandon(checkpoint,evidence,new EmbeddedDuplexAbandonmentRequest(new org.json.JSONObject(auth.toString())
                    .put("expected_current_fence_sha256","d".repeat(64)).toString()),(path,isDirectory)->{}));
            rejects(()->fresh.abandon("changed checkpoint",evidence,request,(path,isDirectory)->{}));
            rejects(fresh::requireFresh);
            org.json.JSONObject receipt=new org.json.JSONObject(fresh.abandon(checkpoint,evidence,request,(path,isDirectory)->{}));
            if(receipt.getBoolean("old_cleanup_confirmed") || receipt.getBoolean("old_authority_transferred")
                    || !"old_scope_abandoned_cleanup_unknown".equals(receipt.getString("disposition"))) throw new AssertionError("fake terminal");
            fresh.requireFresh();
            if(Files.exists(abandoned.resolve("start-checkpoint.v1.json")) || Files.exists(abandoned.resolve("recovery-evidence.v1.json")))
                throw new AssertionError("archived active duplicates not retired");
            if(EmbeddedDuplexAbandonment.visible(abandoned.toFile(),checkpoint,true)!=null
                    || EmbeddedDuplexAbandonment.visible(abandoned.toFile(),evidence,false)!=null) throw new AssertionError("active retained journals");
            if(!checkpoint.equals(EmbeddedDuplexAbandonment.visible(abandoned.toFile(),checkpoint+"changed",true).replace("changed","")))
                throw new AssertionError("unrelated record hidden");
            Path archive=abandoned.resolve("abandoned-"+status.getString("predecessor_sha256"));
            if(!checkpoint.equals(Files.readString(archive.resolve("checkpoint.raw")))
                    || !evidence.equals(Files.readString(archive.resolve("evidence.raw")))) throw new AssertionError("old bytes lost");
            rejects(()->fresh.abandon(null,null,request,(path,isDirectory)->{}));
        }
        try(EmbeddedDuplexProcessFence restarted=EmbeddedDuplexProcessFence.acquire(abandoned.toFile(),checkpoint,evidence,()->{})) {
            if(restarted.recoveryOnly()) throw new AssertionError("committed abandonment not restored");
            restarted.beforeRuntimeEffects(null,null); // Fresh independent pending marker, not an old grant.
        }
        try(EmbeddedDuplexProcessFence again=EmbeddedDuplexProcessFence.acquire(abandoned.toFile(),checkpoint,evidence,()->{})) {
            if(!again.recoveryOnly()) throw new AssertionError("new effect crash cleared");
            rejects(again::requireFresh);
        }
        Path crash = Files.createTempDirectory("duplex-abandon-before-commit");
        try(EmbeddedDuplexProcessFence original=acquire(crash)) { original.beforeRuntimeEffects(checkpoint,evidence); }
        Files.writeString(crash.resolve("start-checkpoint.v1.json"),checkpoint);
        Files.writeString(crash.resolve("recovery-evidence.v1.json"),evidence);
        String originalRecord=Files.readString(crash.resolve("process-fence.v1.lock")); // No channel held here.
        try(EmbeddedDuplexProcessFence recovery=EmbeddedDuplexProcessFence.acquire(crash.toFile(),checkpoint,evidence,()->{})) {
            EmbeddedDuplexAbandonment.prepare(crash.toFile(),originalRecord,recovery.retainedRecord(),checkpoint,evidence,
                    recovery.generation(),authorization(recovery),()->{},(path,isDirectory)->{});
            // Deliberately no fence commit: models crash after archive/marker, before authority disposition.
        }
        try(EmbeddedDuplexProcessFence recovery=EmbeddedDuplexProcessFence.acquire(crash.toFile(),checkpoint,evidence,()->{})) {
            if(!recovery.recoveryOnly()) throw new AssertionError("uncommitted archive cleared Pending");
            rejects(recovery::requireFresh);
        }
        org.json.JSONObject marker=new org.json.JSONObject(Files.readString(abandoned.resolve("abandoned-scope.v1.json")));
        Files.writeString(abandoned.resolve("abandoned-"+marker.getString("scope_sha256")).resolve("checkpoint.raw"),"tampered");
        rejects(()->EmbeddedDuplexProcessFence.acquire(abandoned.toFile(),checkpoint,evidence,()->{}));
        Path twice=Files.createTempDirectory("duplex-two-abandonments");
        try(EmbeddedDuplexProcessFence original=acquire(twice)) { original.beforeRuntimeEffects(checkpoint,evidence); }
        Files.writeString(twice.resolve("start-checkpoint.v1.json"),checkpoint);
        Files.writeString(twice.resolve("recovery-evidence.v1.json"),evidence);
        try(EmbeddedDuplexProcessFence recovery=EmbeddedDuplexProcessFence.acquire(twice.toFile(),checkpoint,evidence,()->{})) {
            recovery.abandon(checkpoint,evidence,new EmbeddedDuplexAbandonmentRequest(authorization(recovery).toString()),(path,isDirectory)->{});
            recovery.beforeRuntimeEffects(null,null);
        }
        String firstScope=new org.json.JSONObject(Files.readString(twice.resolve("abandoned-scope.v1.json"))).getString("scope_sha256");
        Path oldTemporary=twice.resolve("abandoned-scope.v1.json."+firstScope+".new");
        Files.writeString(oldTemporary,"retained incomplete older scope temporary");
        try(EmbeddedDuplexProcessFence recovery=acquire(twice)) {
            if(!recovery.recoveryOnly()) throw new AssertionError("second obligation absent");
            Files.writeString(twice.resolve("start-checkpoint.v1.json.new"),"unknown sidecar");
            rejects(()->recovery.abandon(null,null,new EmbeddedDuplexAbandonmentRequest(authorization(recovery).toString()),(path,isDirectory)->{}));
            Files.delete(twice.resolve("start-checkpoint.v1.json.new")); // Disposable fixture only.
            recovery.abandon(null,null,new EmbeddedDuplexAbandonmentRequest(authorization(recovery).toString()),(path,isDirectory)->{});
        }
        try(EmbeddedDuplexProcessFence fresh=acquire(twice)) { fresh.requireFresh(); }
        long archives;
        try(java.util.stream.Stream<Path> paths=Files.list(twice)){ archives=paths.filter(path->path.getFileName().toString().matches("abandoned-[0-9a-f]{64}")).count(); }
        if(archives!=2) throw new AssertionError("prior abandonment archive lost");
        if(!Files.readString(oldTemporary).equals("retained incomplete older scope temporary")) throw new AssertionError("old temporary rewritten");
        System.out.println("Abandonment production controls PASS; external absence fixture attested, no device cleanup proof.");
        System.out.println("App process fence host regressions passed; native/effect/device fencing unproven.");
    }
}
