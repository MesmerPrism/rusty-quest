package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import org.json.JSONObject;

/** Private local-resource lifetime evidence. Remote transactions keep their original owners. */
final class EmbeddedDuplexProcessIncarnation {
    private static final String NAME="process-incarnation.v1.json";
    private static final String SCHEMA="rusty.quest.embedded_duplex.process_incarnation.v1";
    interface Probe { Long startTicks(int pid) throws Exception; } // null means authenticated PID absence, never unreadable.
    static final class Identity {
        final String boot;final int pid,uid;final long ticks;
        Identity(String boot,int pid,int uid,long ticks){
            if(boot==null || !boot.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || pid<=0 || uid<10000 || ticks<=0)
                throw new IllegalArgumentException("invalid process incarnation");
            this.boot=boot;this.pid=pid;this.uid=uid;this.ticks=ticks;
        }
        JSONObject json()throws Exception{return new JSONObject().put("boot_id",boot).put("pid",pid).put("uid",uid).put("start_ticks",ticks);}
        static Identity read(JSONObject value)throws Exception{
            if(!keys(value).equals(Set.of("boot_id","pid","uid","start_ticks")))throw new IllegalStateException("incarnation fields");
            for(String key:new String[]{"pid","uid","start_ticks"}){
                Object number=value.get(key);if(!(number instanceof Integer || number instanceof Long))throw new IllegalStateException("incarnation integer type");
            }
            if(value.getLong("pid")>Integer.MAX_VALUE || value.getLong("uid")>Integer.MAX_VALUE)throw new IllegalStateException("incarnation integer range");
            return new Identity(value.getString("boot_id"),value.getInt("pid"),value.getInt("uid"),value.getLong("start_ticks"));
        }
        String binding(){return boot+"\n"+pid+"\n"+uid+"\n"+ticks+"\n";}
    }
    static Identity current(int pid,int uid)throws Exception{
        String first=boot();Long ticks=kernelStartTicks(pid);String last=boot();
        if(ticks==null || !first.equals(last))throw new IllegalStateException("current process incarnation unavailable");
        return new Identity(first,pid,uid,ticks);
    }
    private static String boot()throws Exception{
        return new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),StandardCharsets.US_ASCII).trim();
    }
    static Long kernelStartTicks(int pid)throws Exception{
        if(pid<=0)throw new IllegalArgumentException("invalid PID");
        String stat;
        try{stat=new String(Files.readAllBytes(Paths.get("/proc",Integer.toString(pid),"stat")),StandardCharsets.US_ASCII);}
        catch(NoSuchFileException absent){return null;}
        int first=stat.indexOf('('),end=stat.lastIndexOf(')');
        if(stat.length()>16384 || first<1 || end<=first || !Integer.toString(pid).equals(stat.substring(0,first).trim()))
            throw new IllegalStateException("process stat identity unavailable");
        String[] fields=stat.substring(end+1).trim().split("\\s+");
        if(fields.length<20)throw new IllegalStateException("process stat birth unavailable");
        long ticks=Long.parseLong(fields[19]);if(ticks<=0)throw new IllegalStateException("process stat birth invalid");return ticks;
    }
    static final class Binding {
        final Identity owner;final String originalRecord;String currentSha,terminationReceiptSha="-";boolean terminated;
        Binding(Identity owner,String record)throws Exception{this.owner=owner;originalRecord=record;currentSha=EmbeddedDuplexProcessFence.digest(record);}
        String checksum()throws Exception{return EmbeddedDuplexProcessFence.digest(owner.binding()+originalRecord+currentSha+"\n"+terminated+"\n"+terminationReceiptSha+"\nprocess_local_resources\n");}
        JSONObject json()throws Exception{return new JSONObject().put("$schema",SCHEMA).put("original_owner",owner.json())
            .put("effect_category","process_local_resources").put("original_fence_record",originalRecord)
            .put("current_fence_sha256",currentSha).put("local_resources_terminated",terminated)
            .put("termination_receipt_sha256",terminationReceiptSha).put("sha256",checksum());}
    }
    /** Missing/old/interrupted companions never turn unknown effects into a new ownership claim. */
    static Binding load(File directory,String record){
        try{
            Path path=new File(directory,NAME).toPath();
            if(Files.exists(Paths.get(path+".new"),LinkOption.NOFOLLOW_LINKS))return null;
            JSONObject value=new JSONObject(read(path,4096));
            if(!keys(value).equals(Set.of("$schema","original_owner","effect_category","original_fence_record",
                    "current_fence_sha256","local_resources_terminated","termination_receipt_sha256","sha256")) || !SCHEMA.equals(value.getString("$schema"))
                    || !"process_local_resources".equals(value.getString("effect_category")))return null;
            Binding binding=new Binding(Identity.read(value.getJSONObject("original_owner")),value.getString("original_fence_record"));
            if(!(value.get("local_resources_terminated") instanceof Boolean))return null;
            binding.currentSha=value.getString("current_fence_sha256");binding.terminated=value.getBoolean("local_resources_terminated");
            binding.terminationReceiptSha=value.getString("termination_receipt_sha256");
            if(!binding.terminationReceiptSha.matches(binding.terminated?"[0-9a-f]{64}":"-"))return null;
            if(!binding.currentSha.equals(EmbeddedDuplexProcessFence.digest(record)) || !binding.checksum().equals(value.getString("sha256")))return null;
            return binding;
        }catch(Exception unknown){return null;}
    }
    static boolean observeTermination(File directory,Binding binding,Identity current,Probe probe,
            EmbeddedDuplexProcessFence.DirectorySync sync)throws Exception{
        if(binding.owner.uid!=current.uid)return false;
        Path path=new File(directory,"terminated-incarnation-"+EmbeddedDuplexProcessFence.digest(binding.originalRecord)+".json").toPath();
        if(binding.terminated){
            try{return binding.terminationReceiptSha.equals(EmbeddedDuplexProcessFence.digest(read(path,4096)));}
            catch(Exception missingOrChanged){return false;}
        }
        String reason;
        if(!binding.owner.boot.equals(current.boot))reason="boot_changed";
        else{
            Long birth;
            try{birth=probe.startTicks(binding.owner.pid);}catch(Exception unavailable){return false;}
            if(birth!=null && birth==binding.owner.ticks)return false;
            if(birth!=null && birth<=0)return false;
            reason=birth==null?"process_absent":"pid_reused";
        }
        JSONObject receipt=new JSONObject().put("$schema","rusty.quest.embedded_duplex.process_termination.v1")
            .put("original_owner",binding.owner.json()).put("observed_by",current.json())
            .put("original_fence_record",binding.originalRecord).put("termination_reason",reason)
            .put("effect_category","process_local_resources").put("local_handles_disposition","terminated_by_process_or_boot")
            .put("graceful_cleanup_confirmed",false).put("remote_authority_transferred",false);
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){
            // A crash may leave a durable receipt before the primary fence commit. Verify its original scope.
            JSONObject prior=new JSONObject(read(path,4096));
            if(!binding.originalRecord.equals(prior.getString("original_fence_record"))
                    || !binding.owner.binding().equals(Identity.read(prior.getJSONObject("original_owner")).binding())
                    || prior.getBoolean("graceful_cleanup_confirmed") || prior.getBoolean("remote_authority_transferred")
                    || !"rusty.quest.embedded_duplex.process_termination.v1".equals(prior.getString("$schema"))
                    || !"process_local_resources".equals(prior.getString("effect_category"))
                    || !"terminated_by_process_or_boot".equals(prior.getString("local_handles_disposition"))
                    || !Set.of("boot_changed","process_absent","pid_reused").contains(prior.getString("termination_reason")))
                throw new IllegalStateException("termination receipt scope differs");
        }else{writeNew(path,receipt.toString());sync.sync();}
        binding.terminationReceiptSha=EmbeddedDuplexProcessFence.digest(read(path,4096));binding.terminated=true;return true;
    }
    /** Presence, including AtomicFile sidecars, conservatively retains remote reconciliation. */
    static void commitClear(File directory,Binding binding,String clear,EmbeddedDuplexProcessFence.DirectorySync sync)throws Exception{
        if(binding==null || !binding.terminated)throw new IllegalStateException("local termination not observed");
        EmbeddedDuplexOriginalScopeDisposition.clearAfter(binding.originalRecord,clear);
        String stem="terminated-incarnation-"+EmbeddedDuplexProcessFence.digest(binding.originalRecord);
        if(!binding.terminationReceiptSha.equals(EmbeddedDuplexProcessFence.digest(read(new File(directory,stem+".json").toPath(),4096))))
            throw new IllegalStateException("local termination receipt changed before clear commit");
        Path witness=new File(directory,"process-incarnation-clear-"+EmbeddedDuplexProcessFence.digest(binding.originalRecord)+".raw").toPath();
        if(Files.exists(witness,LinkOption.NOFOLLOW_LINKS)){
            EmbeddedDuplexOriginalScopeDisposition.clearAfter(binding.originalRecord,read(witness,512));return;
        }
        writeNew(witness,clear);sync.sync();
    }
    /** Local accounting may follow only a clear transition already permitted by the fence owner. */
    static String[] committedOriginalScope(File directory,String scope,int uid)throws Exception{
        String stem="terminated-incarnation-"+scope;
        Path receipt=new File(directory,stem+".json").toPath(),witness=new File(directory,"process-incarnation-clear-"+scope+".raw").toPath();
        if(!Files.exists(receipt,LinkOption.NOFOLLOW_LINKS)||!Files.exists(witness,LinkOption.NOFOLLOW_LINKS))return null;
        JSONObject value=new JSONObject(read(receipt,4096));
        if(!keys(value).equals(Set.of("$schema","original_owner","observed_by","original_fence_record","termination_reason",
                "effect_category","local_handles_disposition","graceful_cleanup_confirmed","remote_authority_transferred"))
                || !"rusty.quest.embedded_duplex.process_termination.v1".equals(value.getString("$schema"))
                || !"process_local_resources".equals(value.getString("effect_category"))
                || !"terminated_by_process_or_boot".equals(value.getString("local_handles_disposition"))
                || !Boolean.FALSE.equals(value.get("graceful_cleanup_confirmed")) || !Boolean.FALSE.equals(value.get("remote_authority_transferred"))
                || !Set.of("boot_changed","process_absent","pid_reused").contains(value.getString("termination_reason")))
            throw new IllegalStateException("local scope termination receipt invalid");
        Identity owner=Identity.read(value.getJSONObject("original_owner")),observer=Identity.read(value.getJSONObject("observed_by"));
        if(owner.uid!=observer.uid || owner.uid!=uid)return null;
        if("boot_changed".equals(value.getString("termination_reason"))==owner.boot.equals(observer.boot))
            throw new IllegalStateException("local scope termination boot differs");
        if(owner.boot.equals(observer.boot) && owner.pid==observer.pid && owner.ticks==observer.ticks)
            throw new IllegalStateException("local scope termination observed the same live actor");
        String original=value.getString("original_fence_record");
        if(!scope.equals(EmbeddedDuplexProcessFence.digest(original)))throw new IllegalStateException("local termination scope differs");
        String clear=read(witness,512);EmbeddedDuplexOriginalScopeDisposition.clearAfter(original,clear);
        return new String[]{original,clear};
    }
    static boolean remoteRecordsPresent(File directory,String checkpoint,String evidence){
        if(checkpoint!=null || evidence!=null)return true;
        for(String name:new String[]{"start-checkpoint.v1.json","recovery-evidence.v1.json",
                "owner-dispatch.v1.json","product-activation.v1.json","retained-cleanup.v2.json","retained-cleanup-preparations.v1.json"})
            for(String suffix:new String[]{"",".bak",".new"})
                if(Files.exists(new File(directory,name+suffix).toPath(),LinkOption.NOFOLLOW_LINKS))return true;
        for(String name:new String[]{"embedded-duplex-fresh-closure-v1","embedded-duplex-partial-construction-v1",
                "embedded-duplex-unknown-root-custody-v1","embedded-duplex-fresh-handoffs-v1","embedded-duplex-prepared-lifetimes-v1"}){
            Path retained=new File(directory.getParentFile(),name).toPath();
            if(!Files.exists(retained,LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isDirectory(retained,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(retained))return true;
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(retained)){
                if(entries.iterator().hasNext())return true;
            }catch(Exception unavailable){return true;}
        }
        return false;
    }
    static void persist(File directory,Binding binding,String record,EmbeddedDuplexProcessFence.DirectorySync sync)throws Exception{
        binding.currentSha=EmbeddedDuplexProcessFence.digest(record);
        Path path=new File(directory,NAME).toPath(),temporary=Paths.get(path+".new");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS) && (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)))
            throw new IllegalStateException("incarnation companion path invalid");
        writeNew(temporary,binding.json().toString());
        Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync.sync();
    }
    private static String read(Path path,int bound)throws Exception{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path)>bound)
            throw new IllegalStateException("incarnation record unavailable");
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }
    private static Set<String> keys(JSONObject value){
        Set<String> result=new java.util.HashSet<>();for(java.util.Iterator<String> i=value.keys();i.hasNext();)result.add(i.next());return result;
    }
    private static void writeNew(Path path,String exact)throws Exception{
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer bytes=ByteBuffer.wrap(exact.getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())file.write(bytes);file.force(true);
        }
    }
}
