package io.github.mesmerprism.rustymanifold.broker;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Size;
import android.view.Surface;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

final class MediaCodecSurfaceEncoder implements Closeable {
    interface PacketSink {
        void writePacket(long presentationTimeUs, int flags, byte[] payload) throws Exception;
    }

    interface StopSignal {
        boolean stopRequested();
    }

    private static final String MIME_H264 = "video/avc";
    private static final int ENCODER_DRAIN_TIMEOUT_US = 10_000;

    private final MediaCodec encoder;
    private final Surface inputSurface;
    private boolean stopped;

    private MediaCodecSurfaceEncoder(MediaCodec encoder, Surface inputSurface) {
        this.encoder = encoder;
        this.inputSurface = inputSurface;
    }

    static MediaCodecSurfaceEncoder create(Size size, int bitrateBps, int frameRateHz)
            throws IOException {
        MediaCodec encoder = MediaCodec.createEncoderByType(MIME_H264);
        try {
            configureEncoder(encoder, size, bitrateBps, frameRateHz);
            Surface inputSurface = encoder.createInputSurface();
            encoder.start();
            return new MediaCodecSurfaceEncoder(encoder, inputSurface);
        } catch (Exception ex) {
            try {
                encoder.release();
            } catch (Exception ignored) {
                // Best-effort cleanup after encoder setup failure.
            }
            if (ex instanceof IOException) {
                throw (IOException) ex;
            }
            throw new IOException("MediaCodec surface encoder setup failed: " + ex.getMessage(), ex);
        }
    }

    Surface inputSurface() {
        return inputSurface;
    }

    void requestSyncFrame() {
        try {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(parameters);
        } catch (Exception ignored) {
            // Sync-frame requests are best effort.
        }
    }

    void signalEndOfInputStream() {
        encoder.signalEndOfInputStream();
    }

    void drain(boolean endOfStream, StopSignal stopSignal, PacketSink sink) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int emptyPolls = 0;
        while (!stopSignal.stopRequested() || endOfStream) {
            int status = encoder.dequeueOutputBuffer(info, ENCODER_DRAIN_TIMEOUT_US);
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || emptyPolls++ > 50) {
                    break;
                }
                continue;
            }
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || status < 0) {
                continue;
            }
            ByteBuffer outputBuffer = encoder.getOutputBuffer(status);
            if (outputBuffer != null && info.size > 0) {
                byte[] payload = new byte[info.size];
                outputBuffer.position(info.offset);
                outputBuffer.limit(info.offset + info.size);
                outputBuffer.get(payload);
                sink.writePacket(info.presentationTimeUs, info.flags, payload);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            encoder.releaseOutputBuffer(status, false);
            if (eos) {
                break;
            }
        }
    }

    @Override
    public void close() {
        try {
            if (!stopped) {
                encoder.stop();
            }
        } catch (Exception ignored) {
            // Encoder may already be stopped after EOS or setup failure.
        } finally {
            stopped = true;
            try {
                inputSurface.release();
            } catch (Exception ignored) {
                // Surface release is best effort during runtime shutdown.
            }
            encoder.release();
        }
    }

    private static void configureEncoder(
            MediaCodec encoder,
            Size size,
            int bitrateBps,
            int frameRateHz) {
        MediaFormat format = MediaFormat.createVideoFormat(
                MIME_H264,
                size.getWidth(),
                size.getHeight());
        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(1, bitrateBps));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, frameRateHz));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        try {
            format.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
        } catch (Exception ignored) {
            // Some platform encoders reject optional tuning keys; the base H.264 surface profile is enough.
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }
}
