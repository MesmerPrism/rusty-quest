package android.graphics;

import android.os.Handler;
import java.util.concurrent.CountDownLatch;

public final class SurfaceTexture {
    public interface OnFrameAvailableListener { void onFrameAvailable(SurfaceTexture texture); }
    private final Thread glOwner = Thread.currentThread();
    private volatile OnFrameAvailableListener listener;
    private volatile Handler handler;
    public final CountDownLatch detached = new CountDownLatch(1);
    public int textureUpdates;
    public SurfaceTexture(int textureName) { }
    public void setOnFrameAvailableListener(OnFrameAvailableListener value, Handler target) {
        handler = target; listener = value;
    }
    public void setOnFrameAvailableListener(OnFrameAvailableListener value) {
        listener = value;
        if (value == null) detached.countDown();
    }
    public void emit() {
        OnFrameAvailableListener callback = listener;
        Handler target = handler;
        if (callback != null) target.post(() -> callback.onFrameAvailable(this));
    }
    public void updateTexImage() {
        if (Thread.currentThread() != glOwner) throw new AssertionError("texture operation left GL owner");
        textureUpdates++;
    }
}
