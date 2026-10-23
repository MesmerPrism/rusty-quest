package android.system;
import java.io.*;import java.nio.file.*;import java.util.*;
/** Explicit host model of Android Os over one real classic-I/O descriptor.
 * Android's atomic O_NOFOLLOW is not executed on this host; flag contract and
 * pre-open regular/no-link/missing refusal are enforced by this fixture. */
public final class Os {
 private static volatile java.util.concurrent.CountDownLatch readEntered;
 private static volatile java.util.concurrent.atomic.AtomicBoolean readReleased;
 public static volatile FileDescriptor lastOpened;
 public static void blockNextRead(){readEntered=new java.util.concurrent.CountDownLatch(1);readReleased=new java.util.concurrent.atomic.AtomicBoolean();}
 public static boolean awaitRead()throws InterruptedException{return readEntered.await(5,java.util.concurrent.TimeUnit.SECONDS);}
 public static void releaseRead(){readReleased.set(true);}
 private static final Map<FileDescriptor,RandomAccessFile> FILES=Collections.synchronizedMap(new IdentityHashMap<>());
 public static FileDescriptor open(String path,int flags,int mode)throws IOException{
  if(flags!=(OsConstants.O_RDWR|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW)||mode!=0)throw new IOException("host requires no-create/no-truncate/nofollow flags");
  Path p=Path.of(path);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p))throw new FileNotFoundException("host no-follow regular file required");
  RandomAccessFile f=new RandomAccessFile(path,"rw");FileDescriptor fd=f.getFD();FILES.put(fd,f);lastOpened=fd;return fd;
 }
 private static RandomAccessFile file(FileDescriptor fd)throws IOException{RandomAccessFile f=FILES.get(fd);if(f==null||!fd.valid())throw new IOException("host descriptor closed");return f;}
 public static StructStat fstat(FileDescriptor fd)throws IOException{return new StructStat(file(fd).length());}
 public static int pread(FileDescriptor fd,byte[]data,int offset,int count,long position)throws IOException{
  java.util.concurrent.CountDownLatch entered=readEntered;java.util.concurrent.atomic.AtomicBoolean released=readReleased;
  if(entered!=null){entered.countDown();while(!released.get())Thread.onSpinWait();readEntered=null;}
  RandomAccessFile f=file(fd);synchronized(f){f.seek(position);return Math.max(0,f.read(data,offset,count));}}
 public static int pwrite(FileDescriptor fd,byte[]data,int offset,int count,long position)throws IOException{RandomAccessFile f=file(fd);synchronized(f){f.seek(position);f.write(data,offset,count);return count;}}
 public static void ftruncate(FileDescriptor fd,long length)throws IOException{file(fd).setLength(length);}
 public static void fsync(FileDescriptor fd)throws IOException{file(fd).getFD().sync();}
 public static void close(FileDescriptor fd)throws IOException{file(fd).close();FILES.remove(fd);}
}
