package android.os;
public class SystemClock {
    public static long elapsedRealtime() { return 1L; }
    public static long elapsedRealtimeNanos() { return System.nanoTime(); }
}
