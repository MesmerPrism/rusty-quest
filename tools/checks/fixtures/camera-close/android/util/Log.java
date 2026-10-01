package android.util;
public final class Log {
    public static String firstFailure;
    private Log() { }
    public static int i(String tag, String message) {
        if (message.contains("status=owner-first-failure") && firstFailure == null)
            firstFailure = message;
        return 0;
    }
}
