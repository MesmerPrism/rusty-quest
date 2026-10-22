package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.file.*;
import org.json.JSONObject;

/** Actual fence and private persistence; only kernel process observations are modeled. */
public final class EmbeddedDuplexProcessIncarnationHostTest {
    static final String BOOT_A="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",BOOT_B="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final EmbeddedDuplexProcessIncarnation.Identity OLD=new EmbeddedDuplexProcessIncarnation.Identity(BOOT_A,101,10001,100);
    static final EmbeddedDuplexProcessIncarnation.Identity NEXT=new EmbeddedDuplexProcessIncarnation.Identity(BOOT_A,202,10001,200);
    static EmbeddedDuplexProcessFence acquire(Path root,EmbeddedDuplexProcessIncarnation.Identity who,
            EmbeddedDuplexProcessIncarnation.Probe probe,String checkpoint,String evidence)throws Exception {
        return EmbeddedDuplexProcessFence.acquire(root.toFile(),checkpoint,evidence,()->{},who,probe);
    }
    static void check(boolean value){if(!value)throw new AssertionError("incarnation lifecycle control");}
    static Path pending(boolean teardown)throws Exception {
        Path parent=Files.createTempDirectory("duplex-incarnation-");Path root=Files.createDirectory(parent.resolve("embedded-duplex-replay"));
        try(EmbeddedDuplexProcessFence f=acquire(root,OLD,p->100L,null,null)){
            if(teardown)f.beforeNativeAbsenceRetirement(null,null);else f.beforeRuntimeEffects(null,null);
        }
        return root;
    }
    static void denied(Path root)throws Exception {
        try(EmbeddedDuplexProcessFence f=acquire(root,NEXT,p->null,null,null)){check(f.recoveryOnly());}
    }
    public static void main(String[] args)throws Exception {
        Path clear=Files.createTempDirectory("duplex-incarnation-clear-");
        try(EmbeddedDuplexProcessFence f=acquire(clear,OLD,p->100L,null,null)){check(!f.effectsPending());}
        try(EmbeddedDuplexProcessFence f=acquire(clear,NEXT,p->null,null,null)){f.requireFresh();check(!f.effectsPending());}
        for(boolean teardown:new boolean[]{false,true}){
            Path root=pending(teardown);
            try(EmbeddedDuplexProcessFence f=acquire(root,OLD,p->100L,null,null)){check(f.recoveryOnly());}
            // Status reacquisition must retain the original owner rather than attribute effects to the status reader.
            try(EmbeddedDuplexProcessFence f=acquire(root,NEXT,p->100L,null,null)){check(f.recoveryOnly());}
            try(EmbeddedDuplexProcessFence f=acquire(root,NEXT,p->null,null,null)){
                f.requireFresh();check(!f.effectsPending());
            }
            try(java.util.stream.Stream<Path> paths=Files.list(root)){
                Path receipt=paths.filter(p->p.getFileName().toString().startsWith("terminated-incarnation-")).findFirst().orElseThrow();
                JSONObject r=new JSONObject(Files.readString(receipt));
                check("process_absent".equals(r.getString("termination_reason")));
                check(!r.getBoolean("graceful_cleanup_confirmed")&&!r.getBoolean("remote_authority_transferred"));
                check(r.getJSONObject("original_owner").getLong("pid")==101);
                check(r.getString("original_fence_record").contains("\npending\n"));
            }
        }
        Path reboot=pending(false);
        try(EmbeddedDuplexProcessFence f=acquire(reboot,new EmbeddedDuplexProcessIncarnation.Identity(BOOT_B,101,10001,1),
                p->{throw new AssertionError("cross-boot birth must not be compared");},null,null)){f.requireFresh();}
        Path reused=pending(false);
        try(EmbeddedDuplexProcessFence f=acquire(reused,NEXT,p->999L,null,null)){f.requireFresh();}
        Path unknown=pending(false);
        try(EmbeddedDuplexProcessFence f=acquire(unknown,NEXT,p->{throw new java.io.IOException("kernel observation unavailable");},null,null)){
            check(f.recoveryOnly());
        }
        Path wrongUid=pending(false);
        try(EmbeddedDuplexProcessFence f=acquire(wrongUid,new EmbeddedDuplexProcessIncarnation.Identity(BOOT_A,202,10002,200),p->null,null,null)){
            check(f.recoveryOnly());
        }
        for(String remote:new String[]{"owner-dispatch.v1.json","product-activation.v1.json","retained-cleanup.v2.json",
                "retained-cleanup-preparations.v1.json","owner-dispatch.v1.json.bak","product-activation.v1.json.new",
                "start-checkpoint.v1.json.new","start-checkpoint.v1.json.bak","recovery-evidence.v1.json.new","recovery-evidence.v1.json.bak"}){
            Path root=pending(false);byte[] exact="unresolved original remote bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(root.resolve(remote),exact);
            try(EmbeddedDuplexProcessFence f=acquire(root,NEXT,p->null,null,null)){check(f.recoveryOnly());}
            check(java.util.Arrays.equals(exact,Files.readAllBytes(root.resolve(remote))));
        }
        Path journal=Files.createTempDirectory("duplex-incarnation-journal-");
        try(EmbeddedDuplexProcessFence f=acquire(journal,OLD,p->100L,null,null)){f.beforeRuntimeEffects("checkpoint","evidence");}
        try(EmbeddedDuplexProcessFence f=acquire(journal,NEXT,p->null,"checkpoint","evidence")){check(f.recoveryOnly());}
        for(String name:new String[]{"embedded-duplex-fresh-closure-v1","embedded-duplex-partial-construction-v1",
                "embedded-duplex-unknown-root-custody-v1","embedded-duplex-fresh-handoffs-v1","embedded-duplex-prepared-lifetimes-v1"}){
            Path root=pending(false),store=Files.createDirectory(root.getParent().resolve(name));
            Files.writeString(store.resolve("original.json"),"original unresolved authority");
            try(EmbeddedDuplexProcessFence f=acquire(root,NEXT,p->null,null,null)){check(f.recoveryOnly());}
            check("original unresolved authority".equals(Files.readString(store.resolve("original.json"))));
        }
        Path legacy=Files.createTempDirectory("duplex-incarnation-legacy-");
        try(EmbeddedDuplexProcessFence f=EmbeddedDuplexProcessFence.acquire(legacy.toFile(),null,null,()->{})){f.beforeRuntimeEffects(null,null);}
        denied(legacy);
        Path missing=pending(false);Files.delete(missing.resolve("process-incarnation.v1.json"));denied(missing);
        Path sidecar=pending(false);Files.writeString(sidecar.resolve("process-incarnation.v1.json.new"),"interrupted write");denied(sidecar);
        Path mismatch=pending(false);String original=Files.readString(mismatch.resolve("process-incarnation.v1.json"));
        JSONObject tampered=new JSONObject(original).put("current_fence_sha256","d".repeat(64));
        Files.writeString(mismatch.resolve("process-incarnation.v1.json"),tampered.toString());denied(mismatch);
        Path writeFailure=Files.createTempDirectory("duplex-incarnation-sync-failure-");
        boolean[] failSync={false};
        try(EmbeddedDuplexProcessFence f=EmbeddedDuplexProcessFence.acquire(writeFailure.toFile(),null,null,()->{
            if(failSync[0])throw new java.io.IOException("modeled directory sync failure");
        },OLD,p->100L)){
            failSync[0]=true;boolean rejected=false;try{f.beforeRuntimeEffects(null,null);}catch(java.io.IOException expected){rejected=true;}check(rejected);
        }
        denied(writeFailure); // A forced primary without a committed companion cannot earn termination inference.
        Path archiveMissing=pending(false);Files.writeString(archiveMissing.resolve("owner-dispatch.v1.json"),"remote pending");
        try(EmbeddedDuplexProcessFence f=acquire(archiveMissing,NEXT,p->null,null,null)){check(f.recoveryOnly());}
        try(java.util.stream.Stream<Path> paths=Files.list(archiveMissing)){
            Files.delete(paths.filter(p->p.getFileName().toString().startsWith("terminated-incarnation-")).findFirst().orElseThrow());
        }
        Files.delete(archiveMissing.resolve("owner-dispatch.v1.json")); // Fixture models later external reconciliation, not production deletion.
        denied(archiveMissing); // Lost local evidence never becomes fresh cleanup credit.
        Path clean=pending(false);
        // A genuine verified close clears the operational marker; new process admission needs no termination inference.
        try(EmbeddedDuplexProcessFence f=acquire(clean,NEXT,p->null,null,null)){
            f.beforeRuntimeEffects(null,null);f.afterVerifiedWholeProductCleanup(null,null,"e".repeat(64));
        }
        try(EmbeddedDuplexProcessFence f=acquire(clean,OLD,p->100L,null,null)){f.requireFresh();check(!f.effectsPending());}
        System.out.println("Process-incarnation fence controls PASS; kernel observations modeled; no graceful/device cleanup claim.");
    }
}
