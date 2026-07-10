package io.github.mesmerprism.rustymanifold.broker;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.view.Surface;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** GPU-only OES snapshot and side-by-side encoder-surface compositor. */
final class RemoteCameraStereoGlCompositor implements Closeable {
    interface Listener {
        void onPairPresented(RemoteCameraStereoFramePairer.Pair pair, long presentationTimeUs);

        void onCompositorFailure(Throwable error);
    }

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final int RING_SIZE = 6;
    private static final long START_TIMEOUT_MS = 5_000L;

    private final Object signal = new Object();
    private final RemoteCameraPackedStreamMetadata.Layout layout;
    private final Surface encoderInputSurface;
    private final boolean synthetic;
    private final Listener listener;
    private final RemoteCameraStereoFramePairer pairer;
    private final CaptureCorrelation leftCorrelation = new CaptureCorrelation();
    private final CaptureCorrelation rightCorrelation = new CaptureCorrelation();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Thread thread;

    private volatile boolean stopRequested;
    private volatile Throwable startupFailure;
    private volatile Surface leftCameraSurface;
    private volatile Surface rightCameraSurface;
    private volatile boolean gpuCompositorActive;
    private volatile long composedFrames;
    private volatile long syntheticFrames;
    private volatile long leftSurfaceFrames;
    private volatile long rightSurfaceFrames;
    private volatile long leftUncorrelatedFrames;
    private volatile long rightUncorrelatedFrames;
    private volatile long compositorTimeTotalNs;
    private volatile long compositorTimeMaxNs;

    private int leftPending;
    private int rightPending;
    private SyntheticRequest syntheticRequest;

    RemoteCameraStereoGlCompositor(
            RemoteCameraPackedStreamMetadata.Layout layout,
            Surface encoderInputSurface,
            boolean synthetic,
            Listener listener) throws Exception {
        this.layout = layout;
        this.encoderInputSurface = encoderInputSurface;
        this.synthetic = synthetic;
        this.listener = listener;
        this.pairer = new RemoteCameraStereoFramePairer(RING_SIZE - 2, layout.maxPairDeltaNs);
        this.thread = new Thread(new Runnable() {
            @Override
            public void run() {
                RemoteCameraStereoGlCompositor.this.run();
            }
        }, "rusty-remote-camera-packed-gl");
        this.thread.start();
        if (!ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            close();
            throw new IllegalStateException("packed GL compositor startup timed out");
        }
        if (startupFailure != null) {
            close();
            throw new IllegalStateException("packed GL compositor startup failed", startupFailure);
        }
    }

    Surface leftCameraSurface() {
        return leftCameraSurface;
    }

    Surface rightCameraSurface() {
        return rightCameraSurface;
    }

    void recordCapture(String eye, long sourceFrame, long sensorTimestampNs) {
        correlation(eye).record(sourceFrame, sensorTimestampNs);
    }

    void requestSyntheticFrame(long sourceFrame, long leftTimestampNs, long rightTimestampNs) {
        synchronized (signal) {
            syntheticRequest = new SyntheticRequest(sourceFrame, leftTimestampNs, rightTimestampNs);
            signal.notifyAll();
        }
    }

    RemoteCameraStereoFramePairer.Snapshot pairerSnapshot() {
        return pairer.snapshot();
    }

    boolean gpuCompositorActive() {
        return gpuCompositorActive;
    }

    long composedFrames() {
        return composedFrames;
    }

    long syntheticFrames() {
        return syntheticFrames;
    }

    long leftSurfaceFrames() {
        return leftSurfaceFrames;
    }

    long rightSurfaceFrames() {
        return rightSurfaceFrames;
    }

    long leftUncorrelatedFrames() {
        return leftUncorrelatedFrames;
    }

    long rightUncorrelatedFrames() {
        return rightUncorrelatedFrames;
    }

    long compositorTimeAverageNs() {
        return composedFrames > 0L ? compositorTimeTotalNs / composedFrames : 0L;
    }

    long compositorTimeMaxNs() {
        return compositorTimeMaxNs;
    }

