package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.MediaPlayer;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Android-only implementation for the build-verified packaged inventory. Asset paths and hashes
 * arrive exclusively through {@link ConditionAudioContract.Provider}; this class has no study
 * labels, source filenames, or audio content knowledge.
 */
final class ConditionAudioAndroidMediaBackendFactory
        implements ConditionAudioRuntime.StartupPreloader {
    private final Object lock = new Object();
    private final AssetManager assets;
    private final Map<String, PreparedTrack> prepared = new HashMap<String, PreparedTrack>();
    private ConditionAudioRuntime.StartupPreloader.Listener preloadListener;
    private boolean closed;
    private boolean replacementDisabled;
    @Override public void disableReplacement() {
        synchronized (lock) { replacementDisabled = true; }
    }
    private final java.util.Set<MediaPlayer> preparing = new java.util.HashSet<MediaPlayer>();

    @Override public void closePreloads() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            preloadListener = null;
            for (MediaPlayer player : preparing) player.release();
            preparing.clear();
            for (PreparedTrack track : prepared.values()) track.player.release();
            prepared.clear();
        }
    }

    ConditionAudioAndroidMediaBackendFactory(AssetManager assets) {
        if (assets == null) throw new IllegalArgumentException("packaged asset manager is required");
        this.assets = assets;
    }

    @Override public void preload(
        ConditionAudioContract.Provider[] providers,
        ConditionAudioRuntime.StartupPreloader.Listener listener
    ) {
        if (providers == null || providers.length == 0 || listener == null) {
            throw new IllegalArgumentException("packaged condition tracks are required");
        }
        synchronized (lock) {
            preloadListener = listener;
        }
        for (ConditionAudioContract.Provider provider : providers) {
            if (provider == null || !provider.valid()) continue;
            reportPending(provider);
            prepareTrack(provider);
        }
    }

    @Override public ConditionAudioRuntime.MediaBackend create(
        ConditionAudioContract.Provider provider,
        ConditionAudioRuntime.MediaBackend.Listener listener
    ) {
        if (provider == null || listener == null) {
            throw new IllegalArgumentException("prepared track and listener are required");
        }
        final PreparedTrack track;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("audio-owner-closed");
            track = prepared.remove(provider.conditionId);
        }
        if (track == null || track.player == null) {
            throw new IllegalStateException("audio-track-not-ready");
        }
        return new SessionBackend(this, provider, track.player, listener);
    }

    private void prepareTrack(final ConditionAudioContract.Provider provider) {
        try {
            verifyPackagedAsset(provider);
            final MediaPlayer player = new MediaPlayer();
            synchronized (lock) {
                if (closed) { player.release(); return; }
                preparing.add(player);
            }
            AssetFileDescriptor descriptor = assets.openFd(provider.logicalDestination);
            try {
                player.setDataSource(
                    descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength()
                );
            } finally {
                descriptor.close();
            }
            player.setLooping(false);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer readyPlayer) {
                    synchronized (lock) {
                        if (closed || !preparing.remove(readyPlayer)) return;
                        prepared.put(provider.conditionId, new PreparedTrack(provider, readyPlayer));
                    }
                    reportReady(provider);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer failedPlayer, int what, int extra) {
                    synchronized (lock) {
                        if (!preparing.remove(failedPlayer)) return true;
                        failedPlayer.release();
                    }
                    reportFailed(provider, "track-preload-failed");
                    return true;
                }
            });
            player.prepareAsync();
        } catch (Exception error) {
            reportFailed(provider, "track-preload-failed");
        }
    }

    private void replaceAfterTerminal(ConditionAudioContract.Provider provider) {
        synchronized (lock) { if (closed || replacementDisabled) return; }
        reportPending(provider);
        prepareTrack(provider);
    }

    private void verifyPackagedAsset(ConditionAudioContract.Provider provider) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0L;
        InputStream stream = assets.open(provider.logicalDestination, AssetManager.ACCESS_STREAMING);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                bytes += read;
            }
        } finally {
            stream.close();
        }
        if (bytes != provider.sourceBytes || !provider.sourceSha256.equals(hex(digest.digest()))) {
            throw new SecurityException("packaged-audio-hash-mismatch");
        }
    }

    private void reportReady(ConditionAudioContract.Provider provider) {
        ConditionAudioRuntime.StartupPreloader.Listener listener;
        synchronized (lock) { listener = preloadListener; }
        if (listener != null) listener.onTrackReady(provider);
    }

    private void reportPending(ConditionAudioContract.Provider provider) {
        ConditionAudioRuntime.StartupPreloader.Listener listener;
        synchronized (lock) { listener = preloadListener; }
        if (listener != null) listener.onTrackPending(provider);
    }

    private void reportFailed(ConditionAudioContract.Provider provider, String reason) {
        ConditionAudioRuntime.StartupPreloader.Listener listener;
        synchronized (lock) { listener = preloadListener; }
        if (listener != null) listener.onTrackFailed(provider, reason);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static final class PreparedTrack {
        final ConditionAudioContract.Provider provider;
        final MediaPlayer player;

        PreparedTrack(ConditionAudioContract.Provider provider, MediaPlayer player) {
            this.provider = provider;
            this.player = player;
        }
    }

    private static final class SessionBackend implements ConditionAudioRuntime.MediaBackend {
        private final ConditionAudioAndroidMediaBackendFactory factory;
        private final ConditionAudioContract.Provider provider;
        private final MediaPlayer player;
        private final Listener listener;
        private boolean released;

        SessionBackend(
            ConditionAudioAndroidMediaBackendFactory factory,
            ConditionAudioContract.Provider provider,
            MediaPlayer player,
            Listener listener
        ) {
            this.factory = factory;
            this.provider = provider;
            this.player = player;
            this.listener = listener;
        }

        @Override public void prepare(boolean looping) {
            if (looping) throw new IllegalArgumentException("condition audio must not loop");
            player.setLooping(false);
            listener.onPrepared();
        }

        @Override public void start() {
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer completedPlayer) {
                    listener.onNaturalEnd(Math.max(0L, completedPlayer.getCurrentPosition()));
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer failedPlayer, int what, int extra) {
                    listener.onError("media-player-error");
                    return true;
                }
            });
            player.start();
            listener.onActualStart(Math.max(0L, player.getCurrentPosition()));
        }

        @Override public void stop() { player.stop(); }

        @Override public void release() {
            if (released) return;
            released = true;
            player.release();
            factory.replaceAfterTerminal(provider);
        }
    }
}
