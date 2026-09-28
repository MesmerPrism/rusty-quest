package io.github.mesmerprism.rustyquest.media;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One physical encoder input; a stalled codec never blocks the capture actor. */
public final class PackedStereoEncoderWorker implements PackedStereoCaptureOwner.EncoderConsumer {
    public interface Listener {
        // Metadata registration precedes submission so output draining cannot race its identity.
        void onPairSubmitting(PackedStereoPoolExecutor.PairIdentity pair, long ptsUs);
        void onFailure(Throwable failure);
    }
    private final long generation;
    private final Surface surface;
    private final Listener listener;
    private final PackedStereoEncoderMailbox<PackedStereoEncoderInput> mailbox;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Thread thread;
    private volatile boolean stopRequested, physicallyRetired;
    private volatile Throwable failure;
    private volatile GlState quarantinedGl;
    private volatile PackedStereoEncoderInput quarantinedInput;

    public PackedStereoEncoderWorker(long generation, Surface surface, Listener listener) throws Exception {
        if (generation <= 0 || surface == null || listener == null)
            throw new IllegalArgumentException("encoder worker binding");
        this.generation = generation; this.surface = surface; this.listener = listener;
        this.mailbox = new PackedStereoEncoderMailbox<>(generation);
        this.thread = new Thread(new Runnable() {
            @Override public void run() { PackedStereoEncoderWorker.this.run(); }
        }, "rusty-packed-stereo-encoder-input");
        thread.start();
    }
    public void awaitStarted() throws Exception {
        if (!ready.await(5, TimeUnit.SECONDS)) {
            requestStop(); throw new IllegalStateException("encoder initialization Pending");
        }
        if (failure != null) throw new IllegalStateException("encoder initialization failed", failure);
    }
    @Override public void offer(PackedStereoEncoderInput frame) { mailbox.offer(generation, frame); }
    public void requestStop() { stopRequested = true; mailbox.stopAccepting(); }
    public boolean isPhysicallyRetired() { return physicallyRetired && !thread.isAlive(); }
    public Throwable failure() { return failure; }

    private void run() {
        GlState gl = null;
        PackedStereoEncoderInput input = null;
        boolean imported = false;
        try {
            gl = new GlState();
            gl.initialize(surface);
            ready.countDown();
            while (!stopRequested) {
                input = mailbox.take();
                if (input == null) break;
                if (stopRequested) { input.releaseUnsubmitted(); input = null; break; }
                // Import registers pool-owned GPU retention BEFORE any possible sampling.
                imported = true;
                PackedStereoEncoderInput.Imported image = input.importOnCurrentContext();
                PackedStereoPoolExecutor.PairIdentity pair = input.pair();
                listener.onPairSubmitting(pair, pair.packedPtsNs / 1000L);
                gl.draw(image, pair.packedPtsNs);
                input.finishAfterSubmission();
                // Do not take another input until actual fence retirement and same-context import teardown.
                while (!input.pollRetired()) pause();
                imported = false; input = null;
            }
        } catch (Throwable error) {
            failure = error;
            requestStop();
            try { listener.onFailure(error); } catch (Throwable ignored) { }
            if (input != null && imported) {
                try {
                    input.finishAfterSubmission();
                    while (!input.pollRetired()) pause();
                    imported = false; input = null;
                } catch (Throwable uncertainFence) {
                    // Never destroy the context or return pinned content after an uncertain submission.
                    quarantinedInput = input; quarantinedGl = gl;
                }
            }
        } finally {
            ready.countDown(); mailbox.stopAccepting();
            if (input != null && !imported) input.releaseUnsubmitted();
            if (quarantinedInput == null) {
                try {
                    if (gl != null) gl.close();
                    physicallyRetired = true;
                } catch (Throwable uncertainCleanup) {
                    failure = uncertainCleanup; quarantinedGl = gl;
                    // No terminal claim if the worker's EGL resources did not retire.
                }
            }
        }
    }
    private static void pause() {
        try { Thread.sleep(10); }
        catch (InterruptedException ignored) { /* interruption never retires a native fence */ }
    }

    private static final class GlState {
        final EGLDisplay display;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface window = EGL14.EGL_NO_SURFACE;
        int program;
        final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().put(new float[] {-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1});
        GlState() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            require(display != EGL14.EGL_NO_DISPLAY, "encoder EGL display");
        }
        void initialize(Surface surface) {
                int[] version = new int[2];
                require(EGL14.eglInitialize(display, version, 0, version, 1), "encoder EGL initialize");
                int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                        EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                        EGL14.EGL_ALPHA_SIZE,8,0x3142,1,EGL14.EGL_NONE};
                EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
                require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                        && count[0] > 0, "encoder EGL config");
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                        new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE}, 0);
                require(context != EGL14.EGL_NO_CONTEXT, "encoder EGL context");
                window = EGL14.eglCreateWindowSurface(display, configs[0], surface,
                        new int[] {EGL14.EGL_NONE}, 0);
                require(window != EGL14.EGL_NO_SURFACE, "encoder EGL surface");
                require(EGL14.eglMakeCurrent(display, window, window, context), "encoder EGL current");
                int vertex = shader(GLES20.GL_VERTEX_SHADER,
                        "attribute vec2 position;attribute vec2 uv;varying vec2 v;void main(){v=uv;gl_Position=vec4(position,0.,1.);}");
                int fragment = 0;
                try {
                    fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                            "precision mediump float;varying vec2 v;uniform sampler2D packed;void main(){gl_FragColor=texture2D(packed,v);}");
                    program = GLES20.glCreateProgram();
                    GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment);
                    GLES20.glLinkProgram(program); int[] linked = new int[1];
                    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
                    require(linked[0] != 0, "encoder GL link");
                } finally { GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment); }
        }
        void draw(PackedStereoEncoderInput.Imported image, long ptsNs) {
            require(EGL14.eglMakeCurrent(display, window, window, context), "encoder EGL current");
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, image.width, image.height);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(program);
            int position = GLES20.glGetAttribLocation(program, "position");
            int uv = GLES20.glGetAttribLocation(program, "uv");
            vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices);
            vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(uv);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, image.texture);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "packed"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            require(GLES20.glGetError() == GLES20.GL_NO_ERROR, "encoder packed sampling");
            require(EGLExt.eglPresentationTimeANDROID(display, window, ptsNs), "encoder PTS");
            require(EGL14.eglSwapBuffers(display, window), "encoder surface submission");
        }
        static int shader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
            int[] compiled = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) { GLES20.glDeleteShader(shader); throw new IllegalStateException("encoder GL shader"); }
            return shader;
        }
        void close() {
            if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, window, window, context);
                if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) {
                require(EGL14.eglDestroySurface(display, window), "encoder EGL surface retirement");
                window = EGL14.EGL_NO_SURFACE;
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                require(EGL14.eglDestroyContext(display, context), "encoder EGL context retirement");
                context = EGL14.EGL_NO_CONTEXT;
            }
            // EGLDisplay is process-shared. Destroy only this worker's resources.
            EGL14.eglReleaseThread();
        }
        static void require(boolean ok, String what) { if (!ok) throw new IllegalStateException(what); }
    }
}
