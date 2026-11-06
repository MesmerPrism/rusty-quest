package io.github.mesmerprism.rustyquest.displaystream;

import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class HiddenDisplayMirror implements Closeable {
    static final class DisplayInfo {
        final int width;
        final int height;
        final int layerStack;

        DisplayInfo(int width, int height, int layerStack) {
            this.width = width;
            this.height = height;
            this.layerStack = layerStack;
        }
    }

    private final IBinder surfaceControlToken;
    private final Object virtualDisplay;

    private HiddenDisplayMirror(IBinder surfaceControlToken, Object virtualDisplay) {
        this.surfaceControlToken = surfaceControlToken;
        this.virtualDisplay = virtualDisplay;
    }

    static DisplayInfo displayInfo(int displayId) throws Exception {
        Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object manager = globalClass.getDeclaredMethod("getInstance").invoke(null);
        Object info = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, displayId);
        if (info == null) {
            throw new IllegalArgumentException("Display " + displayId + " is unavailable");
        }
        return new DisplayInfo(
            intField(info, "logicalWidth"),
            intField(info, "logicalHeight"),
            intField(info, "layerStack"));
    }

    static HiddenDisplayMirror start(
            int displayId,
            int width,
            int height,
            Surface surface) throws Exception {
        DisplayInfo info = displayInfo(displayId);
        Exception displayManagerFailure = null;
        try {
            Class<?> displayManager = Class.forName("android.hardware.display.DisplayManager");
            Object virtualDisplay = displayManager.getMethod(
                "createVirtualDisplay",
                String.class,
                int.class,
                int.class,
                int.class,
                Surface.class).invoke(null, "RustyQuestShellDisplayMirror", width, height, displayId, surface);
            if (virtualDisplay == null) throw new IllegalStateException("display allocation absent");
            return new HiddenDisplayMirror(null, virtualDisplay);
        } catch (Exception error) {
            displayManagerFailure = error;
        }

        Exception surfaceControlFailure = null;
        IBinder allocatedToken = null;
        try {
            Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
            IBinder token = (IBinder) surfaceControl
                .getMethod("createDisplay", String.class, boolean.class)
                .invoke(null, "RustyQuestShellDisplayMirror", false);
            allocatedToken = token;
            Method open = surfaceControl.getMethod("openTransaction");
            Method close = surfaceControl.getMethod("closeTransaction");
            open.invoke(null);
            try {
                surfaceControl.getMethod("setDisplaySurface", IBinder.class, Surface.class)
                    .invoke(null, token, surface);
                surfaceControl.getMethod(
                    "setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                    .invoke(null, token, 0, new Rect(0, 0, info.width, info.height), new Rect(0, 0, width, height));
                surfaceControl.getMethod("setDisplayLayerStack", IBinder.class, int.class)
                    .invoke(null, token, info.layerStack);
            } finally {
                close.invoke(null);
            }
            return new HiddenDisplayMirror(token, null);
        } catch (Exception error) {
            surfaceControlFailure = error;
            if (allocatedToken != null) {
                try { new HiddenDisplayMirror(allocatedToken, null).close(); }
                catch (Exception cleanup) {
                    error.addSuppressed(cleanup);
                    throw new DisplayStreamAttempt.UncertainReleaseException(error);
                }
            }
        }
        surfaceControlFailure.addSuppressed(displayManagerFailure);
        throw surfaceControlFailure;
    }

    @Override
    public void close() throws java.io.IOException {
        Exception failure = null;
        if (surfaceControlToken != null) {
            try { Class.forName("android.view.SurfaceControl").getMethod("destroyDisplay", IBinder.class)
                    .invoke(null, surfaceControlToken); }
            catch (Exception error) { failure = error; }
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.getClass().getMethod("release").invoke(virtualDisplay); }
            catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw new java.io.IOException("display release unconfirmed", failure);
    }

    private static int intField(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(instance);
    }

}
