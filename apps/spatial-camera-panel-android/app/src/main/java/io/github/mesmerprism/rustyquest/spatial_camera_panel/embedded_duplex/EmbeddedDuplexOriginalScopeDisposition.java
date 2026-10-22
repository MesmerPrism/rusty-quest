package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.File;

/** Admission accounting only. Original results, retention and replay identity stay immutable. */
final class EmbeddedDuplexOriginalScopeDisposition {
    private EmbeddedDuplexOriginalScopeDisposition() {}
    static boolean disposed(File directory,String scope,String currentRecord,long currentGeneration,int uid,String packageId)throws Exception{
        if(scope==null || !scope.matches("[0-9a-f]{64}"))return false;
        String[] proof=EmbeddedDuplexAbandonment.committedOriginalScope(directory,scope,uid,packageId);
        if(proof==null)proof=EmbeddedDuplexProcessIncarnation.committedOriginalScope(directory,scope,uid);
        if(proof==null)return false;
        String prior=proof[0];String[] old=fence(prior),committed=fence(proof[1]),current=fence(currentRecord);
        if(!scope.equals(EmbeddedDuplexProcessFence.digest(prior))
                || Long.parseLong(current[1])!=currentGeneration
                || Long.parseLong(old[1])>=currentGeneration || old[2].equals(current[2])
                || Long.parseLong(committed[1])>currentGeneration
                || Long.parseLong(committed[1])==currentGeneration && !committed[2].equals(current[2]))
            return false;
        return true;
    }
    static String[] fence(String raw)throws Exception{
        if(raw==null || raw.length()>512)throw new IllegalStateException("disposed scope fence bounds");
        String[] f=raw.split("\n",-1);
        if(f.length!=8 || !"rusty.quest.embedded_duplex.app_process_fence.v1".equals(f[0])
                || !f[1].matches("[1-9][0-9]{0,18}") || !f[2].matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
                || !java.util.Set.of("pending","clear").contains(f[3])
                || !f[4].matches("-|[0-9a-f]{64}") || !f[5].matches("-|[0-9a-f]{64}")
                || !f[6].equals(EmbeddedDuplexProcessFence.digest(String.join("\n",java.util.Arrays.copyOf(f,6))+"\n"))
                || !f[7].isEmpty())throw new IllegalStateException("disposed scope fence integrity");
        return f;
    }
    static void clearAfter(String original,String clear)throws Exception{
        String[] old=fence(original),next=fence(clear);
        if(!"pending".equals(old[3]) || !"clear".equals(next[3]) || !"-".equals(next[4]) || !"-".equals(next[5])
                || Long.parseLong(next[1])<=Long.parseLong(old[1]) || old[2].equals(next[2]))
            throw new IllegalStateException("disposed scope clear commit differs");
    }
}
