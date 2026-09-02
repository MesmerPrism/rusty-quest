package io.github.mesmerprism.rustyquest.native_renderer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/**
 * Shell-authorized headless projection of the app-owned breath reducer.
 * The generated manifest protects this receiver with {@code android.permission.DUMP}.
 */
public final class BreathCompositionCommandReceiver extends BroadcastReceiver {
    public static final String ACTION_COMMAND =
        "io.github.mesmerprism.rustyquest.native_renderer.action.BREATH_COMPOSITION_COMMAND";
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
            JSONObject command = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String operation = command.optString("operation", "");
            if ("cancel".equals(operation) && !command.has("generation")) {
                JSONObject status = new JSONObject(ControlPanelActivity.nativeReadBreathCompositionStatus());
                JSONObject snapshot = status.optJSONObject("snapshot");
                long generation = snapshot == null ? 0L : snapshot.optLong("generation", 0L);
                if (generation <= 0L) {
                    reject("no-effective-generation");
                    return;
                }
                command.put("generation", generation);
            }
            String responseText = "status".equals(operation)
                ? ControlPanelActivity.nativeReadBreathCompositionStatus()
                : ControlPanelActivity.nativeApplyBreathCompositionCommand(command.toString());
            JSONObject response = new JSONObject(responseText);
            String commandStatus = response.optString("command_status", "");
            respond(response.toString(), "accepted".equals(commandStatus) || "status".equals(commandStatus));
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
                    .put("schema", "rusty.quest.native_renderer.breath_composition.cli_receipt.v1")
                    .put("command_status", "rejected")
                    .put("reason_code", reason)
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
