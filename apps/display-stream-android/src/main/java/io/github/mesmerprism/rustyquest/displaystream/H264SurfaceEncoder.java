package io.github.mesmerprism.rustyquest.displaystream;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

final class H264SurfaceEncoder implements Closeable {
    interface PacketSink {
        void write(long presentationTimeUs, int flags, ByteBuffer payload) throws Exception;
    }

    private static final String MIME = "video/avc";
    private final MediaCodec codec;
    private final Surface inputSurface;
    private boolean closed;

    private H264SurfaceEncoder(MediaCodec codec, Surface inputSurface) {
        this.codec = codec;
        this.inputSurface = inputSurface;
    }

    static H264SurfaceEncoder create(int width, int height, int bitrateBps, int fps)
            throws IOException {
        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        Surface surface = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            try {
                format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps);
            } catch (RuntimeException ignored) {
                // Some vendor encoders do not accept the surface-input frame-rate cap.
            }
            if (Build.VERSION.SDK_INT >= 23) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            }
            if (Build.VERSION.SDK_INT >= 26) {
                format.setInteger(MediaFormat.KEY_LATENCY, 1);
            }
            try {
                format.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
            } catch (RuntimeException ignored) {
                // Optional encoder tuning is device-dependent.
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            return new H264SurfaceEncoder(codec, surface);
        } catch (Exception error) {
            boolean uncertain = false;
            if (surface != null) {
                try { surface.release(); }
                catch (RuntimeException cleanup) { error.addSuppressed(cleanup); uncertain = true; }
            }
            try { codec.release(); }
            catch (RuntimeException cleanup) { error.addSuppressed(cleanup); uncertain = true; }
            if (uncertain) throw new DisplayStreamAttempt.UncertainReleaseException(error);
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("H.264 surface encoder setup failed", error);
        }
    }

    Surface inputSurface() {
        return inputSurface;
    }

    String encoderName() {
        return codec.getName();
    }

    void requestSyncFrame() {
        try {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(parameters);
        } catch (RuntimeException ignored) {
            // Best effort only.
        }
    }

    void signalEndOfInputStream() {
        codec.signalEndOfInputStream();
    }

    boolean drainAvailable(long timeoutUs, PacketSink sink) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean endOfStream = false;
        long nextTimeout = timeoutUs;
        while (true) {
            int index = codec.dequeueOutputBuffer(info, nextTimeout);
            nextTimeout = 0;
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return endOfStream;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || index < 0) {
                continue;
            }
            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    // EOS belongs to codec draining/transport EOF. RMANVID1 v1
                    // carries only config/key bits, including a final media payload.
                    sink.write(info.presentationTimeUs,
                        info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM, buffer);
                }
                endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
            if (endOfStream) {
                return true;
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try { codec.stop(); } catch (RuntimeException error) { failure = error; }
        try { inputSurface.release(); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        try { codec.release(); } catch (RuntimeException error) {
            if (failure == null) failure = error; else failure.addSuppressed(error);
        }
        if (failure != null) throw new IOException("encoder retirement unconfirmed", failure);
    }
}
