package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.io.File;
import java.util.Objects;
/** Captured operational storage only. The original application Context owns identity/settings. */
public final class EmbeddedDuplexRunContext {
 public final String runId;
 public final long selectionGeneration;
 final File applicationRoot, root;
 final String releaseSourceJson;
 EmbeddedDuplexRunContext(File app,File root,String id,long generation){this(app,root,id,generation,null);}
 EmbeddedDuplexRunContext(File app,File root,String id,long generation,String releaseSource){
  if(app==null||root==null||generation<0)throw new IllegalArgumentException("run context");
  if(id!=null)requireId(id);applicationRoot=app;this.root=root;runId=id;selectionGeneration=generation;releaseSourceJson=releaseSource;
 }
 static void requireId(String id){if(id==null||!id.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("run id");}
 public boolean legacy(){return runId==null;}
 boolean same(EmbeddedDuplexRunContext other){return other!=null&&selectionGeneration==other.selectionGeneration&&Objects.equals(runId,other.runId)&&root.equals(other.root)&&Objects.equals(releaseSourceJson,other.releaseSourceJson);}
 void requireSame(EmbeddedDuplexRunContext current){if(!same(current))throw new IllegalStateException("operational run selection changed");}
}
