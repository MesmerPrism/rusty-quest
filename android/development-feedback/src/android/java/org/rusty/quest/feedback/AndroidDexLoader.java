package org.rusty.quest.feedback;

import android.content.Context;
import dalvik.system.DexClassLoader;
import java.io.*;

/** Source-only API-26+ Android adapter. No APK integration, transport or Quest execution is included. */
public final class AndroidDexLoader implements GenerationLoader {
    @Override public ClassLoader create(File artifact,ClassLoader contractParent) {
        return new DexClassLoader(artifact.getAbsolutePath(),null,null,contractParent);
    }
    public static ArtifactStore.Artifact stage(Context context,String id,InputStream content,String sha256) throws IOException {
        return ArtifactStore.stage(context.getDir("development_feedback",Context.MODE_PRIVATE),id,content,sha256,true);
    }
}
