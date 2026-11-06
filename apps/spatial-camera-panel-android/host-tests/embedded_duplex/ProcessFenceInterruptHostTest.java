package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.io.*;import java.lang.reflect.*;import java.nio.channels.*;import java.nio.file.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Whole fence with real host FileLock/I/O; cancellation schedule is modeled. */
public final class ProcessFenceInterruptHostTest {
 static int checks;static void check(boolean v,String m){if(!v)throw new AssertionError(m);checks++;}
 static Object get(EmbeddedDuplexProcessFence f,String n)throws Exception{Field x=EmbeddedDuplexProcessFence.class.getDeclaredField(n);x.setAccessible(true);return x.get(f);}
 static final String RUN="0123456789abcdef0123456789abcdef";
 static EmbeddedDuplexProcessFence acquire(File dir,EmbeddedDuplexProcessFence.DirectorySync sync)throws Exception{return EmbeddedDuplexProcessFence.acquire(dir,null,null,sync,RUN,dir);}
 static EmbeddedDuplexProcessFence fresh(Path root,String name)throws Exception{EmbeddedDuplexProcessFence f=acquire(Files.createDirectory(root.resolve(name)).toFile(),()->{});f.beforeRuntimeEffects(null,null);return f;}
 static byte[] boundBytes(EmbeddedDuplexProcessFence f,Path dir,String name)throws Exception{
  if(!name.equals("process-fence.v1.lock"))return Files.readAllBytes(dir.resolve(name));
  FileDescriptor fd=(FileDescriptor)get(f,"descriptor");byte[] bytes=new byte[(int)android.system.Os.fstat(fd).st_size];check(android.system.Os.pread(fd,bytes,0,bytes.length,0)==bytes.length,"same held descriptor snapshot");return bytes;
 }
 static int openFiles()throws Exception{Field field=android.system.Os.class.getDeclaredField("FILES");field.setAccessible(true);return ((java.util.Map<?,?>)field.get(null)).size();}
 static void boundRefusals(Path root)throws Exception{
  for(String damage:new String[]{"missing","witness","staging","checksum","canonical","bounds","directory","read-io"}){
   Path dir=Files.createDirectory(root.resolve("bound-refusal-"+damage));
   try(EmbeddedDuplexProcessFence f=acquire(dir.toFile(),()->{})){
    f.bindIncarnation(new EmbeddedDuplexProcessIncarnation.Identity("00112233-4455-6677-8899-aabbccddeeff",42,10001,101));f.beforeRuntimeEffects(null,null);String before=f.nativeAdmissionRecord();
    Path companion=dir.resolve("process-incarnation.v1.json");String exact=Files.readString(companion);
    switch(damage){
     case "missing":Files.delete(companion);break;
     case "witness":Files.delete(dir.resolve("process-incarnation.v1.initialized"));break;
     case "staging":Files.writeString(dir.resolve("process-incarnation.v1.json.new"),"partial");break;
     case "checksum":Files.writeString(companion,EmbeddedDuplexCanonicalJson.encode(new org.json.JSONObject(exact).put("sha256","0".repeat(64))));break;
     case "canonical":Files.writeString(companion,exact+"\n");break;
     case "bounds":Files.writeString(companion," ".repeat(4097));break;
     case "directory":Files.delete(companion);Files.createDirectory(companion);break;
     case "read-io":android.system.Os.failRead=true;break;
    }
    int handles=openFiles();check(EmbeddedDuplexProcessIncarnation.load(dir.toFile(),before)==null,"bound companion refusal "+damage);check(openFiles()==handles,"companion refusal closes own descriptor "+damage);
    if(damage.equals("read-io"))android.system.Os.failRead=true;
    try{f.nativeAdmissionRecord();throw new AssertionError("bound damaged companion accepted "+damage);}catch(IllegalStateException expected){check(expected.getMessage().equals("bound process fence custody unavailable"),"bound refusal remains fail-closed "+damage);}
    check((boolean)get(f,"persistenceFailed"),"bound refusal latches custody failure "+damage);check(openFiles()==handles,"bound failed callback closes own descriptor "+damage);
    check(before.equals(new String(boundBytes(f,dir,"process-fence.v1.lock"),java.nio.charset.StandardCharsets.US_ASCII)),"refusal never rewrites pending fence "+damage);
   }
  }
  System.out.println("PASS bound companion missing/witness/staging/checksum/canonical/bounds/directory/read-IO refusals and descriptor cleanup");
 }
 static void boundInterruptedRead(Path root,boolean cancel)throws Exception{
  Path dir=Files.createDirectory(root.resolve(cancel?"bound-cancel":"bound-preinterrupt"));
  try(EmbeddedDuplexProcessFence f=acquire(dir.toFile(),()->{})){
   f.bindIncarnation(new EmbeddedDuplexProcessIncarnation.Identity("00112233-4455-6677-8899-aabbccddeeff",42,10001,101));
   f.beforeRuntimeEffects(null,null);String before=f.nativeAdmissionRecord();
   String[] names={"process-fence.v1.lock","process-incarnation.v1.json","process-incarnation.v1.initialized"};byte[][] bytes=new byte[names.length][];
   for(int i=0;i<names.length;i++)bytes[i]=boundBytes(f,dir,names[i]);
   AtomicReference<Throwable> error=new AtomicReference<>(),readError=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean(),release=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1);
   FutureTask<Void> task=new FutureTask<>(()->{entered.countDown();if(cancel){while(!release.get())Thread.onSpinWait();}else Thread.currentThread().interrupt();
    try{check(before.equals(f.nativeAdmissionRecord()),"bound exact pending record");}catch(Throwable x){error.set(x);
     // Independent primitive observation after the actual production call. The
     // production loader intentionally swallows this underlying exception.
     try{Method read=EmbeddedDuplexProcessIncarnation.class.getDeclaredMethod("readBytes",Path.class,int.class);read.setAccessible(true);read.invoke(null,dir.resolve("process-incarnation.v1.initialized"),128);}catch(InvocationTargetException cause){readError.set(cause.getCause());}
    }finally{flag.set(Thread.currentThread().isInterrupted());}return null;});
   Thread t=new Thread(task,"bound-incarnation-callback");t.start();check(entered.await(5,TimeUnit.SECONDS),"bound callback entered");if(cancel){check(task.cancel(true),"bound real FutureTask.cancel(true)");release.set(true);}t.join(5000);check(!t.isAlive(),"bound callback joined");
   for(int i=0;i<names.length;i++)check(java.util.Arrays.equals(bytes[i],boundBytes(f,dir,names[i])),"bound bytes unchanged: "+names[i]);
   check(flag.get(),"bound callback interrupt flag retained");check(((FileChannel)get(f,"channel")).isOpen()&&((FileLock)get(f,"lock")).isValid(),"bound lifetime descriptor/lock remains live");
   for(Throwable x=error.get();x!=null;x=x.getCause()){String message=x.getMessage();System.out.println("BOUND_CAUSE class="+x.getClass().getName()+" messageLength="+(message==null?0:message.length())+" messageSha256="+EmbeddedDuplexProcessFence.digest(message));x.printStackTrace(System.out);}
   if(readError.get()!=null){System.out.println("BOUND_READ_PRIMITIVE_AFTER_FAILURE");readError.get().printStackTrace(System.out);}
   System.out.println("BOUND_OBSERVATION cancel="+cancel+" bytesUnchanged=true interruptRetained="+flag.get()+" lockValid=true persistenceFailed="+get(f,"persistenceFailed"));
   check(error.get()==null,"bound interrupted admission must remain usable: "+error.get());healthy(f);
   EmbeddedDuplexProcessFence.CallbackGuard next=f.callbacks();try(EmbeddedDuplexProcessFence.CallbackLease ignored=next.enter()){check(before.equals(f.nativeAdmissionRecord()),"bound later callback exact record");}check(next.retireAndDrain().isDone(),"bound later callback joined");
  }
 }
 static void healthy(EmbeddedDuplexProcessFence f)throws Exception{check(((FileChannel)get(f,"channel")).isOpen()&&((FileLock)get(f,"lock")).isValid()&&!(boolean)get(f,"persistenceFailed"),"same lifetime descriptor/lock remains live");check(((FileOutputStream)get(f,"descriptorView")).getFD()==get(f,"descriptor"),"one exact shared descriptor owner");f.nativeInspectionRecord();}
 static void interruptedRead(EmbeddedDuplexProcessFence f,boolean cancel)throws Exception{
  String before=f.nativeAdmissionRecord();check(before.startsWith("rusty.quest.embedded_duplex.app_process_fence.v2\n")&&RUN.equals(before.split("\n")[6]),"current v2 exact run binding");AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean(),release=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1);
  FutureTask<Void> task=new FutureTask<>(()->{entered.countDown();if(cancel){while(!release.get())Thread.onSpinWait();}else Thread.currentThread().interrupt();try{check(before.equals(f.nativeAdmissionRecord()),"same exact pending record");check(before.equals(f.nativeInspectionRecord()),"same held v2 inspection record");}catch(Throwable x){error.set(x);}finally{flag.set(Thread.currentThread().isInterrupted());}return null;});Thread t=new Thread(task,"owned-host-callback");t.start();check(entered.await(5,TimeUnit.SECONDS),"callback entered");if(cancel){check(task.cancel(true),"real FutureTask.cancel(true)");release.set(true);}t.join(5000);check(!t.isAlive(),"owned worker joined");check(error.get()==null,"interrupted held read must not close lifetime fence: "+error.get());check(flag.get(),"interrupt status retained");healthy(f);
  EmbeddedDuplexProcessFence.CallbackGuard next=f.callbacks();try(EmbeddedDuplexProcessFence.CallbackLease ignored=next.enter()){check(before.equals(f.nativeAdmissionRecord()),"distinct successor callback retains exact actor scope");}check(next.retireAndDrain().isDone(),"successor callback joined");
 }
 static Process child(String...args)throws IOException{java.util.ArrayList<String> command=new java.util.ArrayList<>();command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());command.add("-cp");command.add(System.getProperty("java.class.path"));command.add(ProcessFenceInterruptHostTest.class.getName());command.addAll(java.util.Arrays.asList(args));return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();}
 public static void main(String[]args)throws Exception{
  if(args[0].equals("--contend")){try(EmbeddedDuplexProcessFence f=acquire(Path.of(args[1]).toFile(),()->{})){throw new AssertionError("competing process acquired lock");}catch(IllegalStateException expected){if(!expected.getMessage().contains("already held"))throw expected;return;}}
  if(args[0].equals("--hold")){try(EmbeddedDuplexProcessFence f=acquire(Path.of(args[1]).toFile(),()->{})){f.beforeRuntimeEffects(null,null);Files.writeString(Path.of(args[2]),"ready");System.in.read();}return;}
  Path root=Path.of(args[0]);Files.createDirectory(root);
  boundInterruptedRead(root,false);boundInterruptedRead(root,true);boundRefusals(root);
  try(EmbeddedDuplexProcessFence f=fresh(root,"preinterrupt")){interruptedRead(f,false);System.out.println("PASS pre-interrupted exact same-descriptor admission and next callback");}
  try(EmbeddedDuplexProcessFence f=fresh(root,"cancel")){interruptedRead(f,true);interruptedRead(f,true);System.out.println("PASS real cancellation overlap then distinct callback lifetimes; no media cleanup claim");}
  try(EmbeddedDuplexProcessFence f=fresh(root,"cancel-inside-read")){
   String before=f.nativeAdmissionRecord();check(before.startsWith("rusty.quest.embedded_duplex.app_process_fence.v2\n")&&RUN.equals(before.split("\n")[6]),"current v2 exact run binding");AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();android.system.Os.blockNextRead();FutureTask<Void> task=new FutureTask<>(()->{try{check(before.equals(f.nativeAdmissionRecord()),"held record exact after in-read cancellation");}catch(Throwable x){error.set(x);}finally{flag.set(Thread.currentThread().isInterrupted());}return null;});Thread t=new Thread(task,"owned-in-read-callback");t.start();check(android.system.Os.awaitRead(),"actual admission entered modeled syscall boundary");check(task.cancel(true),"real cancel during modeled read");android.system.Os.releaseRead();t.join(5000);check(!t.isAlive()&&error.get()==null&&flag.get(),"in-read cancellation joins without flag clear or descriptor close");healthy(f);System.out.println("PASS cancellation inside actual admission/modeled syscall and subsequent recovery guard");
  }
  Path directory=Files.createDirectory(root.resolve("persist"));try(EmbeddedDuplexProcessFence f=acquire(directory.toFile(),()->{})){
   AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();Thread t=new Thread(()->{Thread.currentThread().interrupt();try{f.beforeRuntimeEffects(null,null);}catch(Throwable x){error.set(x);}finally{flag.set(Thread.currentThread().isInterrupted());}},"owned-host-persist");t.start();t.join(5000);check(!t.isAlive()&&error.get()==null&&flag.get(),"same-FD durable persist preserves interrupted caller");healthy(f);f.nativeAdmissionRecord();System.out.println("PASS interrupted durable pending persist");
  }
  Path cancelWrite=Files.createDirectory(root.resolve("cancel-write"));try(EmbeddedDuplexProcessFence f=acquire(cancelWrite.toFile(),()->{})){
   AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();android.system.Os.blockNextWrite();
   FutureTask<Void> task=new FutureTask<>(()->{try{f.beforeRuntimeEffects(null,null);}catch(Throwable x){error.set(x);}finally{flag.set(Thread.currentThread().isInterrupted());}return null;});Thread t=new Thread(task,"owned-in-write-callback");t.start();check(android.system.Os.awaitWrite(),"entered modeled pwrite boundary");check(task.cancel(true),"cancel while persist syscall active");android.system.Os.releaseWrite();t.join(5000);check(!t.isAlive()&&error.get()==null&&flag.get(),"cancelled persist preserves flag and held descriptor");healthy(f);System.out.println("PASS cancellation inside held persist/modeled syscall");
  }
  try(EmbeddedDuplexProcessFence f=fresh(root,"read-io-failure")){android.system.Os.failRead=true;try{f.nativeAdmissionRecord();throw new AssertionError("pread fault accepted");}catch(IOException expected){check((boolean)get(f,"persistenceFailed"),"read IO failure still poisons");check(((FileChannel)get(f,"channel")).isOpen()&&((FileLock)get(f,"lock")).isValid(),"IO error is not descriptor cancellation");}}
  Path writeFailure=Files.createDirectory(root.resolve("write-io-failure"));try(EmbeddedDuplexProcessFence f=acquire(writeFailure.toFile(),()->{})){android.system.Os.failWrite=true;try{f.beforeRuntimeEffects(null,null);throw new AssertionError("pwrite fault accepted");}catch(IOException expected){check((boolean)get(f,"persistenceFailed"),"write IO failure still poisons");}}
  EmbeddedDuplexProcessFence rawFault=fresh(root,"closed-raw-io");android.system.Os.close((FileDescriptor)get(rawFault,"descriptor"));try{rawFault.nativeAdmissionRecord();throw new AssertionError("closed raw descriptor accepted");}catch(Exception expected){check(expected instanceof IOException||(expected instanceof IllegalStateException&&expected.getMessage().equals("stale app process generation")),"actual closed descriptor still fails through IO or existing live-lock guard");}finally{try{rawFault.close();}catch(IOException expected){checks++;}}
  try(EmbeddedDuplexProcessFence f=fresh(root,"tamper")){
   FileDescriptor fd=(FileDescriptor)get(f,"descriptor");android.system.Os.pwrite(fd,new byte[]{'x'},0,1,0);
   try{f.nativeAdmissionRecord();throw new AssertionError("tamper accepted");}catch(IllegalStateException expected){check((boolean)get(f,"persistenceFailed"),"record mismatch still poisons");}
   try{f.nativeInspectionRecord();throw new AssertionError("poison accepted");}catch(IllegalStateException expected){checks++;}
  }
  try(EmbeddedDuplexProcessFence f=fresh(root,"bounds")){
   android.system.Os.ftruncate((FileDescriptor)get(f,"descriptor"),513);try{f.nativeAdmissionRecord();throw new AssertionError("oversized accepted");}catch(IllegalStateException expected){check((boolean)get(f,"persistenceFailed"),"record bounds still fail closed");}
  }
  EmbeddedDuplexProcessFence closed=fresh(root,"closed");closed.close();check(!((FileChannel)get(closed,"channel")).isOpen()&&!((FileLock)get(closed,"lock")).isValid()&&!(boolean)get(closed,"persistenceFailed"),"explicit lifetime close unchanged");try{closed.nativeAdmissionRecord();throw new AssertionError("closed lock accepted");}catch(IllegalStateException expected){checks++;}
  try(EmbeddedDuplexProcessFence f=fresh(root,"released-lock")){((FileLock)get(f,"lock")).release();try{f.nativeAdmissionRecord();throw new AssertionError("invalid lock accepted");}catch(IllegalStateException expected){check(!(boolean)get(f,"persistenceFailed"),"invalid lock refusal separate from record poison");}}
  Path missing=root.resolve("never-created");try{android.system.Os.open(missing.toString(),android.system.OsConstants.O_RDWR|android.system.OsConstants.O_CLOEXEC|android.system.OsConstants.O_NOFOLLOW,0);throw new AssertionError("missing opened");}catch(IOException expected){check(!Files.exists(missing),"no-create flag contract leaves no target");}
  Path failed=Files.createDirectory(root.resolve("persist-fault"));AtomicBoolean failSync=new AtomicBoolean();try(EmbeddedDuplexProcessFence f=acquire(failed.toFile(),()->{if(failSync.get())throw new IOException("modeled durable-directory failure");})){
   failSync.set(true);try{f.beforeRuntimeEffects(null,null);throw new AssertionError("sync failure accepted");}catch(IOException expected){check((boolean)get(f,"persistenceFailed"),"directory persistence failure still poisons");}
  }
  Path borrowed=root.resolve("borrowed-view");Files.createFile(borrowed);FileDescriptor fd=android.system.Os.open(borrowed.toString(),android.system.OsConstants.O_RDWR|android.system.OsConstants.O_CLOEXEC|android.system.OsConstants.O_NOFOLLOW,0);
  FileOutputStream view=new FileOutputStream(fd){@Override public void close(){/* Model Android's borrowed raw descriptor, no new FD. */}};
  Method close=EmbeddedDuplexProcessFence.class.getDeclaredMethod("closeDescriptor",FileOutputStream.class,FileDescriptor.class);close.setAccessible(true);close.invoke(null,view,fd);check(!fd.valid(),"borrowed stream view still closes fence-owned raw descriptor");
  Path borrowedFailure=root.resolve("borrowed-view-failure");Files.createFile(borrowedFailure);FileDescriptor failedFd=android.system.Os.open(borrowedFailure.toString(),android.system.OsConstants.O_RDWR|android.system.OsConstants.O_CLOEXEC|android.system.OsConstants.O_NOFOLLOW,0);IOException primary=new IOException("modeled borrowed close failure");FileOutputStream failedView=new FileOutputStream(failedFd){@Override public void close()throws IOException{throw primary;}};
  try{close.invoke(null,failedView,failedFd);throw new AssertionError("close failure lost");}catch(InvocationTargetException expected){check(expected.getCause()==primary,"primary view failure preserved");check(!failedFd.valid(),"failed borrowed view still releases raw descriptor");}
  Path acquisitionFailure=Files.createDirectory(root.resolve("acquisition-failure"));try{acquire(acquisitionFailure.toFile(),()->{throw new IOException("modeled initialization sync failure");});throw new AssertionError("acquisition failure lost");}catch(IOException expected){check(!android.system.Os.lastOpened.valid(),"failed acquisition closes exact owned raw descriptor");}
  Path successorDirectory=Files.createDirectory(root.resolve("successor"));long previous;
  try(EmbeddedDuplexProcessFence f=acquire(successorDirectory.toFile(),()->{})){f.beforeRuntimeEffects(null,null);previous=f.generation();Process competing=child("--contend",successorDirectory.toString());check(competing.waitFor(5,TimeUnit.SECONDS)&&competing.exitValue()==0,"other process denied while held");healthy(f);}
  try(EmbeddedDuplexProcessFence successor=acquire(successorDirectory.toFile(),()->{})){check(successor.generation()==previous+1&&successor.recoveryOnly()&&successor.effectsPending(),"released pending successor stays recovery-only");check(successor.nativeInspectionRecord().split("\n")[6].equals(RUN),"successor retains v2 selected run");try{successor.beforeRuntimeEffects(null,null);throw new AssertionError("recovery successor started effects");}catch(IllegalStateException expected){checks++;}}
  Path deathDirectory=Files.createDirectory(root.resolve("process-death"));Path ready=root.resolve("death-ready");Process doomed=child("--hold",deathDirectory.toString(),ready.toString());
  try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.exists(ready)&&doomed.isAlive()&&System.nanoTime()<deadline)Thread.sleep(10);check(Files.exists(ready),"real child held pending lock before death");}finally{doomed.destroyForcibly();check(doomed.waitFor(5,TimeUnit.SECONDS),"killed host process reaped");}
  try(EmbeddedDuplexProcessFence successor=acquire(deathDirectory.toFile(),()->{})){check(successor.generation()==2&&successor.recoveryOnly()&&successor.effectsPending(),"actual process death releases lock but never clears pending");}
  try{EmbeddedDuplexProcessFence.acquire(successorDirectory.toFile(),null,null,()->{},"ffffffffffffffffffffffffffffffff",successorDirectory.toFile());throw new AssertionError("wrong selected run accepted");}catch(IllegalStateException expected){check(expected.getMessage().contains("operational run differs"),"existing run mismatch guard preserved");}
  System.out.println(checks+" whole production fence controls PASS; host classic I/O models Android Os, actual interrupt provenance unproven");
 }
}
