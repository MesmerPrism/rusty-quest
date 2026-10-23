package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.io.*;import java.lang.reflect.*;import java.nio.channels.*;import java.nio.file.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Whole fence with real host FileLock/I/O; cancellation schedule is modeled. */
public final class ProcessFenceInterruptHostTest {
 static int checks;static void check(boolean v,String m){if(!v)throw new AssertionError(m);checks++;}
 static Object get(EmbeddedDuplexProcessFence f,String n)throws Exception{Field x=EmbeddedDuplexProcessFence.class.getDeclaredField(n);x.setAccessible(true);return x.get(f);}
 static EmbeddedDuplexProcessFence fresh(Path root,String name)throws Exception{EmbeddedDuplexProcessFence f=EmbeddedDuplexProcessFence.acquire(Files.createDirectory(root.resolve(name)).toFile(),null,null,()->{});f.beforeRuntimeEffects(null,null);return f;}
 static void healthy(EmbeddedDuplexProcessFence f)throws Exception{check(((FileChannel)get(f,"channel")).isOpen()&&((FileLock)get(f,"lock")).isValid()&&!(boolean)get(f,"persistenceFailed"),"same lifetime descriptor/lock remains live");check(((FileOutputStream)get(f,"descriptorView")).getFD()==get(f,"descriptor"),"one exact shared descriptor owner");f.recoveryStatus();}
 static void interruptedRead(EmbeddedDuplexProcessFence f,boolean cancel)throws Exception{
  String before=f.nativeAdmissionRecord();AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean(),release=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1);
  FutureTask<Void> task=new FutureTask<>(()->{entered.countDown();if(cancel){while(!release.get())Thread.onSpinWait();}else Thread.currentThread().interrupt();try{check(before.equals(f.nativeAdmissionRecord()),"same exact pending record");}catch(Throwable x){error.set(x);}finally{flag.set(Thread.currentThread().isInterrupted());}return null;});Thread t=new Thread(task,"owned-host-callback");t.start();check(entered.await(5,TimeUnit.SECONDS),"callback entered");if(cancel){check(task.cancel(true),"real FutureTask.cancel(true)");release.set(true);}t.join(5000);check(!t.isAlive(),"owned worker joined");check(error.get()==null,"interrupted held read must not close lifetime fence: "+error.get());check(flag.get(),"interrupt status retained");healthy(f);
  EmbeddedDuplexProcessFence.CallbackGuard next=f.callbacks();try(EmbeddedDuplexProcessFence.CallbackLease ignored=next.enter()){check(before.equals(f.nativeAdmissionRecord()),"distinct successor callback retains exact actor scope");}check(next.retireAndDrain().isDone(),"successor callback joined");
 }
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]);Files.createDirectory(root);
  try(EmbeddedDuplexProcessFence f=fresh(root,"preinterrupt")){interruptedRead(f,false);System.out.println("PASS pre-interrupted exact same-descriptor admission and next callback");}
  try(EmbeddedDuplexProcessFence f=fresh(root,"cancel")){interruptedRead(f,true);interruptedRead(f,true);System.out.println("PASS real cancellation overlap then distinct callback lifetimes; no media cleanup claim");}
  try(EmbeddedDuplexProcessFence f=fresh(root,"cancel-inside-read")){
   String before=f.nativeAdmissionRecord();AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();android.system.Os.blockNextRead();FutureTask<Void> task=new FutureTask<>(()->{try{check(before.equals(f.nativeAdmissionRecord()),"held record exact after in-read cancellation");}catch(Throwable x){error.set(x);}finally{flag.set(Thread.currentThread().isInterrupted());}return null;});Thread t=new Thread(task,"owned-in-read-callback");t.start();check(android.system.Os.awaitRead(),"actual admission entered modeled syscall boundary");check(task.cancel(true),"real cancel during modeled read");android.system.Os.releaseRead();t.join(5000);check(!t.isAlive()&&error.get()==null&&flag.get(),"in-read cancellation joins without flag clear or descriptor close");healthy(f);System.out.println("PASS cancellation inside actual admission/modeled syscall and subsequent recovery guard");
  }
  Path directory=Files.createDirectory(root.resolve("persist"));try(EmbeddedDuplexProcessFence f=EmbeddedDuplexProcessFence.acquire(directory.toFile(),null,null,()->{})){
   AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean flag=new AtomicBoolean();Thread t=new Thread(()->{Thread.currentThread().interrupt();try{f.beforeRuntimeEffects(null,null);}catch(Throwable x){error.set(x);}finally{flag.set(Thread.currentThread().isInterrupted());}},"owned-host-persist");t.start();t.join(5000);check(!t.isAlive()&&error.get()==null&&flag.get(),"same-FD durable persist preserves interrupted caller");healthy(f);f.nativeAdmissionRecord();System.out.println("PASS interrupted durable pending persist");
  }
  try(EmbeddedDuplexProcessFence f=fresh(root,"tamper")){
   FileDescriptor fd=(FileDescriptor)get(f,"descriptor");android.system.Os.pwrite(fd,new byte[]{'x'},0,1,0);
   try{f.nativeAdmissionRecord();throw new AssertionError("tamper accepted");}catch(IllegalStateException expected){check((boolean)get(f,"persistenceFailed"),"record mismatch still poisons");}
   try{f.recoveryStatus();throw new AssertionError("poison accepted");}catch(IllegalStateException expected){checks++;}
  }
  try(EmbeddedDuplexProcessFence f=fresh(root,"bounds")){
   android.system.Os.ftruncate((FileDescriptor)get(f,"descriptor"),513);try{f.nativeAdmissionRecord();throw new AssertionError("oversized accepted");}catch(IllegalStateException expected){check((boolean)get(f,"persistenceFailed"),"record bounds still fail closed");}
  }
  EmbeddedDuplexProcessFence closed=fresh(root,"closed");closed.close();check(!((FileChannel)get(closed,"channel")).isOpen()&&!((FileLock)get(closed,"lock")).isValid()&&!(boolean)get(closed,"persistenceFailed"),"explicit lifetime close unchanged");try{closed.nativeAdmissionRecord();throw new AssertionError("closed lock accepted");}catch(IllegalStateException expected){checks++;}
  try(EmbeddedDuplexProcessFence f=fresh(root,"released-lock")){((FileLock)get(f,"lock")).release();try{f.nativeAdmissionRecord();throw new AssertionError("invalid lock accepted");}catch(IllegalStateException expected){check(!(boolean)get(f,"persistenceFailed"),"invalid lock refusal separate from record poison");}}
  Path missing=root.resolve("never-created");try{android.system.Os.open(missing.toString(),android.system.OsConstants.O_RDWR|android.system.OsConstants.O_CLOEXEC|android.system.OsConstants.O_NOFOLLOW,0);throw new AssertionError("missing opened");}catch(IOException expected){check(!Files.exists(missing),"no-create flag contract leaves no target");}
  Path failed=Files.createDirectory(root.resolve("persist-fault"));AtomicBoolean failSync=new AtomicBoolean();try(EmbeddedDuplexProcessFence f=EmbeddedDuplexProcessFence.acquire(failed.toFile(),null,null,()->{if(failSync.get())throw new IOException("modeled durable-directory failure");})){
   failSync.set(true);try{f.beforeRuntimeEffects(null,null);throw new AssertionError("sync failure accepted");}catch(IOException expected){check((boolean)get(f,"persistenceFailed"),"directory persistence failure still poisons");}
  }
  try(EmbeddedDuplexProcessFence f=fresh(root,"owner-stop-successor")){
   AtomicInteger cycles=new AtomicInteger();io.github.mesmerprism.rustyquest.media.PreparedCaptureReadinessHost.runWithStopHook(()->{try{interruptedRead(f,true);cycles.incrementAndGet();}catch(Exception e){throw new IllegalStateException(e);}});check(cycles.get()==2,"two whole provider Start/Stop callbacks preserve same app fence");healthy(f);System.out.println("PASS whole prepared OwnerSet Start/Stop/cancel/successor-prepare/second-Start/Stop");
  }
  Path borrowed=root.resolve("borrowed-view");Files.createFile(borrowed);FileDescriptor fd=android.system.Os.open(borrowed.toString(),android.system.OsConstants.O_RDWR|android.system.OsConstants.O_CLOEXEC|android.system.OsConstants.O_NOFOLLOW,0);
  FileOutputStream view=new FileOutputStream(fd){@Override public void close(){/* Model Android's borrowed raw descriptor, no new FD. */}};
  Method close=EmbeddedDuplexProcessFence.class.getDeclaredMethod("closeDescriptor",FileOutputStream.class,FileDescriptor.class);close.setAccessible(true);close.invoke(null,view,fd);check(!fd.valid(),"borrowed stream view still closes fence-owned raw descriptor");
  Path borrowedFailure=root.resolve("borrowed-view-failure");Files.createFile(borrowedFailure);FileDescriptor failedFd=android.system.Os.open(borrowedFailure.toString(),android.system.OsConstants.O_RDWR|android.system.OsConstants.O_CLOEXEC|android.system.OsConstants.O_NOFOLLOW,0);IOException primary=new IOException("modeled borrowed close failure");FileOutputStream failedView=new FileOutputStream(failedFd){@Override public void close()throws IOException{throw primary;}};
  try{close.invoke(null,failedView,failedFd);throw new AssertionError("close failure lost");}catch(InvocationTargetException expected){check(expected.getCause()==primary,"primary view failure preserved");check(!failedFd.valid(),"failed borrowed view still releases raw descriptor");}
  Path acquisitionFailure=Files.createDirectory(root.resolve("acquisition-failure"));try{EmbeddedDuplexProcessFence.acquire(acquisitionFailure.toFile(),null,null,()->{throw new IOException("modeled initialization sync failure");});throw new AssertionError("acquisition failure lost");}catch(IOException expected){check(!android.system.Os.lastOpened.valid(),"failed acquisition closes exact owned raw descriptor");}
  System.out.println(checks+" whole production fence controls PASS; host classic I/O models Android Os, actual interrupt provenance unproven");
 }
}
