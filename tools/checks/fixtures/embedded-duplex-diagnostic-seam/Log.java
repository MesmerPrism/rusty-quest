package android.util;
/** JVM fixture: no Android log/device action. */
public final class Log {
    private Log() { }
    public static int i(String tag,String message) {
        if(message.contains("PRIVATE")) throw new AssertionError("private diagnostics leaked");
        return 0;
    }
}
