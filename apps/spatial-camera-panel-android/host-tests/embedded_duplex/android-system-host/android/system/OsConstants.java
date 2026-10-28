package android.system;
/** Host fixture constants for the exact Android public descriptor call. */
public final class OsConstants {
 public static final int O_RDONLY=0,O_RDWR=2,O_CREAT=64,O_TRUNC=512,O_NOFOLLOW=131072,O_CLOEXEC=524288;
 public static final int S_IFREG=32768;
 public static boolean S_ISREG(int mode){return(mode&61440)==S_IFREG;}
}
