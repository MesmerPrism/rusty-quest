package io.github.mesmerprism.rustyquest.qcl041;

import android.os.SystemClock;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class Qcl082ControlTcpMediaCarrier {
    static final String TRANSPORT_PROTOCOL = "control-tcp";

    private static final String CARRIER_REQUEST = "QCL082_CONTROL_TCP_MEDIA_CARRIER";
    private static final String CARRIER_ACK = "QCL082_CONTROL_TCP_MEDIA_CARRIER_ACK";
    private static final String CARRIER_READY = "QCL082_CONTROL_TCP_MEDIA_CARRIER_READY";
    private static final int FRAME_MAGIC = 0x51435442; // QCTB
    private static final int FRAME_VERSION = 1;
    private static final int FRAME_TYPE_DATA = 1;
    private static final int FRAME_TYPE_END = 2;
    private static final int FRAME_MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final long PROGRESS_PUBLISH_INTERVAL_MS = 500L;

    private final Qcl041LifecycleArtifact artifact;
    private final Qcl041ProbeConfig config;
    private final Socket socket;
    private final String role;
    private final Object outputLock = new Object();
    private final AtomicBoolean outboundDone = new AtomicBoolean(false);
    private final AtomicInteger activeSendThreads = new AtomicInteger(0);

    Qcl082ControlTcpMediaCarrier(
            Qcl041LifecycleArtifact artifact,
            Qcl041ProbeConfig config,
            Socket socket,
            String role) {
        this.artifact = artifact;
        this.config = config;
        this.socket = socket;
        this.role = role;
    }

    void run(Qcl082RelayLane[] relayLanes, Qcl082ReceiveProxyLane[] receiveProxyLanes) {
        long started = SystemClock.elapsedRealtime();
        long deadline = started + carrierHoldMs();
        artifact.diagnostic("control_tcp", "media_carrier_enabled", true);
        artifact.diagnostic("control_tcp", "media_carrier_protocol", TRANSPORT_PROTOCOL);
        artifact.diagnostic("control_tcp", "media_carrier_role", role);
        artifact.diagnostic("control_tcp", "media_carrier_relay_lane_count", relayLanes == null ? 0 : relayLanes.length);
        artifact.diagnostic(
                "control_tcp",
                "media_carrier_receive_proxy_lane_count",
                receiveProxyLanes == null ? 0 : receiveProxyLanes.length);
        artifact.diagnostic("control_tcp", "media_carrier_hold_ms", carrierHoldMs());
        artifact.writeQuietly();
        if (socket == null) {
            artifact.diagnostic("control_tcp", "media_carrier_completed", false);
            artifact.diagnostic("control_tcp", "media_carrier_error", "control socket missing");
            artifact.writeQuietly();
            return;
        }
        Qcl082ReceiveProxyLane[] receiveLanes = receiveProxyLanes == null
                ? new Qcl082ReceiveProxyLane[0]
                : receiveProxyLanes;
        Qcl082RelayLane[] sendLanes = relayLanes == null
                ? new Qcl082RelayLane[0]
                : relayLanes;
        Map<String, Qcl082ReceiveProxyLane> receiveByLabel = receiveLaneMap(receiveLanes);
        Map<String, ReceiveState> receiveStates = new HashMap<>();
        Thread[] sendThreads = new Thread[0];
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(1000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            if ("client".equals(role)) {
                startClientHandshake(input, output, sendLanes.length, receiveLanes.length);
            } else {
                startGroupOwnerHandshake(input, output, sendLanes.length, receiveLanes.length);
            }
            sendThreads = startSendThreads(sendLanes, output, deadline, started);
            receiveFrames(input, receiveByLabel, receiveStates, receiveLanes.length, deadline, started);
            joinSendThreads(sendThreads);
            recordMissingReceiveLanes(receiveLanes, receiveStates, started);
            artifact.diagnostic("control_tcp", "media_carrier_completed", true);
        } catch (Exception ex) {
            artifact.diagnostic(
                    "control_tcp",
                    "media_carrier_error",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            artifact.diagnostic("control_tcp", "media_carrier_completed", false);
        } finally {
            outboundDone.set(true);
            for (Thread thread : sendThreads) {
                if (thread != null && thread.isAlive()) {
                    thread.interrupt();
                }
            }
            artifact.diagnostic("control_tcp", "media_carrier_elapsed_ms", SystemClock.elapsedRealtime() - started);
            artifact.writeQuietly();
            closeReceiveStates(receiveStates);
        }
    }

    private void startClientHandshake(
            DataInputStream input,
            DataOutputStream output,
            int relayLaneCount,
            int receiveLaneCount) throws IOException {
        String request = CARRIER_REQUEST
                + ";run_id=" + config.runId
                + ";epoch=" + config.qcl082TopologyEpoch
                + ";relay_lanes=" + relayLaneCount
                + ";receive_proxy_lanes=" + receiveLaneCount;
        writeControlLine(output, request);
        artifact.diagnostic("control_tcp", "media_carrier_request", request);
        String response = readControlLine(input);
        artifact.diagnostic("control_tcp", "media_carrier_reply", response == null ? "" : response);
        if (response == null || !response.startsWith(CARRIER_ACK)) {
            throw new IOException("control TCP media carrier ACK not received");
        }
        String ready = CARRIER_READY + ";run_id=" + config.runId + ";epoch=" + config.qcl082TopologyEpoch;
        writeControlLine(output, ready);
        artifact.diagnostic("control_tcp", "media_carrier_ready_sent", true);
    }

    private void startGroupOwnerHandshake(
            DataInputStream input,
            DataOutputStream output,
            int relayLaneCount,
            int receiveLaneCount) throws IOException {
        String request = readControlLine(input);
        artifact.diagnostic("control_tcp", "media_carrier_request", request == null ? "" : request);
        if (request == null || !request.startsWith(CARRIER_REQUEST)) {
            throw new IOException("control TCP media carrier request not received");
        }
        String response = CARRIER_ACK
                + ";run_id=" + config.runId
                + ";epoch=" + config.qcl082TopologyEpoch
                + ";relay_lanes=" + relayLaneCount
                + ";receive_proxy_lanes=" + receiveLaneCount;
        writeControlLine(output, response);
        artifact.diagnostic("control_tcp", "media_carrier_reply", response);
        String ready = readControlLine(input);
        artifact.diagnostic("control_tcp", "media_carrier_ready", ready == null ? "" : ready);
        if (ready == null || !ready.startsWith(CARRIER_READY)) {
            throw new IOException("control TCP media carrier READY not received");
        }
    }

    private String readControlLine(DataInputStream input) throws IOException {
        byte[] buffer = new byte[1024];
        int length = 0;
        while (true) {
            int value = input.read();
            if (value < 0) {
                return length == 0 ? null : new String(buffer, 0, length, StandardCharsets.UTF_8);
            }
            if (value == '\n') {
                return new String(buffer, 0, length, StandardCharsets.UTF_8);
            }
            if (value == '\r') {
                continue;
            }
            if (length >= buffer.length) {
                throw new IOException("control TCP media carrier line too long");
            }
            buffer[length++] = (byte) value;
        }
    }

    private void writeControlLine(DataOutputStream output, String line) throws IOException {
        output.write(line.getBytes(StandardCharsets.UTF_8));
        output.writeByte('\n');
        output.flush();
    }

    private Thread[] startSendThreads(
            Qcl082RelayLane[] lanes,
            final DataOutputStream output,
            final long deadline,
            final long started) {
        Thread[] threads = new Thread[lanes.length];
        activeSendThreads.set(lanes.length);
        for (int index = 0; index < lanes.length; index++) {
            final Qcl082RelayLane lane = lanes[index];
            final boolean multiLane = lanes.length > 1;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    sendRelayLane(lane, multiLane, output, deadline, started);
                }
            }, "qcl082-control-tcp-media-carrier-send-" + lane.label);
            thread.start();
            threads[index] = thread;
        }
        if (lanes.length == 0) {
            outboundDone.set(true);
        }
        return threads;
    }

    private void sendRelayLane(
            Qcl082RelayLane lane,
            boolean multiLane,
            DataOutputStream output,
            long deadline,
            long started) {
        String section = Qcl082MediaLanes.relaySection(lane, multiLane);
        Qcl082CopyProgress progress = new Qcl082CopyProgress();
        long total = 0L;
        Socket source = null;
        int frames = 0;
        long lastProgressPublishMs = 0L;
        String error = null;
        try {
            artifact.diagnostic(section, "transport_protocol", TRANSPORT_PROTOCOL);
            artifact.diagnostic(section, "control_tcp_carrier_source_host", lane.sourceHost);
            artifact.diagnostic(section, "control_tcp_carrier_source_port", lane.sourcePort);
            artifact.diagnostic(section, "status", "control-tcp-source-connecting");
            artifact.writeQuietly();
            source = connectSocketWithRetry(
                    lane.sourceHost,
                    lane.sourcePort,
                    deadline,
                    section,
                    "control_tcp_carrier_source");
            source.setTcpNoDelay(true);
            source.setSoTimeout(1000);
            InputStream input = source.getInputStream();
            byte[] buffer = new byte[FRAME_MAX_PAYLOAD_BYTES];
            int maxBytes = Math.max(1, config.qcl082RelayMaxBytes);
            artifact.diagnostic(section, "status", "control-tcp-streaming");
            artifact.writeQuietly();
            while (!Thread.currentThread().isInterrupted()
                    && SystemClock.elapsedRealtime() < deadline
                    && total < maxBytes) {
                int readLimit = (int) Math.min((long) buffer.length, maxBytes - total);
                int read;
                try {
                    read = input.read(buffer, 0, readLimit);
                } catch (SocketTimeoutException timeout) {
                    progress.timeoutPolls++;
                    progress.updateIdle(started);
                    continue;
                }
                if (read < 0) {
                    progress.completedReason = "source_eof";
                    break;
                }
                if (read == 0) {
                    continue;
                }
                progress.noteRead(read, started);
                writeFrame(output, FRAME_TYPE_DATA, lane.label, buffer, read);
                total += read;
                frames++;
                progress.noteWrite(total, read, started);
                lastProgressPublishMs = publishRelayProgressIfDue(
                        section,
                        lane,
                        multiLane,
                        progress,
                        started,
                        "pass",
                        frames,
                        lastProgressPublishMs);
            }
            if ("running".equals(progress.completedReason)) {
                progress.completedReason = total >= maxBytes ? "max_bytes" : "deadline_elapsed";
            }
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            if (total > 0L && "running".equals(progress.completedReason)) {
                progress.completedReason = "control_tcp_write_error_after_bytes";
            } else if ("running".equals(progress.completedReason)) {
                progress.completedReason = "control_tcp_write_error";
            }
        } finally {
            try {
                writeFrame(output, FRAME_TYPE_END, lane.label, new byte[0], 0);
            } catch (Exception ex) {
                if (error == null) {
                    error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                }
            }
            publishRelayProgress(
                    section,
                    lane,
                    multiLane,
                    progress,
                    started,
                    total > 0L ? "pass" : "blocked",
                    frames,
                    error);
            closeQuietly(source);
            if (activeSendThreads.decrementAndGet() <= 0) {
                outboundDone.set(true);
            }
        }
    }

    private long publishRelayProgressIfDue(
            String section,
            Qcl082RelayLane lane,
            boolean multiLane,
            Qcl082CopyProgress progress,
            long started,
            String status,
            int frames,
            long lastPublishMs) {
        long now = SystemClock.elapsedRealtime();
        if (lastPublishMs > 0L && now - lastPublishMs < PROGRESS_PUBLISH_INTERVAL_MS) {
            return lastPublishMs;
        }
        publishRelayProgress(section, lane, multiLane, progress, started, status, frames, null);
        return now;
    }

    private void publishRelayProgress(
            String section,
            Qcl082RelayLane lane,
            boolean multiLane,
            Qcl082CopyProgress progress,
            long started,
            String status,
            int frames,
            String error) {
        progress.updateIdle(started);
        artifact.diagnostic(section, "status", status);
        artifact.diagnostic(section, "bytes_copied", progress.totalBytes);
        artifact.diagnostic(section, "frames_sent", frames);
        artifact.diagnostic(section, "elapsed_ms", SystemClock.elapsedRealtime() - started);
        artifact.diagnostic(section, "copy_completed_reason", progress.completedReason);
        artifact.diagnostic(section, "copy_current_elapsed_ms", progress.currentElapsedMs);
        artifact.diagnostic(section, "copy_first_byte_elapsed_ms", progress.firstByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic(section, "copy_last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic(section, "copy_read_operations", progress.readOperations);
        artifact.diagnostic(section, "copy_write_operations", progress.writeOperations);
        artifact.diagnostic(section, "copy_bytes_read_from_source", progress.bytesReadFromSource);
        artifact.diagnostic(section, "copy_bytes_written_to_receiver", progress.bytesWrittenToReceiver);
        artifact.diagnostic(section, "copy_last_read_size", progress.lastReadSize);
        artifact.diagnostic(section, "copy_last_write_size", progress.lastWriteSize);
        artifact.diagnostic(section, "copy_last_read_elapsed_ms", progress.lastReadElapsedMs);
        artifact.diagnostic(section, "copy_last_write_elapsed_ms", progress.lastWriteElapsedMs);
        artifact.diagnostic(section, "copy_last_read_age_ms", progress.lastReadAgeMs);
        artifact.diagnostic(section, "copy_last_write_age_ms", progress.lastWriteAgeMs);
        artifact.diagnostic(section, "copy_timeout_polls", progress.timeoutPolls);
        artifact.diagnostic(section, "progress_publish_interval_ms", PROGRESS_PUBLISH_INTERVAL_MS);
        if (error != null) {
            artifact.diagnostic(section, "error", error);
        }
        artifact.diagnostic("qcl082_relay", lane.label + "_status", status);
        artifact.diagnostic("qcl082_relay", lane.label + "_bytes_copied", progress.totalBytes);
        artifact.diagnostic("qcl082_relay", lane.label + "_last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_frames_sent", frames);
        artifact.diagnostic("qcl082_relay", lane.label + "_copy_current_elapsed_ms", progress.currentElapsedMs);
        artifact.diagnostic("qcl082_relay", lane.label + "_copy_completed_reason", progress.completedReason);
        if (!multiLane || "left".equals(lane.label)) {
            artifact.diagnostic("qcl082_relay", "status", status);
            artifact.diagnostic("qcl082_relay", "bytes_copied", progress.totalBytes);
            artifact.diagnostic("qcl082_relay", "last_byte_elapsed_ms", progress.lastByteElapsedMs);
            artifact.diagnostic("qcl082_relay", "last_byte_unix_ms", progress.lastByteUnixMs);
            artifact.diagnostic("qcl082_relay", "last_byte_age_ms", progress.lastByteAgeMs);
            artifact.diagnostic("qcl082_relay", "frames_sent", frames);
            artifact.diagnostic("qcl082_relay", "copy_current_elapsed_ms", progress.currentElapsedMs);
            artifact.diagnostic("qcl082_relay", "copy_completed_reason", progress.completedReason);
        }
        artifact.writeQuietly();
    }

    private void receiveFrames(
            DataInputStream input,
            Map<String, Qcl082ReceiveProxyLane> receiveByLabel,
            Map<String, ReceiveState> states,
            int expectedReceiveLaneCount,
            long deadline,
            long started) throws IOException {
        int endedReceiveLanes = 0;
        byte[] payload = new byte[FRAME_MAX_PAYLOAD_BYTES];
        while (SystemClock.elapsedRealtime() < deadline) {
            if (expectedReceiveLaneCount == 0 && outboundDone.get()) {
                return;
            }
            if (expectedReceiveLaneCount > 0 && endedReceiveLanes >= expectedReceiveLaneCount) {
                return;
            }
            try {
                int magic = input.readInt();
                if (magic != FRAME_MAGIC) {
                    throw new IOException("unexpected control TCP media carrier frame magic " + magic);
                }
                int version = input.readUnsignedByte();
                int type = input.readUnsignedByte();
                int labelLength = input.readUnsignedShort();
                int payloadLength = input.readInt();
                if (version != FRAME_VERSION) {
                    throw new IOException("unsupported control TCP media carrier frame version " + version);
                }
                if (labelLength <= 0 || labelLength > 128) {
                    throw new IOException("invalid control TCP media carrier lane label length " + labelLength);
                }
                if (payloadLength < 0 || payloadLength > FRAME_MAX_PAYLOAD_BYTES) {
                    throw new IOException("invalid control TCP media carrier payload length " + payloadLength);
                }
                byte[] labelBytes = new byte[labelLength];
                input.readFully(labelBytes);
                String label = new String(labelBytes, StandardCharsets.UTF_8);
                if (payloadLength > 0) {
                    input.readFully(payload, 0, payloadLength);
                }
                Qcl082ReceiveProxyLane lane = receiveByLabel.get(label);
                if (lane == null) {
                    artifact.diagnostic("control_tcp", "media_carrier_unmatched_lane", label);
                    continue;
                }
                ReceiveState state = receiveStateFor(states, lane, receiveByLabel.size(), deadline, started);
                if (type == FRAME_TYPE_DATA) {
                    writeReceivePayload(state, payload, payloadLength, started);
                } else if (type == FRAME_TYPE_END) {
                    state.progress.completedReason = state.progress.totalBytes > 0L
                            ? "control_tcp_peer_end"
                            : "control_tcp_peer_end_without_bytes";
                    recordReceiveState(state, started, null);
                    endedReceiveLanes++;
                } else {
                    throw new IOException("unsupported control TCP media carrier frame type " + type);
                }
            } catch (SocketTimeoutException timeout) {
                updateReceiveStates(states, started);
                if (outboundDone.get() && expectedReceiveLaneCount == 0) {
                    return;
                }
            }
        }
    }

    private ReceiveState receiveStateFor(
            Map<String, ReceiveState> states,
            Qcl082ReceiveProxyLane lane,
            int laneCount,
            long deadline,
            long started) throws IOException {
        ReceiveState current = states.get(lane.label);
        if (current != null) {
            return current;
        }
        String section = Qcl082MediaLanes.receiveProxySection(lane, laneCount > 1);
        Socket target = connectSocketWithRetry(
                lane.targetHost,
                lane.targetPort,
                deadline,
                section,
                "control_tcp_carrier_target");
        target.setTcpNoDelay(true);
        target.setSoTimeout(1000);
        ReceiveState created = new ReceiveState(lane, laneCount > 1, target, target.getOutputStream());
        created.progress.copySegments = 1L;
        artifact.diagnostic(section, "transport_protocol", TRANSPORT_PROTOCOL);
        artifact.diagnostic(section, "status", "control-tcp-target-connected");
        artifact.diagnostic(section, "target_host", lane.targetHost);
        artifact.diagnostic(section, "target_port", lane.targetPort);
        artifact.diagnostic(section, "copy_current_elapsed_ms", SystemClock.elapsedRealtime() - started);
        artifact.writeQuietly();
        states.put(lane.label, created);
        return created;
    }

    private void writeReceivePayload(
            ReceiveState state,
            byte[] payload,
            int payloadLength,
            long started) throws IOException {
        if (payloadLength <= 0) {
            return;
        }
        int maxBytes = Math.max(1, config.qcl082ReceiveProxyMaxBytes);
        long current = state.progress.totalBytes;
        if (current >= maxBytes) {
            state.progress.completedReason = "max_bytes";
            return;
        }
        int writeLength = (int) Math.min(payloadLength, maxBytes - current);
        state.progress.noteRead(writeLength, started);
        state.output.write(payload, 0, writeLength);
        state.output.flush();
        state.framesReceived++;
        state.progress.noteWrite(current + writeLength, writeLength, started);
        recordReceiveState(state, started, null);
    }

    private void recordMissingReceiveLanes(
            Qcl082ReceiveProxyLane[] receiveLanes,
            Map<String, ReceiveState> states,
            long started) {
        boolean multiLane = receiveLanes.length > 1;
        for (Qcl082ReceiveProxyLane lane : receiveLanes) {
            if (states.containsKey(lane.label)) {
                continue;
            }
            Qcl082CopyProgress progress = new Qcl082CopyProgress();
            progress.completedReason = "control_tcp_no_peer_frames";
            String section = Qcl082MediaLanes.receiveProxySection(lane, multiLane);
            artifact.diagnostic(section, "transport_protocol", TRANSPORT_PROTOCOL);
            artifact.diagnostic(section, "status", "blocked");
            artifact.diagnostic(section, "bytes_copied", 0L);
            artifact.diagnostic(section, "frames_received", 0);
            artifact.diagnostic(section, "elapsed_ms", SystemClock.elapsedRealtime() - started);
            artifact.diagnostic(section, "copy_completed_reason", progress.completedReason);
            artifact.diagnostic("qcl082_receive_proxy", lane.label + "_status", "blocked");
            artifact.diagnostic("qcl082_receive_proxy", lane.label + "_bytes_copied", 0L);
        }
        artifact.writeQuietly();
    }

    private void recordReceiveState(ReceiveState state, long started, String error) {
        Qcl082CopyProgress progress = state.progress;
        progress.updateIdle(started);
        String section = Qcl082MediaLanes.receiveProxySection(state.lane, state.multiLane);
        long total = progress.totalBytes;
        String status = total > 0L ? "pass" : "blocked";
        artifact.diagnostic(section, "status", status);
        artifact.diagnostic(section, "bytes_copied", total);
        artifact.diagnostic(section, "frames_received", state.framesReceived);
        artifact.diagnostic(section, "elapsed_ms", SystemClock.elapsedRealtime() - started);
        artifact.diagnostic(section, "copy_completed_reason", progress.completedReason);
        artifact.diagnostic(section, "copy_current_elapsed_ms", progress.currentElapsedMs);
        artifact.diagnostic(section, "copy_first_byte_elapsed_ms", progress.firstByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_elapsed_ms", progress.lastByteElapsedMs);
        artifact.diagnostic(section, "copy_last_byte_unix_ms", progress.lastByteUnixMs);
        artifact.diagnostic(section, "copy_last_byte_age_ms", progress.lastByteAgeMs);
        artifact.diagnostic(section, "copy_read_operations", progress.readOperations);
        artifact.diagnostic(section, "copy_write_operations", progress.writeOperations);
        artifact.diagnostic(section, "copy_bytes_read_from_peer", progress.bytesReadFromSource);
        artifact.diagnostic(section, "copy_bytes_written_to_target", progress.bytesWrittenToReceiver);
        if (error != null) {
            artifact.diagnostic(section, "error", error);
        }
        artifact.diagnostic("qcl082_receive_proxy", state.lane.label + "_status", status);
        artifact.diagnostic("qcl082_receive_proxy", state.lane.label + "_bytes_copied", total);
        artifact.diagnostic(
                "qcl082_receive_proxy",
                state.lane.label + "_copy_completed_reason",
                progress.completedReason);
        if (!state.multiLane || "left".equals(state.lane.label)) {
            artifact.diagnostic("qcl082_receive_proxy", "status", status);
            artifact.diagnostic("qcl082_receive_proxy", "bytes_copied", total);
            artifact.diagnostic("qcl082_receive_proxy", "last_byte_elapsed_ms", progress.lastByteElapsedMs);
            artifact.diagnostic("qcl082_receive_proxy", "last_byte_unix_ms", progress.lastByteUnixMs);
            artifact.diagnostic("qcl082_receive_proxy", "last_byte_age_ms", progress.lastByteAgeMs);
            artifact.diagnostic("qcl082_receive_proxy", "copy_current_elapsed_ms", progress.currentElapsedMs);
            artifact.diagnostic("qcl082_receive_proxy", "copy_completed_reason", progress.completedReason);
        }
        artifact.writeQuietly();
    }

    private void updateReceiveStates(Map<String, ReceiveState> states, long started) {
        for (ReceiveState state : states.values()) {
            state.progress.timeoutPolls++;
            state.progress.updateIdle(started);
        }
    }

    private void writeFrame(
            DataOutputStream output,
            int type,
            String label,
            byte[] payload,
            int payloadLength) throws IOException {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        if (labelBytes.length <= 0 || labelBytes.length > 128) {
            throw new IOException("invalid lane label length for control TCP media carrier: " + label);
        }
        if (payloadLength < 0 || payloadLength > FRAME_MAX_PAYLOAD_BYTES) {
            throw new IOException("invalid control TCP media carrier payload length " + payloadLength);
        }
        synchronized (outputLock) {
            output.writeInt(FRAME_MAGIC);
            output.writeByte(FRAME_VERSION);
            output.writeByte(type);
            output.writeShort(labelBytes.length);
            output.writeInt(payloadLength);
            output.write(labelBytes);
            if (payloadLength > 0) {
                output.write(payload, 0, payloadLength);
            }
            output.flush();
        }
    }

    private Socket connectSocketWithRetry(
            String host,
            int port,
            long deadline,
            String section,
            String prefix) throws IOException {
        IOException last = null;
        int attempts = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts++;
            Socket candidate = new Socket();
            try {
                candidate.connect(new InetSocketAddress(host, port), 1000);
                artifact.diagnostic(section, prefix + "_connect_attempts", attempts);
                artifact.diagnostic(section, prefix + "_connected", true);
                artifact.diagnostic(section, prefix + "_local_socket", String.valueOf(candidate.getLocalSocketAddress()));
                artifact.diagnostic(section, prefix + "_remote_socket", String.valueOf(candidate.getRemoteSocketAddress()));
                return candidate;
            } catch (IOException ex) {
                last = ex;
                closeQuietly(candidate);
                if (attempts == 1 || attempts % 4 == 0) {
                    artifact.diagnostic(section, prefix + "_connect_attempts", attempts);
                    artifact.diagnostic(section, prefix + "_last_connect_error", ex.getMessage());
                    artifact.writeQuietly();
                }
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while connecting " + host + ":" + port, interrupted);
                }
            }
        }
        artifact.diagnostic(section, prefix + "_connect_attempts", attempts);
        throw last == null ? new IOException("connect timed out for " + host + ":" + port) : last;
    }

    private Map<String, Qcl082ReceiveProxyLane> receiveLaneMap(Qcl082ReceiveProxyLane[] lanes) {
        Map<String, Qcl082ReceiveProxyLane> result = new HashMap<>();
        for (Qcl082ReceiveProxyLane lane : lanes) {
            result.put(lane.label, lane);
        }
        return result;
    }

    private long carrierHoldMs() {
        long relayMs = Math.max(5L, (long) config.qcl082RelayTimeoutSeconds) * 1000L;
        long receiveMs = Math.max(5L, (long) config.qcl082ReceiveProxyTimeoutSeconds) * 1000L;
        long configuredHoldMs = Math.max(0L, (long) config.holdAfterSocketMs);
        return Math.max(configuredHoldMs, Math.max(relayMs, receiveMs));
    }

    private void joinSendThreads(Thread[] threads) {
        for (Thread thread : threads) {
            if (thread == null) {
                continue;
            }
            try {
                thread.join(1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void closeReceiveStates(Map<String, ReceiveState> states) {
        for (ReceiveState state : states.values()) {
            closeQuietly(state.socket);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static final class ReceiveState {
        final Qcl082ReceiveProxyLane lane;
        final boolean multiLane;
        final Socket socket;
        final OutputStream output;
        final Qcl082CopyProgress progress = new Qcl082CopyProgress();
        int framesReceived = 0;

        ReceiveState(
                Qcl082ReceiveProxyLane lane,
                boolean multiLane,
                Socket socket,
                OutputStream output) {
            this.lane = lane;
            this.multiLane = multiLane;
            this.socket = socket;
            this.output = output;
        }
    }
}