    @Override
    public void close() {
        stopRequested = true;
        synchronized (signal) {
            signal.notifyAll();
        }
        thread.interrupt();
        try {
            thread.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        pairer.clear();
    }

    private void run() {
        GlState gl = null;
        try {
            gl = new GlState(layout, encoderInputSurface, !synthetic);
            if (!synthetic) {
                leftCameraSurface = gl.leftInput.cameraSurface;
                rightCameraSurface = gl.rightInput.cameraSurface;
                gl.leftInput.surfaceTexture.setOnFrameAvailableListener(
                        new SurfaceTexture.OnFrameAvailableListener() {
                            @Override
                            public void onFrameAvailable(SurfaceTexture texture) {
                                synchronized (signal) {
                                    leftPending++;
                                    signal.notifyAll();
                                }
                            }
                        });
                gl.rightInput.surfaceTexture.setOnFrameAvailableListener(
                        new SurfaceTexture.OnFrameAvailableListener() {
                            @Override
                            public void onFrameAvailable(SurfaceTexture texture) {
                                synchronized (signal) {
                                    rightPending++;
                                    signal.notifyAll();
                                }
                            }
                        });
            }
            gpuCompositorActive = true;
        } catch (Throwable error) {
            startupFailure = error;
        } finally {
            ready.countDown();
        }
        if (startupFailure != null) {
            notifyFailure(startupFailure);
            if (gl != null) {
                gl.close();
            }
            return;
        }

        try {
            while (!stopRequested) {
                int consumeLeft;
                int consumeRight;
                SyntheticRequest request;
                synchronized (signal) {
                    while (!stopRequested
                            && leftPending == 0
                            && rightPending == 0
                            && syntheticRequest == null) {
                        signal.wait(50L);
                    }
                    consumeLeft = leftPending;
                    consumeRight = rightPending;
                    leftPending = 0;
                    rightPending = 0;
                    request = syntheticRequest;
                    syntheticRequest = null;
                }
                if (stopRequested) {
                    break;
                }
                if (request != null) {
                    renderSynthetic(gl, request);
                }
                if (consumeLeft > 0) {
                    leftSurfaceFrames += consumeLeft;
                    consumeCameraFrame(gl, gl.leftInput, leftCorrelation, RemoteCameraStereoFramePairer.LEFT);
                }
                if (consumeRight > 0) {
                    rightSurfaceFrames += consumeRight;
                    consumeCameraFrame(gl, gl.rightInput, rightCorrelation, RemoteCameraStereoFramePairer.RIGHT);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            notifyFailure(error);
        } finally {
            gpuCompositorActive = false;
            gl.close();
        }
    }

    private void consumeCameraFrame(
            GlState gl,
            InputState input,
            CaptureCorrelation correlation,
            String eye) throws Exception {
        gl.makePbufferCurrent();
        input.surfaceTexture.updateTexImage();
        long timestampNs = input.surfaceTexture.getTimestamp();
        float[] transform = new float[16];
        input.surfaceTexture.getTransformMatrix(transform);
        int slot = input.nextSlot();
        pairer.discardTextureSlot(eye, slot);
        gl.snapshotExternal(input.externalTexture, transform, input.snapshotTextures[slot]);
        CaptureFrame capture = correlation.match(timestampNs, 20L);
        if (capture == null) {
            if (RemoteCameraStereoFramePairer.LEFT.equals(eye)) {
                leftUncorrelatedFrames++;
            } else {
                rightUncorrelatedFrames++;
            }
            return;
        }
        RemoteCameraStereoFramePairer.Pair pair = pairer.add(
                new RemoteCameraStereoFramePairer.Candidate(
                        eye,
                        capture.sourceFrame,
                        capture.sensorTimestampNs,
                        slot,
                        SystemClock.elapsedRealtimeNanos()),
                SystemClock.elapsedRealtimeNanos());
        if (pair != null) {
            composePair(gl, pair);
        }
    }

    private void renderSynthetic(GlState gl, SyntheticRequest request) throws Exception {
        gl.makePbufferCurrent();
        int leftSlot = gl.leftInput.nextSlot();
        int rightSlot = gl.rightInput.nextSlot();
        pairer.discardTextureSlot(RemoteCameraStereoFramePairer.LEFT, leftSlot);
        pairer.discardTextureSlot(RemoteCameraStereoFramePairer.RIGHT, rightSlot);
        gl.drawSynthetic(gl.leftInput.snapshotTextures[leftSlot], true, request.sourceFrame);
        gl.drawSynthetic(gl.rightInput.snapshotTextures[rightSlot], false, request.sourceFrame);
        long queuedNs = SystemClock.elapsedRealtimeNanos();
        pairer.add(
                new RemoteCameraStereoFramePairer.Candidate(
                        RemoteCameraStereoFramePairer.LEFT,
                        request.sourceFrame,
                        request.leftTimestampNs,
                        leftSlot,
                        queuedNs),
                queuedNs);
        RemoteCameraStereoFramePairer.Pair pair = pairer.add(
                new RemoteCameraStereoFramePairer.Candidate(
                        RemoteCameraStereoFramePairer.RIGHT,
                        request.sourceFrame,
                        request.rightTimestampNs,
                        rightSlot,
                        queuedNs),
                SystemClock.elapsedRealtimeNanos());
        if (pair != null) {
            syntheticFrames++;
            composePair(gl, pair);
        }
    }

    private void composePair(GlState gl, RemoteCameraStereoFramePairer.Pair pair) throws Exception {
        long startNs = SystemClock.elapsedRealtimeNanos();
        int leftTexture = gl.leftInput.snapshotTextures[pair.left.textureSlot];
        int rightTexture = gl.rightInput.snapshotTextures[pair.right.textureSlot];
        long presentationNs = Math.max(
                pair.left.sensorTimestampNs,
                pair.right.sensorTimestampNs);
        gl.compose(leftTexture, rightTexture, presentationNs);
        long elapsedNs = Math.max(0L, SystemClock.elapsedRealtimeNanos() - startNs);
        composedFrames++;
        compositorTimeTotalNs += elapsedNs;
        compositorTimeMaxNs = Math.max(compositorTimeMaxNs, elapsedNs);
        listener.onPairPresented(pair, presentationNs / 1_000L);
    }

    private CaptureCorrelation correlation(String eye) {
        if (RemoteCameraStereoFramePairer.LEFT.equals(eye)) {
            return leftCorrelation;
        }
        if (RemoteCameraStereoFramePairer.RIGHT.equals(eye)) {
            return rightCorrelation;
        }
        throw new IllegalArgumentException("unsupported eye " + eye);
    }

    private void notifyFailure(Throwable error) {
        try {
            listener.onCompositorFailure(error);
        } catch (Throwable ignored) {
            // Runtime failure evidence remains owned by the source runtime.
        }
    }

    private static final class SyntheticRequest {
        final long sourceFrame;
        final long leftTimestampNs;
        final long rightTimestampNs;

        SyntheticRequest(long sourceFrame, long leftTimestampNs, long rightTimestampNs) {
            this.sourceFrame = sourceFrame;
            this.leftTimestampNs = leftTimestampNs;
            this.rightTimestampNs = rightTimestampNs;
        }
    }

    private static final class CaptureFrame {
        final long sourceFrame;
        final long sensorTimestampNs;

        CaptureFrame(long sourceFrame, long sensorTimestampNs) {
            this.sourceFrame = sourceFrame;
            this.sensorTimestampNs = sensorTimestampNs;
        }
    }

    private static final class CaptureCorrelation {
        private final ArrayDeque<CaptureFrame> captures = new ArrayDeque<>();

        synchronized void record(long sourceFrame, long sensorTimestampNs) {
            if (sourceFrame <= 0L || sensorTimestampNs <= 0L) {
                return;
            }
            captures.addLast(new CaptureFrame(sourceFrame, sensorTimestampNs));
            while (captures.size() > 16) {
                captures.removeFirst();
            }
            notifyAll();
        }

        synchronized CaptureFrame match(long surfaceTimestampNs, long waitMs)
                throws InterruptedException {
            long deadline = SystemClock.elapsedRealtime() + waitMs;
            while (true) {
                CaptureFrame best = null;
                long bestDelta = Long.MAX_VALUE;
                for (CaptureFrame capture : captures) {
                    long delta = capture.sensorTimestampNs >= surfaceTimestampNs
                            ? capture.sensorTimestampNs - surfaceTimestampNs
                            : surfaceTimestampNs - capture.sensorTimestampNs;
                    if (delta < bestDelta) {
                        best = capture;
                        bestDelta = delta;
                    }
                }
                if (best != null && bestDelta <= 2_000_000L) {
                    captures.remove(best);
                    return best;
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    return null;
                }
                wait(remaining);
            }
        }
    }

    private static final class InputState implements Closeable {
        final int externalTexture;
        final SurfaceTexture surfaceTexture;
        final Surface cameraSurface;
        final int[] snapshotTextures = new int[RING_SIZE];
        private int nextSlot;

        InputState(int width, int height, boolean cameraInput) {
            externalTexture = cameraInput ? createExternalTexture() : 0;
            if (cameraInput) {
                surfaceTexture = new SurfaceTexture(externalTexture);
                surfaceTexture.setDefaultBufferSize(width, height);
                cameraSurface = new Surface(surfaceTexture);
            } else {
                surfaceTexture = null;
                cameraSurface = null;
            }
            GLES20.glGenTextures(RING_SIZE, snapshotTextures, 0);
            for (int texture : snapshotTextures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES20.GL_RGBA,
                        width,
                        height,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        null);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        int nextSlot() {
            int slot = nextSlot;
            nextSlot = (nextSlot + 1) % RING_SIZE;
            return slot;
        }

        @Override
        public void close() {
            if (cameraSurface != null) {
                cameraSurface.release();
            }
            if (surfaceTexture != null) {
                surfaceTexture.release();
            }
            if (externalTexture != 0) {
                GLES20.glDeleteTextures(1, new int[] {externalTexture}, 0);
            }
            GLES20.glDeleteTextures(snapshotTextures.length, snapshotTextures, 0);
        }

        private static int createExternalTexture() {
            int[] texture = new int[1];
            GLES20.glGenTextures(1, texture, 0);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture[0]);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
            return texture[0];
        }
    }

    private static final class GlState implements Closeable {
        private static final float[] QUAD = {
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f
        };

        final RemoteCameraPackedStreamMetadata.Layout layout;
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface pbufferSurface;
        final EGLSurface encoderSurface;
        final InputState leftInput;
        final InputState rightInput;
        final int oesProgram;
        final int textureProgram;
        final int framebuffer;
        final java.nio.FloatBuffer quadBuffer;

        GlState(
                RemoteCameraPackedStreamMetadata.Layout layout,
                Surface encoderInputSurface,
                boolean cameraInput) {
            this.layout = layout;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            require(EGL14.eglInitialize(display, versions, 0, versions, 1), "eglInitialize");
            int[] configAttributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCount = new int[1];
            require(EGL14.eglChooseConfig(
                    display,
                    configAttributes,
                    0,
                    configs,
                    0,
                    configs.length,
                    configCount,
                    0) && configCount[0] > 0, "eglChooseConfig");
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    contextAttributes,
                    0);
            require(context != null && context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
            int[] pbufferAttributes = {
                    EGL14.EGL_WIDTH, layout.perEyeWidth,
                    EGL14.EGL_HEIGHT, layout.perEyeHeight,
                    EGL14.EGL_NONE
            };
            pbufferSurface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttributes, 0);
            int[] windowAttributes = {EGL14.EGL_NONE};
            encoderSurface = EGL14.eglCreateWindowSurface(
                    display,
                    configs[0],
                    encoderInputSurface,
                    windowAttributes,
                    0);
            makePbufferCurrent();
            oesProgram = createProgram(
                    vertexShader(),
                    "#extension GL_OES_EGL_image_external : require\n"
                            + "precision mediump float; varying vec2 vUv; uniform samplerExternalOES uTexture;"
                            + "void main(){ gl_FragColor=texture2D(uTexture,vUv); }");
            textureProgram = createProgram(
                    vertexShader(),
                    "precision mediump float; varying vec2 vUv; uniform sampler2D uTexture;"
                            + "void main(){ gl_FragColor=texture2D(uTexture,vUv); }");
            int[] framebuffers = new int[1];
            GLES20.glGenFramebuffers(1, framebuffers, 0);
            framebuffer = framebuffers[0];
            quadBuffer = java.nio.ByteBuffer
                    .allocateDirect(QUAD.length * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer();
            quadBuffer.put(QUAD).position(0);
            leftInput = new InputState(layout.perEyeWidth, layout.perEyeHeight, cameraInput);
            rightInput = new InputState(layout.perEyeWidth, layout.perEyeHeight, cameraInput);
        }

        void makePbufferCurrent() {
            require(EGL14.eglMakeCurrent(
                    display,
                    pbufferSurface,
                    pbufferSurface,
                    context), "eglMakeCurrent pbuffer");
        }

        void snapshotExternal(int externalTexture, float[] transform, int targetTexture) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    targetTexture,
                    0);
            checkFramebuffer();
            GLES20.glViewport(0, 0, layout.perEyeWidth, layout.perEyeHeight);
            drawTexture(oesProgram, GL_TEXTURE_EXTERNAL_OES, externalTexture, transform);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        void drawSynthetic(int targetTexture, boolean left, long frame) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    targetTexture,
                    0);
            checkFramebuffer();
            GLES20.glViewport(0, 0, layout.perEyeWidth, layout.perEyeHeight);
            if (left) {
                GLES20.glClearColor(0.85f, 0.06f, 0.03f, 1f);
            } else {
                GLES20.glClearColor(0.03f, 0.12f, 0.85f, 1f);
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            int moving = (int) (frame % Math.max(1, layout.perEyeWidth - 24));
            GLES20.glScissor(moving, 16, 24, Math.max(1, layout.perEyeHeight - 32));
            GLES20.glClearColor(left ? 1f : 0f, 1f, left ? 0f : 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glScissor(
                    left ? layout.perEyeWidth - 4 : 0,
                    0,
                    4,
                    layout.perEyeHeight);
            GLES20.glClearColor(1f, 1f, 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        void compose(int leftTexture, int rightTexture, long presentationNs) {
            require(EGL14.eglMakeCurrent(
                    display,
                    encoderSurface,
                    encoderSurface,
                    context), "eglMakeCurrent encoder");
            GLES20.glViewport(0, 0, layout.packedWidth, layout.packedHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glViewport(0, 0, layout.perEyeWidth, layout.perEyeHeight);
            drawTexture(textureProgram, GLES20.GL_TEXTURE_2D, leftTexture, identity());
            GLES20.glViewport(
                    layout.perEyeWidth,
                    0,
                    layout.perEyeWidth,
                    layout.perEyeHeight);
            drawTexture(textureProgram, GLES20.GL_TEXTURE_2D, rightTexture, identity());
            EGLExt.eglPresentationTimeANDROID(display, encoderSurface, presentationNs);
            require(EGL14.eglSwapBuffers(display, encoderSurface), "eglSwapBuffers");
        }

        private void drawTexture(int program, int target, int texture, float[] matrix) {
            GLES20.glUseProgram(program);
            int position = GLES20.glGetAttribLocation(program, "aPosition");
            int texCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            int matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix");
            int textureLocation = GLES20.glGetUniformLocation(program, "uTexture");
            quadBuffer.position(0);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
            GLES20.glEnableVertexAttribArray(position);
            quadBuffer.position(2);
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer);
            GLES20.glEnableVertexAttribArray(texCoord);
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(target, texture);
            GLES20.glUniform1i(textureLocation, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glBindTexture(target, 0);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(texCoord);
        }

        @Override
        public void close() {
            try {
                makePbufferCurrent();
                leftInput.close();
                rightInput.close();
                GLES20.glDeleteFramebuffers(1, new int[] {framebuffer}, 0);
                GLES20.glDeleteProgram(oesProgram);
                GLES20.glDeleteProgram(textureProgram);
            } catch (Throwable ignored) {
            }
            EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, encoderSurface);
            EGL14.eglDestroySurface(display, pbufferSurface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }

        private static String vertexShader() {
            return "attribute vec2 aPosition; attribute vec2 aTexCoord; uniform mat4 uTexMatrix;"
                    + "varying vec2 vUv; void main(){ gl_Position=vec4(aPosition,0.0,1.0);"
                    + "vUv=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy; }";
        }

        private static int createProgram(String vertex, String fragment) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            if (status[0] == 0) {
                throw new IllegalStateException("GL program link failed: " + GLES20.glGetProgramInfoLog(program));
            }
            return program;
        }

        private static int compileShader(int kind, String source) {
            int shader = GLES20.glCreateShader(kind);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                throw new IllegalStateException("GL shader compile failed: " + GLES20.glGetShaderInfoLog(shader));
            }
            return shader;
        }

        private static void checkFramebuffer() {
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("incomplete GL framebuffer " + status);
            }
        }

        private static float[] identity() {
            return new float[] {
                    1f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f
            };
        }

        private static void require(boolean condition, String label) {
            if (!condition) {
                throw new IllegalStateException(label + " failed eglError=0x"
                        + Integer.toHexString(EGL14.eglGetError()));
            }
        }
    }
}
