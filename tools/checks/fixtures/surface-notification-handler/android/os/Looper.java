package android.os;

public final class Looper {
    private static final Looper MAIN = new Looper();
    static final ThreadLocal<Looper> CURRENT = new ThreadLocal<>();
    HandlerThread owner;
    public static Looper myLooper() { return CURRENT.get(); }
    public static Looper getMainLooper() { return MAIN; }
}
