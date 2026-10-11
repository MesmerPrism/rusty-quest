package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Nonaccepting disposition only. Exact old bytes remain archived; no old authority is restored. */
final class EmbeddedDuplexAbandonment {
    interface Protection { void apply(File path,boolean directory) throws Exception; }
    private static final Map<String, JSONObject> ACTIVE = new HashMap<>();
    private static final String MARKER = "abandoned-scope.v1.json";
    private static final String SCHEMA = "rusty.quest.embedded_duplex.abandoned_scope.v1";

    static synchronized String visible(File directory, String exact, boolean checkpoint) throws Exception {
        JSONObject marker = ACTIVE.get(directory.getCanonicalPath());
        if (marker == null) return exact;
        verifyArchive(directory, marker);
        if(exact==null) return null;
        return EmbeddedDuplexProcessFence.digest(exact).equals(marker.getString(checkpoint
                ? "checkpoint_sha256" : "evidence_sha256")) ? null : exact;
    }
    /** Read-only projection of exactly archived historical maps, never native generation state. */
    static synchronized void requireReplayRead(File directory,String name) throws Exception {
        if(!java.util.Set.of("owner-dispatch.v1.json","product-activation.v1.json","retained-cleanup-preparations.v1.json").contains(name)) return;
        JSONObject marker=ACTIVE.get(directory.getCanonicalPath());
        if(marker==null || !EmbeddedDuplexHistoricalAbandonment.SCHEMA.equals(marker.getJSONObject("operator_authorization").getString("$schema"))) return;
        verifyArchive(directory,marker);
        File archive=new File(directory,"abandoned-"+marker.getString("scope_sha256"));
        if(!Files.exists(new File(archive,"committed-fence.raw").toPath(),LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("historical replay disposition uncommitted");
        for(String suffix:java.util.List.of(".new",".bak"))
            if(Files.exists(new File(directory,name+suffix).toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("historical replay sidecar unresolved");
    }
    static synchronized String visibleReplay(File directory,String name,String exact) throws Exception {
        requireReplayRead(directory,name);
        if(!java.util.Set.of("owner-dispatch.v1.json","product-activation.v1.json","retained-cleanup-preparations.v1.json").contains(name)) return exact;
        JSONObject marker=ACTIVE.get(directory.getCanonicalPath());
        if(marker==null || !EmbeddedDuplexHistoricalAbandonment.SCHEMA.equals(marker.getJSONObject("operator_authorization").getString("$schema"))) return exact;
        verifyArchive(directory,marker);
        File archive=new File(directory,"abandoned-"+marker.getString("scope_sha256"));
        if(!Files.exists(new File(archive,"committed-fence.raw").toPath(),LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("historical replay disposition uncommitted");
        JSONObject auth=marker.getJSONObject("operator_authorization");
        String sha=auth.getJSONObject("historical_peers").getJSONObject(auth.getString("role_id"))
                .getJSONObject("preserved_records").getString(name);
        if(!EmbeddedDuplexProcessFence.digest(exact).equals(sha)) return exact;
        return name.equals("retained-cleanup-preparations.v1.json") ? "{\"originals\":{},\"prepared\":{}}"
                : "{\"pending_request_sha256\":{},\"terminal\":{}}";
    }

    /** Called with the already-held app channel record, never another descriptor for its inode. */
    static synchronized boolean restore(File directory, String record, String checkpoint, String evidence) throws Exception {
        ACTIVE.remove(directory.getCanonicalPath());
        File path = new File(directory, MARKER);
        if (!Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        JSONObject marker = readMarker(path);
        verifyArchive(directory, marker);
        String[] fields = record.split("\n", -1);
        if(EmbeddedDuplexHistoricalAbandonment.SCHEMA.equals(marker.getJSONObject("operator_authorization").getString("$schema"))
                && !Files.exists(new File(directory,"abandoned-"+marker.getString("scope_sha256")+"/committed-fence.raw").toPath(),LinkOption.NOFOLLOW_LINKS)) {
            if(fields.length==8 && "clear".equals(fields[3])) throw new IllegalStateException("historical clear fence commit witness unavailable");
            return false;
        }
        // A crash before the fence commit leaves the original pending scope active.
        if (fields.length != 8 || Long.parseLong(fields[1]) < marker.getLong("successor_generation")
                || !"-".equals(fields[4]) || !"-".equals(fields[5])
                || (checkpoint!=null && !EmbeddedDuplexProcessFence.digest(checkpoint).equals(marker.getString("checkpoint_sha256")))
                || (evidence!=null && !EmbeddedDuplexProcessFence.digest(evidence).equals(marker.getString("evidence_sha256")))) return false;
        retireActiveCopies(directory,marker);
        ACTIVE.put(directory.getCanonicalPath(), marker);
        return true;
    }

    static JSONObject prepare(File directory, String priorRecord, String currentRecord,
            String checkpoint, String evidence, long generation, JSONObject authorization,
            EmbeddedDuplexProcessFence.DirectorySync sync,Protection protection) throws Exception {
        if(protection==null) throw new IllegalStateException("abandonment protection unavailable");
        requireActiveCopies(directory,checkpoint,evidence);
        String scope = EmbeddedDuplexProcessFence.digest(priorRecord);
        if (!scope.equals(authorization.getString("expected_predecessor_sha256")))
            throw new IllegalStateException("abandonment predecessor differs");
        File archive = new File(directory, "abandoned-" + scope);
        if (!archive.mkdir()) throw new IllegalStateException("abandonment archive already exists");
        protection.apply(archive,true);
        writeNew(new File(archive,"prior-fence.raw"),priorRecord,protection);
        writeNew(new File(archive,"recovery-fence.raw"),currentRecord,protection);
        if (checkpoint != null) writeNew(new File(archive,"checkpoint.raw"),checkpoint,protection);
        if (evidence != null) writeNew(new File(archive,"evidence.raw"),evidence,protection);
        if(EmbeddedDuplexHistoricalAbandonment.SCHEMA.equals(authorization.getString("$schema"))) {
            JSONObject digests=authorization.getJSONObject("historical_peers")
                    .getJSONObject(authorization.getString("role_id")).getJSONObject("preserved_records");
            for(String name:EmbeddedDuplexHistoricalAbandonment.RECORDS) {
                for(String suffix:java.util.List.of(".new",".bak"))
                    if(Files.exists(new File(directory,name+suffix).toPath(),LinkOption.NOFOLLOW_LINKS))
                        throw new IllegalStateException("historical record sidecar unresolved");
                String exact=read(new File(directory,name),8*1024*1024);
                if(!EmbeddedDuplexProcessFence.digest(exact).equals(digests.getString(name)))
                    throw new IllegalStateException("historical durable record differs");
                writeNew(new File(archive,"preserved-"+name),exact,protection);
                if(!EmbeddedDuplexProcessFence.digest(read(new File(directory,name),8*1024*1024)).equals(digests.getString(name)))
                    throw new IllegalStateException("historical durable record changed while archiving");
            }
        }
        JSONObject receipt = new JSONObject().put("$schema",SCHEMA)
                .put("disposition","old_scope_abandoned_cleanup_unknown")
                .put("old_cleanup_confirmed",false).put("old_authority_transferred",false)
                .put("scope_sha256",scope).put("recovery_fence_sha256",EmbeddedDuplexProcessFence.digest(currentRecord))
                .put("checkpoint_sha256",EmbeddedDuplexProcessFence.digest(checkpoint))
                .put("evidence_sha256",EmbeddedDuplexProcessFence.digest(evidence))
                .put("successor_generation",generation).put("operator_authorization",authorization);
        writeNew(new File(archive,"receipt.json"),receipt.toString(),protection);
        protection.apply(archive,true); // App adapter chmods and fsyncs the archive directory after its entries.
        sync.sync();
        // The fixed marker points only to an exact, flushed immutable archive.
        File marker = new File(directory,MARKER);
        File temporary = new File(directory,MARKER+"."+scope+".new");
        writeNew(temporary,receipt.toString(),protection);
        Files.move(temporary.toPath(),marker.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        sync.sync();
        return receipt;
    }

    static synchronized void committed(File directory, JSONObject receipt,String clearRecord,Protection protection) throws Exception {
        verifyArchive(directory,receipt);
        if(EmbeddedDuplexHistoricalAbandonment.SCHEMA.equals(receipt.getJSONObject("operator_authorization").getString("$schema"))) {
            File archive=new File(directory,"abandoned-"+receipt.getString("scope_sha256"));
            if(!clearRecord.equals(expectedClear(archive))) throw new IllegalStateException("historical commit fence differs");
            writeNew(new File(archive,"committed-fence.raw"),clearRecord,protection);
            protection.apply(archive,true); // Android adapter chmods and fsyncs this directory.
            verifyArchive(directory,receipt);
        }
        retireActiveCopies(directory,receipt);
        ACTIVE.put(directory.getCanonicalPath(),receipt);
    }
    private static void requireActiveCopies(File directory,String checkpoint,String evidence) throws Exception {
        String[] names={"start-checkpoint.v1.json","recovery-evidence.v1.json"};
        String[] values={checkpoint,evidence};
        for(int i=0;i<names.length;i++) {
            File path=new File(directory,names[i]);
            if(Files.exists(new File(path.getPath()+".new").toPath(),LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(new File(path.getPath()+".bak").toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("unarchived journal sidecar unavailable for abandonment");
            if(values[i]==null) {
                if(Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("active journal differs");
            } else if(!values[i].equals(read(path,i==0?256*1024:8*1024*1024)))
                throw new IllegalStateException("active journal changed before archival");
        }
    }
    /** Retire only exact archived duplicate active files, never any arbitrary state or archive. */
    private static void retireActiveCopies(File directory,JSONObject receipt) throws Exception {
        String[] names={"start-checkpoint.v1.json","recovery-evidence.v1.json"};
        String[] hashes={receipt.getString("checkpoint_sha256"),receipt.getString("evidence_sha256")};
        for(int i=0;i<names.length;i++) {
            File path=new File(directory,names[i]);
            if(Files.exists(new File(path.getPath()+".new").toPath(),LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(new File(path.getPath()+".bak").toPath(),LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("unarchived journal sidecar blocks retirement");
            if(Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                if(!hashes[i].equals(EmbeddedDuplexProcessFence.digest(read(path,i==0?256*1024:8*1024*1024))))
                    throw new IllegalStateException("active duplicate differs from archive");
                Files.delete(path.toPath());
            }
        }
    }
    private static JSONObject readMarker(File path) throws Exception {
        String exact = read(path, 32*1024);
        JSONObject value = new JSONObject(exact);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for(java.util.Iterator<String> iterator=value.keys();iterator.hasNext();) keys.add(iterator.next());
        if (!keys.equals(java.util.Set.of("$schema","disposition","old_cleanup_confirmed",
                "old_authority_transferred","scope_sha256","recovery_fence_sha256",
                "checkpoint_sha256","evidence_sha256","successor_generation","operator_authorization"))
                || !SCHEMA.equals(value.getString("$schema"))
                || !"old_scope_abandoned_cleanup_unknown".equals(value.getString("disposition"))
                || !Boolean.FALSE.equals(value.get("old_cleanup_confirmed")) || !Boolean.FALSE.equals(value.get("old_authority_transferred"))
                || !(value.get("successor_generation") instanceof Integer || value.get("successor_generation") instanceof Long)
                || value.getLong("successor_generation") <= 0
                || !value.getString("scope_sha256").matches("[0-9a-f]{64}"))
            throw new IllegalStateException("abandonment marker invalid");
        new EmbeddedDuplexAbandonmentRequest(value.getJSONObject("operator_authorization").toString());
        return value;
    }
    private static void verifyArchive(File directory, JSONObject marker) throws Exception {
        String scope = marker.getString("scope_sha256");
        if (!scope.matches("[0-9a-f]{64}")) throw new IllegalStateException("abandonment scope invalid");
        File archive = new File(directory,"abandoned-"+scope);
        if (!Files.isDirectory(archive.toPath(),LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(archive.toPath()))
            throw new IllegalStateException("abandonment archive unavailable");
        if (!scope.equals(EmbeddedDuplexProcessFence.digest(read(new File(archive,"prior-fence.raw"),512)))
                || !marker.getString("recovery_fence_sha256").equals(EmbeddedDuplexProcessFence.digest(
                        read(new File(archive,"recovery-fence.raw"),512)))
                || !sameObject(new JSONObject(read(new File(archive,"receipt.json"),32*1024)),marker))
            throw new IllegalStateException("abandonment archive binding differs");
        verifyOptional(archive,"checkpoint.raw",marker.getString("checkpoint_sha256"),256*1024);
        verifyOptional(archive,"evidence.raw",marker.getString("evidence_sha256"),8*1024*1024);
        JSONObject authorization=marker.getJSONObject("operator_authorization");
        if(EmbeddedDuplexHistoricalAbandonment.SCHEMA.equals(authorization.getString("$schema"))) {
            JSONObject digests=authorization.getJSONObject("historical_peers")
                    .getJSONObject(authorization.getString("role_id")).getJSONObject("preserved_records");
            for(String name:EmbeddedDuplexHistoricalAbandonment.RECORDS)
                verifyOptional(archive,"preserved-"+name,digests.getString(name),8*1024*1024);
            File committed=new File(archive,"committed-fence.raw");
            if(Files.exists(committed.toPath(),LinkOption.NOFOLLOW_LINKS) && !read(committed,512).equals(expectedClear(archive)))
                throw new IllegalStateException("historical commit witness differs");
        }
    }
    private static String expectedClear(File archive) throws Exception {
        String[] fields=read(new File(archive,"recovery-fence.raw"),512).split("\n",-1);
        if(fields.length!=8 || !"pending".equals(fields[3]) || !"-".equals(fields[4]) || !"-".equals(fields[5]))
            throw new IllegalStateException("historical recovery fence shape differs");
        fields[3]="clear";String body=String.join("\n",java.util.Arrays.copyOf(fields,6))+"\n";
        return body+EmbeddedDuplexProcessFence.digest(body)+"\n";
    }
    private static boolean sameObject(JSONObject left,JSONObject right) throws Exception {
        java.util.Set<String> keys=new java.util.HashSet<>();for(java.util.Iterator<String> iterator=left.keys();iterator.hasNext();) keys.add(iterator.next());
        java.util.Set<String> other=new java.util.HashSet<>();for(java.util.Iterator<String> iterator=right.keys();iterator.hasNext();) other.add(iterator.next());
        if(!keys.equals(other)) return false;
        for(String key:keys) {
            Object a=left.get(key),b=right.get(key);
            if(a instanceof JSONObject && b instanceof JSONObject) { if(!sameObject((JSONObject)a,(JSONObject)b)) return false; }
            else if((a instanceof Integer || a instanceof Long) && (b instanceof Integer || b instanceof Long)) {
                if(((Number)a).longValue()!=((Number)b).longValue()) return false;
            } else if(!a.equals(b)) return false;
        }
        return true;
    }
    private static void verifyOptional(File archive,String name,String sha,int max) throws Exception {
        File path = new File(archive,name);
        if ("-".equals(sha)) {
            if (Files.exists(path.toPath(),LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("unexpected archived record");
        } else if (!sha.matches("[0-9a-f]{64}") || !sha.equals(EmbeddedDuplexProcessFence.digest(read(path,max))))
            throw new IllegalStateException("archived record differs");
    }
    private static String read(File path,int max) throws Exception {
        if (!Files.isRegularFile(path.toPath(),LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path.toPath()))
            throw new IllegalStateException("abandonment path invalid");
        long size=Files.size(path.toPath());
        if(size<=0 || size>max) throw new IllegalStateException("abandonment bounds");
        byte[] bytes=Files.readAllBytes(path.toPath());
        if(bytes.length!=size) throw new IllegalStateException("abandonment changed");
        return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void writeNew(File path,String exact,Protection protection) throws Exception {
        try(FileChannel channel=FileChannel.open(path.toPath(),StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            protection.apply(path,false);
            ByteBuffer buffer=ByteBuffer.wrap(exact.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while(buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
