package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
/** One app-private selection. Caller must hold the fixed global writer and actual owner eligibility.
 * This store neither inspects historical rows nor supplies authority/physical-cleanup evidence. */
final class EmbeddedDuplexRunSelectionStore {
 interface DirectorySync { void sync(File directory)throws Exception; default void privateMode(File path,boolean directory)throws Exception { throw new IllegalStateException("private mode provider absent"); } }
 private final File app,directory,selection;
 private final DirectorySync sync;
 EmbeddedDuplexRunSelectionStore(File app,DirectorySync sync)throws Exception{
  regularDirectory(app);this.app=app.getCanonicalFile();this.sync=Objects.requireNonNull(sync);
  directory=new File(this.app,"embedded-duplex-runs-v1");selection=new File(directory,"selection.v1.json");
 }
 synchronized EmbeddedDuplexRunContext current()throws Exception{
  if(!Files.exists(directory.toPath(),LinkOption.NOFOLLOW_LINKS))return new EmbeddedDuplexRunContext(app,app,null,0);
  regularDirectory(directory);rejectPending(selection);
  if(!Files.exists(selection.toPath(),LinkOption.NOFOLLOW_LINKS))return new EmbeddedDuplexRunContext(app,app,null,0);
  JSONObject row=read(selection);fields(row,Set.of("schema","run_id","selection_generation"));
  if(!"rusty.quest.embedded_duplex.run_selection.v1".equals(row.getString("schema")))throw new IllegalStateException("run selection schema");
  long generation=positive(row,"selection_generation");String id=row.getString("run_id");File root=existing(id);
  return new EmbeddedDuplexRunContext(app,root,id,generation,release(root));
 }
 synchronized EmbeddedDuplexRunContext newRun(String id)throws Exception{return newRun(id,null);}
 synchronized EmbeddedDuplexRunContext newRun(String id,String releaseSource)throws Exception{
  EmbeddedDuplexRunContext.requireId(id);EmbeddedDuplexRunContext prior=current();
  if(id.equals(prior.runId))return prior;
  createDirectory(directory);File root=new File(directory,id);File marker=new File(root,"run.v1.json");
  if(Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS)){
   existing(id);JSONObject row=read(marker);
   if(row.getLong("created_from_generation")!=prior.selectionGeneration)throw new IllegalStateException("run already exists; ResumeRun required");
  }else{
   createDirectory(root);
   write(marker,new JSONObject().put("schema","rusty.quest.embedded_duplex.operational_run.v1").put("run_id",id).put("created_from_generation",prior.selectionGeneration).put("release_source",releaseSource==null?JSONObject.NULL:validateRelease(new JSONObject(releaseSource))));
  }
  return select(id,root,prior);
 }
 synchronized EmbeddedDuplexRunContext resumeRun(String id)throws Exception{
  EmbeddedDuplexRunContext.requireId(id);EmbeddedDuplexRunContext prior=current();File root=existing(id);
  if(id.equals(prior.runId))return prior;return select(id,root,prior);
 }
 EmbeddedDuplexRunContext retained(String id)throws Exception{
  EmbeddedDuplexRunContext.requireId(id);File root=existing(id);return new EmbeddedDuplexRunContext(app,root,id,0,release(root));
 }
 private EmbeddedDuplexRunContext select(String id,File root,EmbeddedDuplexRunContext prior)throws Exception{
  long next=Math.addExact(prior.selectionGeneration,1);
  write(selection,new JSONObject().put("schema","rusty.quest.embedded_duplex.run_selection.v1").put("run_id",id).put("selection_generation",next));
  return new EmbeddedDuplexRunContext(app,root,id,next,release(root));
 }
 private File existing(String id)throws Exception{
  EmbeddedDuplexRunContext.requireId(id);regularDirectory(directory);File root=new File(directory,id);regularDirectory(root);
  JSONObject marker=read(new File(root,"run.v1.json"));fields(marker,Set.of("schema","run_id","created_from_generation","release_source"));
  Object generation=marker.get("created_from_generation");
  if(!"rusty.quest.embedded_duplex.operational_run.v1".equals(marker.getString("schema"))||!id.equals(marker.getString("run_id"))
    ||!(generation instanceof Integer||generation instanceof Long)||marker.getLong("created_from_generation")<0)throw new IllegalStateException("run marker differs");
  if(!marker.isNull("release_source"))validateRelease(marker.getJSONObject("release_source"));
  return root;
 }
 private String release(File root)throws Exception{
  JSONObject marker=read(new File(root,"run.v1.json"));return marker.isNull("release_source")?null:EmbeddedDuplexCanonicalJson.encode(validateRelease(marker.getJSONObject("release_source")));
 }
 static JSONObject validateRelease(JSONObject value)throws Exception{
  fields(value,Set.of("source_run_id","whole_original_id","request_sha256","outcome_sha256","physical_retirement_sha256","prior_native_generation"));
  if(!value.isNull("source_run_id"))EmbeddedDuplexRunContext.requireId(value.getString("source_run_id"));
  EmbeddedDuplexRunContext.requireId(value.getString("whole_original_id"));positive(value,"prior_native_generation");
  for(String key:new String[]{"request_sha256","outcome_sha256","physical_retirement_sha256"})
    if(!value.getString(key).matches("[0-9a-f]{64}"))throw new IllegalStateException("release source digest");
  return value; // Locator/hash tuple only; never authority or a serialized Native permit.
 }
 private static long positive(JSONObject value,String key)throws Exception{
  Object number=value.get(key);if(!(number instanceof Integer||number instanceof Long)||value.getLong(key)<=0)throw new IllegalStateException("run generation");return value.getLong(key);
 }
 private static void fields(JSONObject row,Set<String> expected){Set<String> keys=new HashSet<>();row.keys().forEachRemaining(keys::add);if(!keys.equals(expected))throw new IllegalStateException("run fields");}
 static void regularDirectory(File dir)throws Exception{
  if(!Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(dir.toPath()))throw new IllegalStateException("run directory type");
 }
 private void createDirectory(File dir)throws Exception{
  if(!Files.exists(dir.toPath(),LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(dir.toPath());sync.privateMode(dir,true);sync.sync(dir.getParentFile());}
  regularDirectory(dir);
 }
 private static void rejectPending(File file){if(Files.exists(Path.of(file+".new"),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("incomplete run publication");}
 private static JSONObject read(File file)throws Exception{
  rejectPending(file);if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file.toPath())||Files.size(file.toPath())>2048)throw new IllegalStateException("run record unavailable");
  return EmbeddedDuplexCanonicalJson.readOwned(Files.readAllBytes(file.toPath()));
 }
 private void write(File file,JSONObject value)throws Exception{
  rejectPending(file);File temporary=new File(file+".new");
  try(FileOutputStream out=new FileOutputStream(Files.createFile(temporary.toPath()).toFile())){
   sync.privateMode(temporary,false);out.write(EmbeddedDuplexCanonicalJson.encode(value).getBytes(StandardCharsets.UTF_8));out.getFD().sync();
  }
  Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync.sync(file.getParentFile());
 }
}
