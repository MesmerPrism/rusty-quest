package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** One source-owned, cleanup-only current-run retirement. It never restores an old writable transaction. */
final class EmbeddedDuplexColdRecovery {
    static final String SCHEMA="rusty.quest.embedded_duplex.cold_pending_local_retirement.v2";
    static final String NATIVE="rusty.quest.embedded_duplex.cold_pending_native_retirement.v2";
    static final String LINK="rusty.quest.embedded_duplex.cold_pending_owner_link.v2";
    private static final String LEGACY_SCHEMA="rusty.quest.embedded_duplex.cold_pending_local_retirement.v1";
    private static final String LEGACY_NATIVE="rusty.quest.embedded_duplex.cold_pending_native_retirement.v1";
    private static final String LEGACY_LINK="rusty.quest.embedded_duplex.cold_pending_owner_link.v1";
    private static final String DIRECTORY="embedded-duplex-cold-recovery-v1";
    static final String[] LOCAL={"java_own_capture_cleanup","java_peer_resources_cleanup","java_renderer_cleanup","java_control_cleanup","java_callback_cleanup","command_lane_barrier","whole_app_physical_cleanup"};
    interface LocalOwner {void requireTerminal()throws Exception;JSONObject physicalReceipt()throws Exception;}
    static final class Plan {
        final EmbeddedDuplexProcessFence fence;final EmbeddedDuplexOriginals.Record original;final File root;
        final String run,priorRun,priorOriginal,record,link,clearRecord,packageId;
        final long counter,generation;final EmbeddedDuplexProcessIncarnation.Identity old,current;
        final String basis;LocalOwner local;String completed;
        Plan(EmbeddedDuplexProcessFence f,EmbeddedDuplexOriginals.Record o,File root,String run,String priorRun,String priorOriginal,
                long counter,String record,JSONObject link,EmbeddedDuplexProcessIncarnation.Identity old,EmbeddedDuplexProcessIncarnation.Identity current,String basis,String packageId)throws Exception{
            this.fence=f;this.original=o;this.root=root;this.run=run;this.priorRun=priorRun;this.priorOriginal=priorOriginal;this.counter=counter;
            this.record=record;this.link=EmbeddedDuplexCanonicalJson.encode(link);this.clearRecord=EmbeddedDuplexProcessFence.recordedPhase(record,"clear");
            this.generation=f.generation();this.old=old;this.current=current;this.basis=basis;this.packageId=packageId;
        }
        String originalId(){return original.id();}boolean completed(){return completed!=null;}
        String evidence()throws Exception{
            requireCurrentProcess(this);
            requireJournals(root,new JSONObject(link));
            if(local==null)throw new IllegalStateException("actual local recovery owner not armed");
            local.requireTerminal();requirePhysical(local.physicalReceipt(),false);
            if(!basis.equals(EmbeddedDuplexProcessIncarnation.retirementBasis(old,current)))throw new IllegalStateException("old process retirement observation changed");
            return link;
        }
    }
    static Plan prepare(Context context,EmbeddedDuplexProcessFence fence,String priorRun,String priorOriginal,long counter)throws Exception{
        if((priorRun==null)!=(priorOriginal==null)||counter<=0)throw new IllegalArgumentException("paired recovery source IDs required");
        EmbeddedDuplexOriginals.Record original=EmbeddedDuplexOriginals.capture();
        if(original==null||!"recover_cold_pending".equals(original.row.getString("operation")))throw new IllegalStateException("actual recovery Original absent");
        String record=fence.nativeInspectionRecord();String[] currentFields=fields(record);
        EmbeddedDuplexRunSelectionStore selection=new EmbeddedDuplexRunSelectionStore(context.getNoBackupFilesDir(),dir->{throw new IllegalStateException("recovery source reader is inert");});
        EmbeddedDuplexRunContext selected=selection.current();
        if(selected.runId==null||!selected.runId.equals(fence.operationalRunId())||!selected.runId.equals(currentFields[6]))throw new IllegalStateException("selected recovery scope differs");
        EmbeddedDuplexProcessIncarnation.RecoveryBinding last=EmbeddedDuplexProcessIncarnation.readForRecovery(new File(selected.root,"embedded-duplex-replay"),fence.acquisitionRecordForRecovery());
        String[] lastFields=fields(last.phaseRecord);
        boolean interrupted=publicationPending(selected.root);
        if(!lastFields[6].equals(selected.runId)||Long.parseLong(lastFields[1])>=fence.generation())throw new IllegalStateException("last writable recovery phase unsupported");
        EmbeddedDuplexProcessIncarnation.Identity now=EmbeddedDuplexProcessIncarnation.current(Process.myPid(),Process.myUid());
        JSONObject nativeOwner=new JSONObject(EmbeddedDuplexNative.readColdPendingOwner(fence));
        keys(nativeOwner,"$schema","native_generation","native_record_sha256","owner");
        if(!"rusty.quest.embedded_duplex.cold_pending_native_owner.v1".equals(nativeOwner.getString("$schema"))||nativeOwner.getLong("native_generation")!=counter)
            throw new IllegalStateException("actual Native recovery counter changed");
        hash(nativeOwner.getString("native_record_sha256"));
        EmbeddedDuplexProcessIncarnation.Identity old;String sourceRecord;JSONObject source;
        if(!nativeOwner.isNull("owner")){
            if(priorRun!=null)throw new IllegalArgumentException("durable Native owner needs no legacy IDs");
            source=nativeOwner.getJSONObject("owner");sourceRecord=source.getString("app_record");
            old=source.has("retirement")?last.binding.owner:EmbeddedDuplexProcessIncarnation.Identity.read(source.getJSONObject("process_identity"));
            if(source.getLong("counter")!=counter)throw new IllegalStateException("durable Native owner counter differs");
        }else{
            if(priorRun==null)throw new IllegalStateException("legacy Native counter requires bounded source owner link");
            EmbeddedDuplexRunContext prior=selection.retained(priorRun);
            EmbeddedDuplexProcessIncarnation.RecoveryBinding nativeCompanion=EmbeddedDuplexProcessIncarnation.readForRecovery(new File(prior.root,"embedded-duplex-replay"));
            sourceRecord=EmbeddedDuplexProcessFence.recordedPhase(nativeCompanion.binding.originalRecord,"pending");old=nativeCompanion.binding.owner;
            JSONObject row=EmbeddedDuplexOriginals.retainedRecoveryRow(prior.root,priorOriginal),actor=row.getJSONObject("actor_binding");
            if(actor.isNull("native_generation")||actor.getLong("native_generation")!=counter||!context.getPackageName().equals(actor.getString("package_id"))
                    ||!EmbeddedDuplexProcessFence.digest(sourceRecord).equals(actor.getString("app_record_sha256")))throw new IllegalStateException("legacy Native actor/companion linkage differs");
            source=new JSONObject().put("counter",counter).put("app_record",sourceRecord).put("process_identity",old.json());
        }
        String[] nativeFields=fields(sourceRecord);
        if(!nativeFields[3].equals("pending")||Long.parseLong(nativeFields[1])>Long.parseLong(lastFields[1])
                ||!old.binding().equals(last.binding.owner.binding())||old.uid!=now.uid)throw new IllegalStateException("Native counter/last writable owner differs");
        String basis=EmbeddedDuplexProcessIncarnation.retirementBasis(old,now);
        if(!fence.recoveryOnly()){
            if(fence.effectsPending()||!interrupted&&!restoredClearRequiresRecovery(selected.root,fence,new JSONObject(EmbeddedDuplexNative.inspectOperationalRun(fence))))throw new IllegalStateException("no supported restored recovery decision");
            fence.enterInterruptedColdRecovery();record=fence.nativeInspectionRecord();currentFields=fields(record);
        }
        if(!fence.effectsPending()||!currentFields[3].equals("pending"))throw new IllegalStateException("recovery pending scope changed");
        JSONObject link=new JSONObject().put("$schema",LINK).put("recovery_original_id",original.id()).put("run_id",selected.runId)
            .put("app_process_generation",fence.generation()).put("app_record_sha256",EmbeddedDuplexProcessFence.digest(record))
            .put("prior_native_generation",counter).put("native_record_sha256",nativeOwner.getString("native_record_sha256"))
            .put("native_owner",source).put("last_writable_record",last.phaseRecord).put("last_incarnation_json",last.exact)
            .put("old_process_identity",old.json()).put("current_process_identity",now.json()).put("old_process_retirement",basis)
            .put("journal_snapshot",journalSnapshot(selected.root,currentFields,lastFields));
        Plan plan=new Plan(fence,original,selected.root,selected.runId,priorRun,priorOriginal,counter,record,link,old,now,basis,context.getPackageName());
        EmbeddedDuplexOriginals.bindActualActor(new JSONObject().put("package_id",context.getPackageName()).put("app_record_sha256",EmbeddedDuplexProcessFence.digest(record)).put("native_generation",JSONObject.NULL),fence.generation());
        original.retainedCleanupOwner=plan;return plan;
    }
    static void arm(Plan plan,LocalOwner local)throws Exception{
        if(plan.local!=null&&plan.local!=local||plan.completed())throw new IllegalStateException("cold recovery owner replacement refused");
        plan.local=Objects.requireNonNull(local);plan.evidence();plan.fence.armColdRecovery(plan,plan::evidence);
    }
    static JSONObject complete(Plan plan,String actualNativeRetirement)throws Exception{
        if(plan.completed()){requireCompleted(plan);return new JSONObject(plan.completed);}
        plan.evidence();JSONObject nativeProof=new JSONObject(actualNativeRetirement);validateNative(nativeProof,plan);
        JSONObject physical=plan.local.physicalReceipt();requirePhysical(physical,false);
        physical=new JSONObject(physical.toString()).put("app_process_fence_cleanup","terminal");
        JSONObject receipt=new JSONObject().put("$schema",SCHEMA).put("recovery_original_id",plan.originalId()).put("run_id",plan.run)
            .put("prior_run_id",plan.priorRun==null?JSONObject.NULL:plan.priorRun).put("prior_original_id",plan.priorOriginal==null?JSONObject.NULL:plan.priorOriginal)
            .put("app_process_generation",plan.generation).put("app_record_sha256",EmbeddedDuplexProcessFence.digest(plan.record))
            .put("app_clear_record_sha256",EmbeddedDuplexProcessFence.digest(plan.clearRecord)).put("prior_native_generation",plan.counter)
            .put("old_process_identity",plan.old.json()).put("current_process_identity",plan.current.json()).put("old_process_retirement",plan.basis)
            .put("owner_link_sha256",EmbeddedDuplexProcessFence.digest(plan.link)).put("native_retirement",nativeProof).put("physical_retirement",physical)
            .put("transaction_outcome","unchanged").put("remote_outcome_resolved",false);
        File dir=directory(plan.root,true);
        JSONObject retained=new JSONObject().put("schema","rusty.quest.embedded_duplex.cold_pending_retirement_storage.v1")
            .put("package_id",plan.packageId).put("owner_link_json",plan.link).put("receipt",receipt);
        // Retain exact old companion + verified decision before the only Pending->clear transition.
        writeNew(new File(dir,plan.originalId()+".prepared.json"),retained);
        replace(new File(dir,"active.v1.json"),new JSONObject().put("schema","rusty.quest.embedded_duplex.cold_pending_active.v1").put("original_id",plan.originalId()));
        plan.evidence();
        plan.fence.finishColdRecovery(plan,plan.current);
        if(!plan.clearRecord.equals(plan.fence.nativeInspectionRecord()))throw new IllegalStateException("actual cleared recovery fence differs");
        requireJournals(plan.root,new JSONObject(plan.link));
        writeNew(new File(dir,plan.originalId()+".json"),retained);
        JSONObject pointer=new JSONObject().put("schema","rusty.quest.embedded_duplex.cold_pending_latest.v1").put("original_id",plan.originalId());
        replace(new File(dir,"latest.v1.json"),pointer);requireJournals(plan.root,new JSONObject(plan.link));
        plan.completed=EmbeddedDuplexCanonicalJson.encode(receipt);return new JSONObject(plan.completed);
    }
    static void requireCompleted(Plan plan)throws Exception{
        if(plan==null||!plan.completed())throw new IllegalStateException("actual cold recovery completion absent");
        requireCurrentProcess(plan);plan.local.requireTerminal();requirePhysical(plan.local.physicalReceipt(),false);
        requireJournals(plan.root,new JSONObject(plan.link));
        if(plan.fence.recoveryOnly()||plan.fence.effectsPending()||!plan.clearRecord.equals(plan.fence.nativeInspectionRecord()))throw new IllegalStateException("actual cold recovery fence changed");
        if(!EmbeddedDuplexCanonicalJson.encode(read(new File(directory(plan.root,false),plan.originalId()+".json")).getJSONObject("receipt")).equals(plan.completed))throw new IllegalStateException("retained recovery receipt differs");
    }
    static void requireCompletedRecord(EmbeddedDuplexOriginals.Record record,JSONObject response)throws Exception{
        if(!(record.retainedCleanupOwner instanceof Plan)||record.restored)throw new IllegalStateException("actual cold recovery Original owner absent");
        Plan plan=(Plan)record.retainedCleanupOwner;requireCompleted(plan);
        if(plan.original!=record||!plan.completed.equals(EmbeddedDuplexCanonicalJson.encode(new JSONObject(response.getString("receipt")))))throw new IllegalStateException("actual recovery response differs");
    }
    static boolean hasReceipt(File root,String id)throws Exception{
        id(id);File dir=directory(root,false);return Files.isRegularFile(new File(dir,id+".json").toPath(),LinkOption.NOFOLLOW_LINKS);
    }
    static JSONObject closedHandoff(File root,String id,String packageId)throws Exception{
        id(id);JSONObject stored=read(new File(directory(root,false),id+".json"));keys(stored,"schema","package_id","owner_link_json","receipt");
        if(!"rusty.quest.embedded_duplex.cold_pending_retirement_storage.v1".equals(stored.getString("schema"))||!packageId.equals(stored.getString("package_id")))throw new IllegalStateException("cold handoff package/storage differs");
        JSONObject receipt=stored.getJSONObject("receipt");validateReceipt(receipt);
        if(!id.equals(receipt.getString("recovery_original_id"))||!receipt.getString("owner_link_sha256").equals(EmbeddedDuplexProcessFence.digest(stored.getString("owner_link_json"))))throw new IllegalStateException("cold handoff source digest differs");
        JSONObject link=EmbeddedDuplexCanonicalJson.readOwned(stored.getString("owner_link_json").getBytes(StandardCharsets.UTF_8));
        if(!link.getString("$schema").equals(SCHEMA.equals(receipt.getString("$schema"))?LINK:LEGACY_LINK))throw new IllegalStateException("cold handoff owner link version differs");
        requireJournals(root,link);
        JSONObject row=EmbeddedDuplexOriginals.retainedRecoveryRow(root,id);
        if(!row.getString("operation").equals("recover_cold_pending"))throw new IllegalStateException("recovery Original operation differs");
        if(!row.getString("state").equals("succeeded"))throw new PublicationPending();
        if(!EmbeddedDuplexCanonicalJson.encode(new JSONObject(row.getJSONObject("response").getString("receipt"))).equals(EmbeddedDuplexCanonicalJson.encode(receipt)))throw new IllegalStateException("published recovery Original differs");
        return new JSONObject().put("whole_original_id",id).put("request_sha256",row.getString("request_sha256")).put("outcome_sha256",row.getString("outcome_sha256"))
            .put("physical_retirement_sha256",EmbeddedDuplexCanonicalJson.sha256(receipt)).put("prior_native_generation",receipt.getLong("prior_native_generation")).put("physical_retirement",receipt);
    }
    static JSONObject latestHandoff(File root,String packageId)throws Exception{
        File pointer=new File(directory(root,false),"latest.v1.json");if(!Files.exists(pointer.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        JSONObject value=read(pointer);keys(value,"schema","original_id");if(!"rusty.quest.embedded_duplex.cold_pending_latest.v1".equals(value.getString("schema")))throw new IllegalStateException("latest recovery pointer schema");
        try{return closedHandoff(root,value.getString("original_id"),packageId);}catch(PublicationPending unavailable){return null;}
    }
    static JSONObject latestCompletedHandoff(File root,String packageId)throws Exception{
        JSONObject cold=latestHandoff(root,packageId),whole=EmbeddedDuplexOriginals.latestWholeHandoff(root,packageId);
        if(cold==null)return whole;if(whole==null)return cold;
        return whole.getLong("prior_native_generation")>=cold.getLong("prior_native_generation")?whole:cold;
    }
    static String appDisposition(File root,EmbeddedDuplexProcessFence fence)throws Exception{
        return appDisposition(root,fence,new JSONObject(EmbeddedDuplexNative.inspectOperationalRun(fence)));
    }
    static String appDisposition(File root,EmbeddedDuplexProcessFence fence,JSONObject nativeStatus)throws Exception{
        if(fence.recoveryOnly()||publicationPending(root))return "recovery_required";
        if(fence.effectsPending())return "effects_pending";
        return restoredClearRequiresRecovery(root,fence,nativeStatus)?"recovery_required":"clear";
    }
    private static boolean restoredClearRequiresRecovery(File root,EmbeddedDuplexProcessFence fence,JSONObject nativeStatus)throws Exception{
        if(nativeStatus.getBoolean("globally_eligible")||nativeStatus.isNull("native_generation"))return false;
        if(nativeStatus.getLong("native_generation")<=0||fence.effectsPending())return false;
        File companion=new File(root,"embedded-duplex-replay");
        if(!EmbeddedDuplexProcessIncarnation.present(companion))return false;
        EmbeddedDuplexProcessIncarnation.RecoveryBinding last=EmbeddedDuplexProcessIncarnation.readForRecovery(companion,fence.acquisitionRecordForRecovery());
        if(Long.parseLong(fields(last.phaseRecord)[1])>=fence.generation())return false;
        EmbeddedDuplexProcessIncarnation.Identity current=EmbeddedDuplexProcessIncarnation.current(Process.myPid(),Process.myUid());
        if(last.binding.owner.binding().equals(current.binding()))return false;
        EmbeddedDuplexProcessIncarnation.retirementBasis(last.binding.owner,current);
        return true; // Native rejects the missing handoff; only a fresh cleanup-only decision can replace it.
    }
    private static final class PublicationPending extends IllegalStateException{PublicationPending(){super("actual recovery Original publication incomplete");}}
    private static boolean publicationPending(File root)throws Exception{
        File active=new File(directory(root,false),"active.v1.json");if(!Files.exists(active.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
        JSONObject pointer=read(active);keys(pointer,"schema","original_id");if(!"rusty.quest.embedded_duplex.cold_pending_active.v1".equals(pointer.getString("schema")))throw new IllegalStateException("active recovery pointer schema");
        String id=pointer.getString("original_id");id(id);
        if(!hasReceipt(root,id))return true;
        JSONObject row=EmbeddedDuplexOriginals.retainedRecoveryRow(root,id);
        return !"succeeded".equals(row.getString("state"));
    }
    private static void validateNative(JSONObject value,Plan p)throws Exception{
        keys(value,"$schema","recovery_original_id","run_id","app_process_generation","app_record_sha256","prior_native_generation","owner_link_sha256","disposition","native_counter_preserved","transaction_outcome","remote_outcome_resolved");
        if(!NATIVE.equals(value.getString("$schema"))||!p.originalId().equals(value.getString("recovery_original_id"))||!p.run.equals(value.getString("run_id"))
                ||value.getLong("app_process_generation")!=p.generation||!value.getString("app_record_sha256").equals(EmbeddedDuplexProcessFence.digest(p.record))
                ||value.getLong("prior_native_generation")!=p.counter||!value.getString("owner_link_sha256").equals(EmbeddedDuplexProcessFence.digest(p.link))
                ||!value.getString("disposition").equals("prior_process_local_retired")||!Boolean.TRUE.equals(value.get("native_counter_preserved"))
                ||!value.getString("transaction_outcome").equals("unchanged")||!Boolean.FALSE.equals(value.get("remote_outcome_resolved")))throw new IllegalStateException("actual Native retirement subject differs");
    }
    static void validateReceipt(JSONObject value)throws Exception{
        keys(value,"$schema","recovery_original_id","run_id","prior_run_id","prior_original_id","app_process_generation","app_record_sha256","app_clear_record_sha256","prior_native_generation","old_process_identity","current_process_identity","old_process_retirement","owner_link_sha256","native_retirement","physical_retirement","transaction_outcome","remote_outcome_resolved");
        if(!isReceiptSchema(value.getString("$schema"))||!"unchanged".equals(value.getString("transaction_outcome"))||!Boolean.FALSE.equals(value.get("remote_outcome_resolved")))throw new IllegalStateException("cold receipt disposition differs");
        id(value.getString("recovery_original_id"));id(value.getString("run_id"));hash(value.getString("app_record_sha256"));hash(value.getString("app_clear_record_sha256"));hash(value.getString("owner_link_sha256"));
        if(value.isNull("prior_run_id")!=value.isNull("prior_original_id"))throw new IllegalStateException("cold receipt prior subject differs");
        if(!value.isNull("prior_run_id")){id(value.getString("prior_run_id"));id(value.getString("prior_original_id"));}
        positive(value,"app_process_generation");positive(value,"prior_native_generation");
        if(!Set.of("not_running","pid_reused","boot_changed").contains(value.getString("old_process_retirement")))throw new IllegalStateException("cold receipt actor invalid");
        EmbeddedDuplexProcessIncarnation.Identity.read(value.getJSONObject("old_process_identity"));EmbeddedDuplexProcessIncarnation.Identity.read(value.getJSONObject("current_process_identity"));requirePhysical(value.getJSONObject("physical_retirement"),true);
        JSONObject n=value.getJSONObject("native_retirement");keys(n,"$schema","recovery_original_id","run_id","app_process_generation","app_record_sha256","prior_native_generation","owner_link_sha256","disposition","native_counter_preserved","transaction_outcome","remote_outcome_resolved");
        for(String key:new String[]{"recovery_original_id","run_id","app_process_generation","app_record_sha256","prior_native_generation","owner_link_sha256","transaction_outcome","remote_outcome_resolved"})if(!EmbeddedDuplexCanonicalJson.sameValue(value.get(key),n.get(key)))throw new IllegalStateException("cold receipt Native join differs");
        if(!(SCHEMA.equals(value.getString("$schema"))?NATIVE:LEGACY_NATIVE).equals(n.getString("$schema"))||!"prior_process_local_retired".equals(n.getString("disposition"))||!Boolean.TRUE.equals(n.get("native_counter_preserved")))throw new IllegalStateException("cold receipt Native disposition differs");
    }
    static boolean isReceiptSchema(String value){return SCHEMA.equals(value)||LEGACY_SCHEMA.equals(value);}
    private static JSONObject journalSnapshot(File root,String[] current,String[] prior)throws Exception{
        JSONObject value=new JSONObject().put("checkpoint_sha256",current[4]).put("evidence_sha256",current[5])
            .put("prior_checkpoint_sha256",prior[4]).put("prior_evidence_sha256",prior[5]);
        requireJournalBytes(root,value);return value;
    }
    private static void requireJournals(File root,JSONObject link)throws Exception{
        if(LEGACY_LINK.equals(link.getString("$schema")))return;
        if(!LINK.equals(link.getString("$schema")))throw new IllegalStateException("cold journal owner link schema");
        JSONObject value=link.getJSONObject("journal_snapshot");String[] prior=fields(link.getString("last_writable_record"));
        requireJournalBytes(root,value);
        if(!value.getString("prior_checkpoint_sha256").equals(prior[4])||!value.getString("prior_evidence_sha256").equals(prior[5]))throw new IllegalStateException("cold prior journal binding differs");
    }
    private static void requireJournalBytes(File root,JSONObject value)throws Exception{
        keys(value,"checkpoint_sha256","evidence_sha256","prior_checkpoint_sha256","prior_evidence_sha256");
        for(String key:new String[]{"checkpoint_sha256","evidence_sha256","prior_checkpoint_sha256","prior_evidence_sha256"})
            if(!(value.get(key) instanceof String)||!value.getString(key).matches("-|[0-9a-f]{64}"))throw new IllegalStateException("cold journal digest shape");
        String checkpoint=readJournal(root,"start-checkpoint.v1.json",256*1024);
        String evidence=readJournal(root,"recovery-evidence.v1.json",8*1024*1024);
        if(checkpoint!=null)EmbeddedDuplexStartJournal.phase(checkpoint);
        if(evidence!=null)EmbeddedDuplexRecoveryEvidenceJournal.validate(evidence);
        if(!value.getString("checkpoint_sha256").equals(EmbeddedDuplexProcessFence.digest(checkpoint))
                ||!value.getString("evidence_sha256").equals(EmbeddedDuplexProcessFence.digest(evidence)))throw new IllegalStateException("cold observed journal bytes changed");
    }
    private static String readJournal(File root,String name,int bound)throws Exception{
        File dir=new File(root,"embedded-duplex-replay"),file=new File(dir,name);
        if(!Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(dir.toPath()))throw new IllegalStateException("cold journal directory unsafe");
        for(String suffix:new String[]{".new",".bak"})if(Files.exists(new File(file+suffix).toPath(),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("cold journal publication Pending");
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file.toPath())||Files.size(file.toPath())<=0||Files.size(file.toPath())>bound)throw new IllegalStateException("cold journal file invalid");
        byte[] exact=Files.readAllBytes(file.toPath());if(exact.length==0||exact.length>bound)throw new IllegalStateException("cold journal byte bounds");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(exact)).toString();
    }
    private static void requirePhysical(JSONObject value,boolean fence)throws Exception{
        Set<String> expected=new HashSet<>(Arrays.asList(LOCAL));if(fence)expected.add("app_process_fence_cleanup");
        if(!fieldNames(value).equals(expected))throw new IllegalStateException("cold local physical closed fields differ");
        for(String key:expected)if(!"terminal".equals(value.get(key)))throw new IllegalStateException("cold local physical owner Pending");
    }
    private static void requireCurrentProcess(Plan p)throws Exception{
        if(!p.current.binding().equals(EmbeddedDuplexProcessIncarnation.current(Process.myPid(),Process.myUid()).binding())||p.fence.generation()!=p.generation)throw new IllegalStateException("current recovery actor changed");
    }
    private static String[] fields(String record)throws Exception{EmbeddedDuplexProcessFence.recordedPhase(record,"pending");return record.split("\n",-1);}
    private static File directory(File root,boolean create)throws Exception{
        File dir=new File(root,DIRECTORY);if(create&&!dir.exists()){Files.createDirectory(dir.toPath());Os.chmod(dir.getAbsolutePath(),0700);sync(root);}
        if(Files.exists(dir.toPath(),LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(dir.toPath())))throw new IllegalStateException("cold recovery directory unsafe");return dir;
    }
    private static JSONObject read(File file)throws Exception{
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file.toPath())||Files.size(file.toPath())>65536)throw new IllegalStateException("cold retained file unavailable");
        return EmbeddedDuplexCanonicalJson.readOwned(Files.readAllBytes(file.toPath()));
    }
    private static void writeNew(File file,JSONObject value)throws Exception{
        String exact=EmbeddedDuplexCanonicalJson.encode(value);if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)){if(!exact.equals(EmbeddedDuplexCanonicalJson.encode(read(file))))throw new IllegalStateException("cold retained receipt changed");return;}
        write(file,exact,false);
    }
    private static void replace(File file,JSONObject value)throws Exception{write(file,EmbeddedDuplexCanonicalJson.encode(value),true);}
    private static void write(File file,String exact,boolean replace)throws Exception{
        // A dead writer's staged pointer is retained under its own Original ID. A new
        // recovery never reuses that temporary path or erases the old UNKNOWN decision.
        String suffix=replace?"."+new JSONObject(exact).getString("original_id")+".new":".new";
        File tmp=new File(file+suffix);try(FileOutputStream out=new FileOutputStream(Files.createFile(tmp.toPath()).toFile())){Os.chmod(tmp.getAbsolutePath(),0600);out.write(exact.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(replace)Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);else Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(file.getParentFile());
    }
    private static void sync(File dir)throws Exception{java.io.FileDescriptor fd=Os.open(dir.getAbsolutePath(),OsConstants.O_RDONLY|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0);try{Os.fsync(fd);}finally{Os.close(fd);}}
    private static Set<String> fieldNames(JSONObject value){Set<String> names=new HashSet<>();value.keys().forEachRemaining(names::add);return names;}
    private static long positive(JSONObject value,String key)throws Exception{Object number=value.get(key);if(!(number instanceof Integer||number instanceof Long)||value.getLong(key)<=0)throw new IllegalStateException("cold recovery integer type/range");return value.getLong(key);}
    private static void keys(JSONObject value,String...keys)throws Exception{if(!fieldNames(value).equals(new HashSet<>(Arrays.asList(keys))))throw new IllegalStateException("cold recovery closed fields differ");}
    private static void hash(String value){if(!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("cold recovery digest");}
    private static void id(String value){if(!value.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("cold recovery identity");}
}
