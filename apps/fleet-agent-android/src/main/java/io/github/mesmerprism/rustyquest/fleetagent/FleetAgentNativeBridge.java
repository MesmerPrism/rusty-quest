package io.github.mesmerprism.rustyquest.fleetagent;

final class FleetAgentNativeBridge {
    static {
        System.loadLibrary("rusty_quest_fleet_agent_android");
    }

    private FleetAgentNativeBridge() {
    }

    static String produce(
            String profileJson,
            String snapshotJson,
            byte[] privateSeed,
            long issuedAtMs) {
        return nativeProduce(profileJson, snapshotJson, privateSeed, issuedAtMs);
    }

    private static native String nativeProduce(
            String profileJson,
            String snapshotJson,
            byte[] privateSeed,
            long issuedAtMs);
}
