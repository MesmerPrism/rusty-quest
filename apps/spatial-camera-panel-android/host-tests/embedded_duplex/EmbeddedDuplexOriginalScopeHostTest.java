package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Real fence/archive files and actual counter methods; Android/kernel facts modeled. */
public final class EmbeddedDuplexOriginalScopeHostTest {
    static final String PKG="io.example.scope",BOOT="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final EmbeddedDuplexProcessIncarnation.Identity OLD=new EmbeddedDuplexProcessIncarnation.Identity(BOOT,101,10001,100);
    static final EmbeddedDuplexProcessIncarnation.Identity NEXT=new EmbeddedDuplexProcessIncarnation.Identity(BOOT,202,10001,200);
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static EmbeddedDuplexProcessFence acquire(Path p,EmbeddedDuplexProcessIncarnation.Identity i,EmbeddedDuplexProcessIncarnation.Probe probe)throws Exception{
        return EmbeddedDuplexProcessFence.acquire(p.toFile(),null,null,()->{},i,probe);
    }
    static JSONObject authorization(String prior,String current)throws Exception{
        return new JSONObject().put("$schema","rusty.quest.embedded_duplex.abandonment_authorization.v1")
            .put("role_id","peer_a").put("package_id",PKG).put("component_id",PKG+"/io.example.Scope")
            .put("installed_apk_sha256","a".repeat(64)).put("signer_sha256","b".repeat(64))
            .put("physical_serial","MODELED").put("boot_id",BOOT).put("user_id",0).put("package_uid",10001)
            .put("old_pid",101).put("old_start_ticks",100).put("process_absence_evidence_sha256","c".repeat(64))
            .put("expected_predecessor_sha256",EmbeddedDuplexProcessFence.digest(prior))
            .put("expected_current_fence_sha256",EmbeddedDuplexProcessFence.digest(current)).put("authorize_abandonment",true);
    }
    static class Fixture implements AutoCloseable{
        Path root,originals;String prior,scope;EmbeddedDuplexProcessFence current;JSONObject row;
        Fixture(Path parent,boolean automatic)throws Exception{
            root=Files.createDirectory(parent.resolve("embedded-duplex-replay"));originals=Files.createDirectory(parent.resolve("operator-originals-v1"));
            try(EmbeddedDuplexProcessFence first=acquire(root,OLD,p->100L)){
                first.beforeRuntimeEffects(null,null);prior=first.retainedRecord();scope=EmbeddedDuplexProcessFence.digest(prior);
            }
            if(!automatic)Files.writeString(root.resolve("owner-dispatch.v1.json"),"{}"); // Mere presence intentionally retains recovery-only.
            current=acquire(root,NEXT,p->null);
            if(!automatic){
                JSONObject a=authorization(prior,current.retainedRecord());
                current.abandon(null,null,new EmbeddedDuplexAbandonmentRequest(a.toString()),(p,d)->{});
            }
            row=new JSONObject().put("original_id","1".repeat(32)).put("operation","prepare_peer_start")
                .put("state","unknown").put("effect_state","unknown").put("retention","owned")
                .put("actor_binding",new JSONObject().put("package_id",PKG).put("app_record_sha256",scope).put("native_generation",9))
                .put("outcome_sha256","d".repeat(64));
            Files.writeString(originals.resolve("1".repeat(32)+".json"),row.toString());
            EmbeddedDuplexProcessHost.instance=new EmbeddedDuplexProcessHost(current,PKG);
        }
        OriginalCounters counter(){return new OriginalCounters(originals.toFile());}
        public void close()throws Exception{current.close();}
    }
    static void expectedExcluded(Fixture f)throws Exception{
        byte[] before=Files.readAllBytes(f.originals.resolve("1".repeat(32)+".json"));
        check(f.counter().unresolvedExceptCurrent()==0,"exact disposed old UNKNOWN still blocks new scope");
        check(f.counter().unresolvedForRetirement(null)==0,"exact disposed old UNKNOWN still blocks retirement");
        check(f.counter().unrelated()==0,"exact disposed old UNKNOWN still blocks continuation");
        check(Arrays.equals(before,Files.readAllBytes(f.originals.resolve("1".repeat(32)+".json"))),"immutable Original row changed");
    }
    interface Call{void run()throws Exception;}
    static void refused(Call action)throws Exception{
        try{action.run();throw new AssertionError("inconsistent proof was accepted");}catch(IllegalStateException expected){}
    }
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args[0]);Files.createDirectory(output);String group=args[1];int tests=0;
        if(group.equals("committed")){
            try(Fixture f=new Fixture(Files.createDirectory(output.resolve("v1")),false)){
                check(Files.isRegularFile(f.root.resolve("abandoned-"+f.scope+"/committed-fence.raw")),"future v1 lacks actual commit witness");expectedExcluded(f);tests++;
                // A new process must retain the exclusion without replay or consumer ACK.
                f.current.close();f.current=acquire(f.root,new EmbeddedDuplexProcessIncarnation.Identity(BOOT,303,10001,300),p->null);
                EmbeddedDuplexProcessHost.instance=new EmbeddedDuplexProcessHost(f.current,PKG);expectedExcluded(f);tests++;
                Path witness=f.root.resolve("abandoned-"+f.scope+"/committed-fence.raw");Files.move(witness,witness.resolveSibling("held-witness.raw"));
                check(f.counter().unresolvedExceptCurrent()==1,"legacy/uncommitted archive was accepted");tests++;
            }
        }else if(group.equals("counter")){
            try(Fixture f=new Fixture(Files.createDirectory(output.resolve("counter")),false)){expectedExcluded(f);tests++;}
        }else if(group.equals("automatic")){
            try(Fixture f=new Fixture(Files.createDirectory(output.resolve("automatic")),true)){
                expectedExcluded(f);tests++;
                f.current.beforeRuntimeEffects(null,null);
                check(f.counter().unresolvedExceptCurrent()==0,"committed local scope lost exclusion when new effects entered");tests++;
                JSONObject current=new JSONObject(f.row.toString());current.getJSONObject("actor_binding").put("app_record_sha256",EmbeddedDuplexProcessFence.digest(f.current.retainedRecord()));
                Files.writeString(f.originals.resolve("2".repeat(32)+".json"),current.put("original_id","2".repeat(32)).toString());
                check(f.counter().unresolvedExceptCurrent()==1,"current actor UNKNOWN excluded");tests++;
                JSONObject foreign=new JSONObject(f.row.toString());foreign.getJSONObject("actor_binding").put("package_id","io.foreign.scope");
                Files.writeString(f.originals.resolve("3".repeat(32)+".json"),foreign.put("original_id","3".repeat(32)).toString());
                check(f.counter().unresolvedExceptCurrent()==2,"foreign package Original excluded");tests++;
            }
        }else if(group.equals("null")){
            try(Fixture f=new Fixture(Files.createDirectory(output.resolve("null")),false)){
                f.row.getJSONObject("actor_binding").put("app_record_sha256","f".repeat(64));
                Files.writeString(f.originals.resolve("1".repeat(32)+".json"),f.row.toString());OriginalCounters.ACTIVE=null;
                check(f.counter().unresolvedExceptCurrent()==1,"null current incorrectly excluded disk-only unresolved row");tests++;
                OriginalCounters c=f.counter();OriginalCounters.ACTIVE=new OriginalCounters.Record();OriginalCounters.ACTIVE.row=f.row;c.live.put(f.row.getString("original_id"),OriginalCounters.ACTIVE);
                check(c.unresolvedExceptCurrent()==0,"genuinely current Original was not excluded");tests++;
            }
        }else if(group.equals("faults")){
            try(Fixture f=new Fixture(Files.createDirectory(output.resolve("torn-archive")),false)){
                Files.writeString(f.root.resolve("abandoned-"+f.scope+"/committed-fence.raw"),"torn");
                refused(()->f.counter().unresolvedExceptCurrent());tests++;
            }
            try(Fixture f=new Fixture(Files.createDirectory(output.resolve("archive-binding")),false)){
                check(EmbeddedDuplexAbandonment.committedOriginalScope(f.root.toFile(),f.scope,10002,PKG)==null,"foreign UID archive accepted");tests++;
                check(EmbeddedDuplexAbandonment.committedOriginalScope(f.root.toFile(),f.scope,10001,"io.foreign.scope")==null,"foreign package archive accepted");tests++;
                Path receipt=f.root.resolve("abandoned-"+f.scope+"/receipt.json");JSONObject marker=new JSONObject(Files.readString(receipt));
                marker.getJSONObject("operator_authorization").put("expected_predecessor_sha256","e".repeat(64));Files.writeString(receipt,marker.toString());
                refused(()->f.counter().unresolvedExceptCurrent());tests++;
            }
            try(Fixture f=new Fixture(Files.createDirectory(output.resolve("torn-local")),true)){
                Path witness=f.root.resolve("process-incarnation-clear-"+f.scope+".raw");
                String exact=Files.readString(witness);Files.move(witness,witness.resolveSibling("held-clear.raw"));
                check(f.counter().unresolvedExceptCurrent()==1,"termination without commit witness excluded Original");tests++;
                Files.writeString(witness,"torn");refused(()->f.counter().unresolvedExceptCurrent());tests++;
                Files.writeString(witness,exact);expectedExcluded(f);tests++;
                String[] future=exact.split("\n",-1);future[1]="999";
                String body=String.join("\n",Arrays.copyOf(future,6))+"\n";
                Files.writeString(witness,body+EmbeddedDuplexProcessFence.digest(body)+"\n");
                check(f.counter().unresolvedExceptCurrent()==1,"future clear proof accepted by older current fence");tests++;
                Files.writeString(witness,exact);
                JSONObject term=new JSONObject(Files.readString(f.root.resolve("terminated-incarnation-"+f.scope+".json")));
                term.getJSONObject("original_owner").put("uid",10002);
                Files.writeString(f.root.resolve("terminated-incarnation-"+f.scope+".json"),term.toString());
                check(f.counter().unresolvedExceptCurrent()==1,"foreign UID proof excluded Original");tests++;
            }
            for(boolean live:new boolean[]{false,true}){
                Path p=Files.createDirectory(output.resolve(live?"live":"remote"));String prior;
                try(EmbeddedDuplexProcessFence old=acquire(p,OLD,x->100L)){old.beforeRuntimeEffects(null,null);prior=old.retainedRecord();}
                if(!live)Files.writeString(p.resolve("owner-dispatch.v1.json"),"{}");
                try(EmbeddedDuplexProcessFence next=acquire(p,NEXT,x->live?100L:null)){
                    check(next.recoveryOnly(),"live or remote-uncertain source became fresh");
                    check(!Files.exists(p.resolve("process-incarnation-clear-"+EmbeddedDuplexProcessFence.digest(prior)+".raw")),"unpermitted local clear committed");tests++;
                }
            }
        }else if(group.equals("newer")){
            for(boolean known:new boolean[]{false,true}){
                Path a=Files.createDirectory(output.resolve(known?"known-a":"historical-a")),b=Files.createDirectory(output.resolve(known?"known-b":"historical-b"));
                JSONObject pa=known?EmbeddedDuplexKnownBirthAbandonmentHostTest.peer(a,"A",10001):EmbeddedDuplexHistoricalAbandonmentHostTest.peer(a,"A",10001);
                JSONObject pb=known?EmbeddedDuplexKnownBirthAbandonmentHostTest.peer(b,"B",10002):EmbeddedDuplexHistoricalAbandonmentHostTest.peer(b,"B",10002);
                try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(a.toFile(),null,null,()->{})){
                    JSONObject auth=known?EmbeddedDuplexKnownBirthAbandonmentHostTest.auth(current,pa,pb):EmbeddedDuplexHistoricalAbandonmentHostTest.auth(current,pa,pb);
                    current.abandon(null,null,new EmbeddedDuplexAbandonmentRequest(auth.toString()),(p,d)->{});
                    String scope=auth.getString("expected_predecessor_sha256");String[] proof=EmbeddedDuplexAbandonment.committedOriginalScope(a.toFile(),scope,10001,"test.product.app");
                    check(proof!=null&&EmbeddedDuplexProcessFence.digest(proof[0]).equals(scope),"newer authentic committed archive rejected");tests++;
                }
            }
            Path a=Files.createDirectory(output.resolve("post-media-a")),b=Files.createDirectory(output.resolve("post-media-b"));
            JSONObject pa=EmbeddedDuplexPostMediaAbandonmentHostTest.peer(a,"A",10001,"checkpoint","evidence",EmbeddedDuplexPostMediaAbandonmentHostTest.MAP);
            JSONObject pb=EmbeddedDuplexPostMediaAbandonmentHostTest.peer(b,"B",10002,null,null,null);
            Path originals=Files.createDirectory(output.resolve("operator-originals-v1"));String id="4".repeat(32);
            JSONObject oldRow=new JSONObject().put("schema","rusty.quest.embedded_duplex.operator_original.v1")
                .put("original_id",id).put("state","unknown").put("effect_state","unknown").put("retention","owned")
                .put("actor_binding",new JSONObject().put("package_id","test.product.app").put("app_record_sha256",pa.getString("original_fence_sha256")))
                .put("outcome_sha256","d".repeat(64)).put("consumer_receipt_sha256",JSONObject.NULL);
            Path row=originals.resolve(id+".json");Files.writeString(row,oldRow.toString());byte[] exact=Files.readAllBytes(row);
            try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(a.toFile(),"checkpoint","evidence",()->{},
                    new EmbeddedDuplexProcessIncarnation.Identity(EmbeddedDuplexPostMediaAbandonmentHostTest.BOOT,900,10001,12345),p->null)){
                EmbeddedDuplexProcessHost.instance=new EmbeddedDuplexProcessHost(current,"test.product.app");
                OriginalCounters counters=new OriginalCounters(originals.toFile());
                refused(()->counters.unresolvedExceptCurrent()); // Recovery-only denies before admission accounting.
                current.abandon("checkpoint","evidence",new EmbeddedDuplexAbandonmentRequest(EmbeddedDuplexPostMediaAbandonmentHostTest.auth(current,pa,pb).toString()),(p,d)->{});
                check(counters.unresolvedExceptCurrent()==0&&counters.unresolvedForRetirement(null)==0&&counters.unrelated()==0,"committed post-media scope still blocks counters");tests++;
                Bundle queried=counters.query(id,"5".repeat(32));JSONObject observed=new JSONObject(queried.getString("observation_json"));
                check("unknown".equals(queried.getString("status"))&&"unknown".equals(observed.getString("state"))&&"unknown".equals(observed.getString("effect_state")),"disposed Original query lost UNKNOWN");
                check("owned".equals(observed.getString("retention"))&&observed.isNull("consumer_receipt_sha256")&&"d".repeat(64).equals(observed.getString("outcome_sha256")),"query changed outcome/retention/ACK");
                check(Arrays.equals(exact,Files.readAllBytes(row)),"query or disposition mutated immutable Original");tests++;
            }
        }else if(group.equals("mixed")){
            Path a=Files.createDirectory(output.resolve("mixed-a")),b=Files.createDirectory(output.resolve("mixed-b"));
            JSONObject pa=EmbeddedDuplexPostMediaAbandonmentHostTest.peer(a,"A",10001,"checkpoint","evidence",EmbeddedDuplexPostMediaAbandonmentHostTest.MAP);
            JSONObject pb=EmbeddedDuplexMixedPostMediaAbandonmentHostTest.clearPeer(b,"B",10002,null,null,null);
            Path originals=Files.createDirectory(output.resolve("operator-originals-v1"));String id="4".repeat(32);
            JSONObject oldRow=new JSONObject().put("schema","rusty.quest.embedded_duplex.operator_original.v1")
                .put("original_id",id).put("state","unknown").put("effect_state","unknown").put("retention","owned")
                .put("actor_binding",new JSONObject().put("package_id","test.product.app").put("app_record_sha256",pa.getString("original_fence_sha256")))
                .put("outcome_sha256","d".repeat(64)).put("consumer_receipt_sha256",JSONObject.NULL);
            Path row=originals.resolve(id+".json");Files.writeString(row,oldRow.toString());byte[] exact=Files.readAllBytes(row);
            try(EmbeddedDuplexProcessFence current=EmbeddedDuplexProcessFence.acquire(a.toFile(),"checkpoint","evidence",()->{},
                    new EmbeddedDuplexProcessIncarnation.Identity(EmbeddedDuplexPostMediaAbandonmentHostTest.BOOT,900,10001,12345),p->null)){
                EmbeddedDuplexProcessHost.instance=new EmbeddedDuplexProcessHost(current,"test.product.app");
                OriginalCounters counters=new OriginalCounters(originals.toFile());
                refused(()->counters.unresolvedExceptCurrent()); // Recovery-only denies before admission accounting.
                JSONObject v8auth=EmbeddedDuplexMixedPostMediaAbandonmentHostTest.auth(current,pa,pb,"peer_a");
                current.abandon("checkpoint","evidence",new EmbeddedDuplexAbandonmentRequest(v8auth.toString()),(p,d)->{});
                check(counters.unresolvedExceptCurrent()==0&&counters.unresolvedForRetirement(null)==0&&counters.unrelated()==0,"committed post-media scope still blocks counters");tests++;
                Bundle queried=counters.query(id,"5".repeat(32));JSONObject observed=new JSONObject(queried.getString("observation_json"));
                check("unknown".equals(queried.getString("status"))&&"unknown".equals(observed.getString("state"))&&"unknown".equals(observed.getString("effect_state")),"disposed Original query lost UNKNOWN");
                check("owned".equals(observed.getString("retention"))&&observed.isNull("consumer_receipt_sha256")&&"d".repeat(64).equals(observed.getString("outcome_sha256")),"query changed outcome/retention/ACK");
                check(Arrays.equals(exact,Files.readAllBytes(row)),"query or disposition mutated immutable Original");tests++;
                JSONObject typed=new JSONObject().put("authorization_json",v8auth.toString());
                int[] factories={0};String newId="6".repeat(32),challenge="7".repeat(32);
                java.util.function.Supplier<java.util.concurrent.CompletableFuture<Bundle>> factory=()->{factories[0]++;return new java.util.concurrent.CompletableFuture<>();};
                counters.begin(newId,"abandon_retained_scope",challenge,typed,factory);
                counters.begin(newId,"abandon_retained_scope",challenge,typed,factory);
                check(factories[0]==1,"same Original replayed factory");tests++;
                JSONObject changed=new JSONObject(typed.toString());JSONObject auth=new JSONObject(changed.getString("authorization_json"));
                auth.put("process_absence_evidence_sha256","8".repeat(64));changed.put("authorization_json",auth.toString());
                refused(()->counters.begin(newId,"abandon_retained_scope",challenge,changed,factory));tests++;
                check(factories[0]==1&&Arrays.equals(exact,Files.readAllBytes(row)),"changed authorization replayed or mutated old Original");tests++;

            }
        }else throw new AssertionError("unknown test group");
        System.out.println("PASS actual scope/counter group="+group+" controls="+tests+" modeled kernel/Android facts; no devices");
    }
}
