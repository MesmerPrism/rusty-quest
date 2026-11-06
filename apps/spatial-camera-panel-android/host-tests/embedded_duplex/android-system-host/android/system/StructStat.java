package android.system;
/** Android native fstat is modeled; same actual host descriptor length is read. */
public final class StructStat {public final long st_size;public final int st_mode;StructStat(long size){st_size=size;st_mode=OsConstants.S_IFREG;}}
