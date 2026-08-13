package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import java.util.Locale;

/** Process-local, secret-free, low-rate status for the active peer-stereo source. */
final class SpatialPeerStereoStatus {
    private static final Object LOCK = new Object();
    private static Snapshot snapshot = Snapshot.idle();

    private SpatialPeerStereoStatus() {}

    static void starting(String routeKind, String sessionId, boolean authProvided) {
        synchronized (LOCK) {
            snapshot = new Snapshot(
                "starting",
                SpatialPeerStereoTransport.normalizeRouteKind(routeKind),
                present(sessionId),
                authProvided,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                false,
                false
            );
        }
    }

    static void connected() {
        synchronized (LOCK) {
            snapshot = snapshot.withState("connected");
        }
    }

    static void packet(long payloadBytes, long pairId, long leftTimestampNs, long rightTimestampNs,
                       long pairDeltaNs, boolean codecConfig) {
        synchronized (LOCK) {
            snapshot = snapshot.withPacket(
                payloadBytes,
                pairId,
                leftTimestampNs,
                rightTimestampNs,
                pairDeltaNs,
                codecConfig
            );
        }
    }

    static void rendered(long renderedFrames) {
        synchronized (LOCK) {
            snapshot = snapshot.withRendered(renderedFrames);
        }
    }

    static void stopped(boolean failed) {
        synchronized (LOCK) {
            snapshot = snapshot.withState(failed ? "failed" : "stopped");
        }
    }

    static Snapshot snapshot() {
        synchronized (LOCK) {
            return snapshot;
        }
    }

    static String snapshotSummary() {
        return snapshot().summary();
    }

    private static boolean present(String value) {
        return value != null && !value.trim().isEmpty();
    }

    static final class Snapshot {
        final String state;
        final String routeKind;
        final boolean acceptedSession;
        final boolean authenticationProvided;
        final long packets;
        final long bytes;
        final long renderedFrames;
        final long lastPairId;
        final long lastLeftSensorTimestampNs;
        final long lastRightSensorTimestampNs;
        final long lastPairDeltaNs;
        final boolean timestampsAdvancing;
        final boolean pairSequenceAdvancing;
        final boolean decoderOutputObserved;

        Snapshot(
            String state,
            String routeKind,
            boolean acceptedSession,
            boolean authenticationProvided,
            long packets,
            long bytes,
            long renderedFrames,
            long lastPairId,
            long lastLeftSensorTimestampNs,
            long lastRightSensorTimestampNs,
            long lastPairDeltaNs,
            boolean timestampsAdvancing,
            boolean pairSequenceAdvancing,
            boolean decoderOutputObserved
        ) {
            this.state = state;
            this.routeKind = routeKind;
            this.acceptedSession = acceptedSession;
            this.authenticationProvided = authenticationProvided;
            this.packets = packets;
            this.bytes = bytes;
            this.renderedFrames = renderedFrames;
            this.lastPairId = lastPairId;
            this.lastLeftSensorTimestampNs = lastLeftSensorTimestampNs;
            this.lastRightSensorTimestampNs = lastRightSensorTimestampNs;
            this.lastPairDeltaNs = lastPairDeltaNs;
            this.timestampsAdvancing = timestampsAdvancing;
            this.pairSequenceAdvancing = pairSequenceAdvancing;
            this.decoderOutputObserved = decoderOutputObserved;
        }

        static Snapshot idle() {
            return new Snapshot(
                "idle", SpatialPeerStereoTransport.DIRECT_TCP_CONNECT, false, false,
                0, 0, 0, 0, 0, 0, 0, false, false, false
            );
        }

        Snapshot withState(String nextState) {
            return new Snapshot(
                nextState, routeKind, acceptedSession, authenticationProvided, packets, bytes,
                renderedFrames, lastPairId, lastLeftSensorTimestampNs, lastRightSensorTimestampNs,
                lastPairDeltaNs, timestampsAdvancing, pairSequenceAdvancing, decoderOutputObserved
            );
        }

        Snapshot withPacket(long payloadBytes, long pairId, long leftTimestampNs,
                            long rightTimestampNs, long pairDeltaNs, boolean codecConfig) {
            boolean realPair = !codecConfig && pairId > 0;
            return new Snapshot(
                "streaming",
                routeKind,
                acceptedSession,
                authenticationProvided,
                packets + 1,
                bytes + Math.max(0, payloadBytes),
                renderedFrames,
                realPair ? pairId : lastPairId,
                realPair ? leftTimestampNs : lastLeftSensorTimestampNs,
                realPair ? rightTimestampNs : lastRightSensorTimestampNs,
                realPair ? pairDeltaNs : lastPairDeltaNs,
                timestampsAdvancing
                    || (realPair
                        && lastLeftSensorTimestampNs > 0
                        && lastRightSensorTimestampNs > 0
                        && leftTimestampNs > lastLeftSensorTimestampNs
                        && rightTimestampNs > lastRightSensorTimestampNs),
                pairSequenceAdvancing || (realPair && lastPairId > 0 && pairId > lastPairId),
                decoderOutputObserved
            );
        }

        Snapshot withRendered(long count) {
            return new Snapshot(
                "streaming", routeKind, acceptedSession, authenticationProvided, packets, bytes,
                Math.max(renderedFrames, count), lastPairId, lastLeftSensorTimestampNs,
                lastRightSensorTimestampNs, lastPairDeltaNs, timestampsAdvancing,
                pairSequenceAdvancing, count > 0 || decoderOutputObserved
            );
        }

        String summary() {
            return String.format(
                Locale.US,
                "Peer stereo: %s · %s · packets %d · frames %d · pair %d · skew %.3f ms",
                state,
                routeKind,
                packets,
                renderedFrames,
                lastPairId,
                lastPairDeltaNs / 1_000_000.0
            );
        }

        String marker() {
            return String.format(
                Locale.US,
                "peerState=%s peerRouteKind=%s peerSessionAccepted=%s "
                    + "peerAuthenticationProvided=%s packets=%d bytes=%d renderedFrames=%d "
                    + "lastPairId=%d leftSensorTimestampNs=%d rightSensorTimestampNs=%d "
                    + "pairDeltaNs=%d timestampsAdvancing=%s pairSequenceAdvancing=%s "
                    + "decoderOutputObserved=%s peerEndpointRedacted=true peerSecretSerialized=false",
                state,
                routeKind,
                acceptedSession,
                authenticationProvided,
                packets,
                bytes,
                renderedFrames,
                lastPairId,
                lastLeftSensorTimestampNs,
                lastRightSensorTimestampNs,
                lastPairDeltaNs,
                timestampsAdvancing,
                pairSequenceAdvancing,
                decoderOutputObserved
            );
        }
    }
}
