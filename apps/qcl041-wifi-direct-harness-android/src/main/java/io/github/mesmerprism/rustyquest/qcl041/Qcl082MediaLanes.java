package io.github.mesmerprism.rustyquest.qcl041;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Qcl082MediaLanes {
    private Qcl082MediaLanes() {
    }

    static Qcl082RelayLane[] relayLanes(
            Qcl041ProbeConfig config,
            Qcl041LifecycleArtifact artifact) {
        String laneSpec = config.qcl082RelayLanes == null ? "" : config.qcl082RelayLanes.trim();
        if (laneSpec.isEmpty()) {
            return new Qcl082RelayLane[] {
                    new Qcl082RelayLane(
                            "default",
                            config.qcl082RelaySourceHost,
                            config.qcl082RelaySourcePort,
                            config.qcl082RelayReceiverHost,
                            config.qcl082RelayReceiverPort)
            };
        }

        String[] specs = laneSpec.split(";");
        List<Qcl082RelayLane> lanes = new ArrayList<>();
        for (int index = 0; index < specs.length; index++) {
            Qcl082RelayLane lane = parseRelayLane(specs[index], index, artifact);
            if (lane != null) {
                lanes.add(lane);
            }
        }
        if (lanes.isEmpty()) {
            artifact.diagnostic("qcl082_relay", "lane_parse_error", laneSpec);
            return new Qcl082RelayLane[] {
                    new Qcl082RelayLane(
                            "default",
                            config.qcl082RelaySourceHost,
                            config.qcl082RelaySourcePort,
                            config.qcl082RelayReceiverHost,
                            config.qcl082RelayReceiverPort)
            };
        }
        return lanes.toArray(new Qcl082RelayLane[lanes.size()]);
    }

    static Qcl082ReceiveProxyLane[] receiveProxyLanes(
            Qcl041ProbeConfig config,
            Qcl041LifecycleArtifact artifact) {
        String laneSpec = config.qcl082ReceiveProxyLanes == null
                ? ""
                : config.qcl082ReceiveProxyLanes.trim();
        if (laneSpec.isEmpty()) {
            return new Qcl082ReceiveProxyLane[] {
                    new Qcl082ReceiveProxyLane(
                            "default",
                            config.qcl082ReceiveProxyListenPort,
                            config.qcl082ReceiveProxyTargetHost,
                            config.qcl082ReceiveProxyTargetPort)
            };
        }

        String[] specs = laneSpec.split(";");
        List<Qcl082ReceiveProxyLane> lanes = new ArrayList<>();
        for (int index = 0; index < specs.length; index++) {
            Qcl082ReceiveProxyLane lane = parseReceiveProxyLane(specs[index], index, artifact);
            if (lane != null) {
                lanes.add(lane);
            }
        }
        if (lanes.isEmpty()) {
            artifact.diagnostic("qcl082_receive_proxy", "lane_parse_error", laneSpec);
            return new Qcl082ReceiveProxyLane[] {
                    new Qcl082ReceiveProxyLane(
                            "default",
                            config.qcl082ReceiveProxyListenPort,
                            config.qcl082ReceiveProxyTargetHost,
                            config.qcl082ReceiveProxyTargetPort)
            };
        }
        return lanes.toArray(new Qcl082ReceiveProxyLane[lanes.size()]);
    }

    static String relaySection(Qcl082RelayLane lane, boolean multiLane) {
        return multiLane ? "qcl082_relay_" + lane.label : "qcl082_relay";
    }

    static String receiveProxySection(Qcl082ReceiveProxyLane lane, boolean multiLane) {
        return multiLane ? "qcl082_receive_proxy_" + lane.label : "qcl082_receive_proxy";
    }

    static String relayLaneSummary(Qcl082RelayLane[] lanes) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lanes.length; index++) {
            Qcl082RelayLane lane = lanes[index];
            if (index > 0) {
                builder.append(';');
            }
            builder.append(lane.label)
                    .append('=')
                    .append(lane.sourceHost)
                    .append(':')
                    .append(lane.sourcePort)
                    .append("->")
                    .append(lane.receiverHost)
                    .append(':')
                    .append(lane.receiverPort);
        }
        return builder.toString();
    }

    static String receiveProxyLaneSummary(Qcl082ReceiveProxyLane[] lanes) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < lanes.length; index++) {
            Qcl082ReceiveProxyLane lane = lanes[index];
            if (index > 0) {
                builder.append(';');
            }
            builder.append(lane.label)
                    .append('=')
                    .append(lane.listenPort)
                    .append("->")
                    .append(lane.targetHost)
                    .append(':')
                    .append(lane.targetPort);
        }
        return builder.toString();
    }

    private static Qcl082RelayLane parseRelayLane(
            String spec,
            int index,
            Qcl041LifecycleArtifact artifact) {
        String trimmed = spec == null ? "" : spec.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.contains(",") ? trimmed.split(",") : trimmed.split(":");
        if (parts.length != 5) {
            artifact.diagnostic("qcl082_relay", "lane_parse_error_" + index, trimmed);
            return null;
        }
        int sourcePort = parsePort(parts[2], -1);
        int receiverPort = parsePort(parts[4], -1);
        if (sourcePort <= 0 || receiverPort <= 0) {
            artifact.diagnostic("qcl082_relay", "lane_port_parse_error_" + index, trimmed);
            return null;
        }
        return new Qcl082RelayLane(
                sanitizeLaneLabel(parts[0], index),
                parts[1].trim(),
                sourcePort,
                parts[3].trim(),
                receiverPort);
    }

    private static Qcl082ReceiveProxyLane parseReceiveProxyLane(
            String spec,
            int index,
            Qcl041LifecycleArtifact artifact) {
        String trimmed = spec == null ? "" : spec.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.contains(",") ? trimmed.split(",") : trimmed.split(":");
        if (parts.length != 4) {
            artifact.diagnostic("qcl082_receive_proxy", "lane_parse_error_" + index, trimmed);
            return null;
        }
        int listenPort = parsePort(parts[1], -1);
        int targetPort = parsePort(parts[3], -1);
        if (listenPort <= 0 || targetPort <= 0) {
            artifact.diagnostic("qcl082_receive_proxy", "lane_port_parse_error_" + index, trimmed);
            return null;
        }
        return new Qcl082ReceiveProxyLane(
                sanitizeLaneLabel(parts[0], index),
                listenPort,
                parts[2].trim(),
                targetPort);
    }

    private static int parsePort(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String sanitizeLaneLabel(String value, int index) {
        String source = value == null ? "" : value.trim().toLowerCase(Locale.US);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_') {
                builder.append(ch);
            } else if (ch == '-') {
                builder.append('_');
            }
        }
        if (builder.length() == 0) {
            builder.append("lane").append(index);
        }
        return builder.toString();
    }
}

final class Qcl082RelayLane {
    final String label;
    final String sourceHost;
    final int sourcePort;
    final String receiverHost;
    final int receiverPort;

    Qcl082RelayLane(
            String label,
            String sourceHost,
            int sourcePort,
            String receiverHost,
            int receiverPort) {
        this.label = label;
        this.sourceHost = sourceHost;
        this.sourcePort = sourcePort;
        this.receiverHost = receiverHost;
        this.receiverPort = receiverPort;
    }
}

final class Qcl082ReceiveProxyLane {
    final String label;
    final int listenPort;
    final String targetHost;
    final int targetPort;

    Qcl082ReceiveProxyLane(
            String label,
            int listenPort,
            String targetHost,
            int targetPort) {
        this.label = label;
        this.listenPort = listenPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }
}
