package io.github.mesmerprism.rustymanifold.broker;

import org.json.JSONObject;

/** Fixed low-rate Connection Hub wire and Binder contract. */
public final class ConnectionHubProtocol {
    public static final String PROTOCOL_SCHEMA = "rusty.quest.connection_hub.v1";
    public static final String STATUS_SCHEMA = "rusty.quest.connection_hub.status.v1";
    public static final String PAIR_REQUEST_SCHEMA = "rusty.quest.connection_hub.pair_request.v1";
    public static final String PAIR_RECEIPT_SCHEMA = "rusty.quest.connection_hub.pair_receipt.v1";
    public static final String REVOKE_REQUEST_SCHEMA = "rusty.quest.connection_hub.revoke_request.v1";
    public static final String REVOKE_RECEIPT_SCHEMA = "rusty.quest.connection_hub.revoke_receipt.v1";
    public static final String SURFACE_REGISTRATION_SCHEMA =
            "rusty.quest.connection_hub.surface_registration.v1";
    public static final String SURFACE_STATE_SCHEMA =
            "rusty.quest.connection_hub.surface_state.v1";
    public static final String SURFACE_COMMAND_SCHEMA =
            "rusty.quest.connection_hub.surface_command.v1";
    public static final String SOCKET_AUTHENTICATE_SCHEMA =
            "rusty.quest.connection_hub.socket_authenticate.v1";
    public static final String SOCKET_AUTHENTICATION_RECEIPT_SCHEMA =
            "rusty.quest.connection_hub.socket_authentication_receipt.v1";
    public static final String SURFACE_SNAPSHOT_SCHEMA =
            "rusty.quest.connection_hub.surface_snapshot.v1";
    public static final String SURFACE_AVAILABLE_SCHEMA =
            "rusty.quest.connection_hub.surface_available.v1";
    public static final String SURFACE_REMOVED_SCHEMA =
            "rusty.quest.connection_hub.surface_removed.v1";
    public static final String COMMAND_RECEIPT_SCHEMA =
            "rusty.quest.connection_hub.command_receipt.v1";

    public static final String STATUS_PATH = "/v1/status";
    public static final String PAIR_PATH = "/v1/pair";
    public static final String REVOKE_PATH = "/v1/revoke";
    public static final String SOCKET_PATH = "/v1/socket";

    public static final int MAX_SURFACES = 32;
    public static final int MAX_COMMANDS = 32;
    public static final int MAX_OBJECT_KEYS = 16;
    public static final int MAX_SURFACE_ID_CHARS = 96;
    public static final int MAX_COMMAND_ID_CHARS = 96;
    public static final int MAX_REQUEST_ID_CHARS = 96;
    public static final int MAX_LABEL_CHARS = 96;
    public static final int MAX_DESCRIPTION_CHARS = 160;
    public static final int MAX_SCALAR_STRING_CHARS = 256;
    public static final int MAX_JSON_UTF8_BYTES = 4096;
    public static final int MAX_HTTP_BODY_BYTES = 8192;
    public static final int MAX_HTTP_HEADER_BYTES = 16384;
    public static final int MAX_SOCKET_FRAME_BYTES = 8192;
    public static final int MAX_SOCKET_SESSIONS = 4;
    public static final int MAX_HTTP_CLIENTS = 8;
    public static final int MAX_PAIR_ATTEMPTS_PER_WINDOW = 5;
    public static final int MAX_SOCKET_AUTH_FAILURES_PER_WINDOW = 8;
    public static final long AUTH_RATE_WINDOW_MS = 60_000L;

    public static final String CONFIDENTIALITY = "none";
    public static final String SECURITY_MODE = "paired_trusted_lan_experimental";
    public static final boolean PRODUCTION_ELIGIBLE = false;

    public static JSONObject socketAuthenticationReceipt(long transportEpoch) {
        try {
            return new JSONObject()
                    .put("$schema", SOCKET_AUTHENTICATION_RECEIPT_SCHEMA)
                    .put("type", "authentication_receipt")
                    .put("accepted", true)
                    .put("status", "authenticated")
                    .put("transport_epoch", transportEpoch)
                    .put("confidentiality", CONFIDENTIALITY)
                    .put("production_eligible", PRODUCTION_ELIGIBLE);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ConnectionHubProtocol() {}
}
