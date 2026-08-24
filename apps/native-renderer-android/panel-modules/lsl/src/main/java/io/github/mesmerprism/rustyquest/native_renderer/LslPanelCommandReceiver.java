package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/**
 * Shell-authorized typed CLI projection of the same panel-owned LSL reducer.
 * The generated manifest protects this receiver with {@code android.permission.DUMP}.
 */
public final class LslPanelCommandReceiver extends BroadcastReceiver {
    public static final String ACTION_COMMAND =
        "io.github.mesmerprism.rustyquest.native_renderer.action.LSL_PANEL_COMMAND";
    public static final String EXTRA_COMMAND_BASE64 = "command_b64";
    private static final int MAX_COMMAND_BYTES = 16384;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!isOrderedBroadcast()) {
            return;
        }
        if (intent == null || !ACTION_COMMAND.equals(intent.getAction())) {
            reject("unexpected-action");
            return;
        }
        try {
            String encoded = intent.getStringExtra(EXTRA_COMMAND_BASE64);
            if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_COMMAND_BYTES * 2) {
                reject("missing-or-oversized-command");
                return;
            }
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP | Base64.NO_PADDING);
            if (bytes.length == 0 || bytes.length > MAX_COMMAND_BYTES) {
                reject("missing-or-oversized-command");
                return;
            }
            String commandJson = new String(bytes, StandardCharsets.UTF_8);
            JSONObject command = new JSONObject(commandJson);
            String operation = command.optString("operation", "");
            String responseText = ControlPanelActivity.applyLslTransportCommandFromOwner(commandJson);
            JSONObject response = new JSONObject(responseText);
            boolean accepted = "accepted".equals(response.optString("response_status", ""));
            if (accepted) {
                JSONObject acceptedConfig = response.optJSONObject("config");
                boolean persisted = acceptedConfig != null
                    && LslPanelConfigStore.save(context.getApplicationContext(), acceptedConfig);
                if ("reset".equals(operation)) {
                    persisted = LslPanelConfigStore.reset(context.getApplicationContext());
                }
                boolean effectiveEnabled = acceptedConfig != null
                    && acceptedConfig.optBoolean("enabled", false);
                LslMulticastLockManager.setFromCli(context, effectiveEnabled);
                response.put("persisted", persisted);
            }
            boolean statusReadback = "status".equals(response.optString("response_status", ""));
            respond(response.toString(), accepted || statusReadback);
        } catch (UnsatisfiedLinkError error) {
            reject("native-runtime-not-running");
        } catch (Exception error) {
            reject("invalid-command-or-response");
        }
    }

    private void reject(String reason) {
        try {
            respond(
                new JSONObject()
                    .put("schema", "rusty.quest.native_renderer.lsl.cli_receipt.v1")
                    .put("response_status", "rejected")
                    .put("response_reason", reason)
                    .toString(),
                false
            );
        } catch (Exception ignored) {
            setResultCode(Activity.RESULT_CANCELED);
        }
    }

    private void respond(String responseJson, boolean successful) {
        String encoded = Base64.encodeToString(
            responseJson.getBytes(StandardCharsets.UTF_8),
            Base64.NO_WRAP | Base64.NO_PADDING
        );
        setResultCode(successful ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
        setResultData(encoded);
    }
}
