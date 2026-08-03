package io.github.mesmerprism.rustyquest.connection_hub_sample;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;

/** Second-package conformance provider for persistent Hub handoff validation. */
public final class ConnectionHubSampleActivity extends Activity {
    private static final String BROKER_PACKAGE = "io.github.mesmerprism.rustymanifold.broker";
    private static final String BROKER_SERVICE =
            "io.github.mesmerprism.rustymanifold.broker.ConnectionHubAdmissionService";
    private static final String PROVIDER_CAPABILITY =
            "capability.connection_hub.provider.register";
    private static final String SURFACE_ID = "surface.connection_hub_sample.toggle";
    private static final String COMMAND_ID = "command.connection_hub_sample.toggle";
    private static final String PARAMS_SHA256 =
            "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";
    private static final String CONTRACT_SHA256 =
            "sha256:48019a4a7a00c9ee6d694927727f54093b6946c1f894b99214c5aeb5629472c4";
    private static final int ISSUE = 1;
    private static final int AUTHORIZE = 2;
    private static final int EVIDENCE = 6;
    private static final int REGISTER = 20;
    private static final int UPDATE = 21;
    private static final int UNREGISTER = 22;
    private static final int COMMAND = 23;

    private final SecureRandom random = new SecureRandom();
    private final Handler handler = new ProviderHandler(Looper.getMainLooper());
    private final Messenger callback = new Messenger(handler);
    private Messenger broker;
    private boolean bound;
    private boolean registered;
    private boolean toggled;
    private long admissionRevision;
    private String tokenId = "";
    private Stage stage = Stage.IDLE;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setTextSize(20f);
        status.setPadding(32, 32, 32, 32);
        render("idle");
        setContentView(status);
    }

    @Override protected void onStart() {
        super.onStart();
        Intent intent = new Intent().setComponent(new ComponentName(BROKER_PACKAGE, BROKER_SERVICE));
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) { render("broker unavailable"); }
    }

    @Override protected void onStop() {
        if (registered) {
            Bundle data = new Bundle();
            data.putString("surface_id", SURFACE_ID);
            send(UNREGISTER, data);
            registered = false;
        }
        if (bound) { unbindService(connection); }
        bound = false;
        broker = null;
        stage = Stage.IDLE;
        super.onStop();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            broker = new Messenger(service);
            stage = Stage.EVIDENCE;
            send(EVIDENCE, new Bundle());
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            broker = null;
            registered = false;
            render("broker disconnected");
        }
    };

    private final class ProviderHandler extends Handler {
        ProviderHandler(Looper looper) { super(looper); }
        @Override public void handleMessage(Message message) {
            if (message.what == COMMAND) { handleCommand(message); return; }
            try {
                String error = message.getData().getString("error", "");
                if (!error.isEmpty()) { throw new SecurityException(error); }
                JSONObject response = new JSONObject(
                        message.getData().getString("response_json", "{}"));
                if (stage == Stage.EVIDENCE) {
                    admissionRevision = response.getJSONObject("runtime")
                            .getJSONObject("admission_snapshot")
                            .getLong("authority_revision");
                    stage = Stage.ISSUE;
                    Bundle data = requestBase();
                    data.putString("capabilities", PROVIDER_CAPABILITY);
                    data.putLong("token_ttl_ms", 30_000L);
                    send(ISSUE, data);
                } else if (stage == Stage.ISSUE) {
                    JSONObject receipt = response.getJSONObject("receipt");
                    requireApplied(receipt);
                    tokenId = receipt.getJSONObject("token").getString("token_id");
                    admissionRevision = receipt.getLong("resulting_authority_revision");
                    stage = Stage.AUTHORIZE;
                    Bundle data = requestBase();
                    data.putString("token_id", tokenId);
                    data.putString("capability_id", PROVIDER_CAPABILITY);
                    send(AUTHORIZE, data);
                } else if (stage == Stage.AUTHORIZE) {
                    JSONObject receipt = response.getJSONObject("receipt");
                    requireApplied(receipt);
                    admissionRevision = receipt.getLong("resulting_authority_revision");
                    stage = Stage.REGISTER;
                    send(REGISTER, registration());
                } else if (stage == Stage.REGISTER) {
                    if (!response.optBoolean("applied", false)) {
                        throw new SecurityException("surface rejected");
                    }
                    registered = true;
                    stage = Stage.ACTIVE;
                    render("surface registered");
                }
            } catch (Exception error) {
                render("rejected: " + error.getClass().getSimpleName());
            }
        }
    }

    private void handleCommand(Message message) {
        Bundle result = new Bundle();
        try {
            String requestId = message.getData().getString("request_id", "");
            String surfaceId = message.getData().getString("surface_id", "");
            String command = message.getData().getString("command", "");
            JSONObject args = new JSONObject(message.getData().getString("args_json", "{}"));
            JSONObject receipt = new JSONObject(
                    message.getData().getString("authority_receipt_json", "{}"));
            JSONObject authorization = receipt.getJSONObject("command_authorization");
            if (!receipt.getBoolean("applied")
                    || !"authorize_surface_command".equals(receipt.getString("operation"))
                    || !requestId.equals(authorization.getString("request_id"))
                    || !SURFACE_ID.equals(surfaceId)
                    || !SURFACE_ID.equals(authorization.getString("surface_id"))
                    || !COMMAND_ID.equals(command)
                    || !COMMAND_ID.equals(authorization.getString("command_id"))
                    || !PARAMS_SHA256.equals(authorization.getString("typed_params_sha256"))
                    || authorization.getBoolean("proves_application_effect")
                    || args.length() != 0) {
                throw new SecurityException("authorization binding mismatch");
            }
            toggled = !toggled;
            render("command applied");
            publishState();
            result.putBoolean("provider_applied", true);
            result.putString("status", "toggle_applied");
            result.putString("state_json", state().toString());
        } catch (Exception error) {
            result.putBoolean("provider_applied", false);
            result.putString("status", "toggle_rejected");
            result.putString("state_json", state().toString());
        }
        try {
            Message response = Message.obtain(null, COMMAND);
            response.setData(result);
            if (message.replyTo != null) { message.replyTo.send(response); }
        } catch (Exception ignored) {}
    }

    private Bundle registration() throws Exception {
        JSONObject registration = new JSONObject()
                .put("$schema", "rusty.quest.connection_hub.surface_registration.v1")
                .put("schema_version", 1)
                .put("surface_id", SURFACE_ID)
                .put("display_label", "Connection Hub Sample")
                .put("description", "Second package used to validate persistent controller handoff")
                .put("surface_contract_sha256", CONTRACT_SHA256)
                .put("commands", new JSONArray().put(new JSONObject()
                        .put("command", COMMAND_ID)
                        .put("display_label", "Toggle")
                        .put("required_controller_capability",
                                "capability.connection_hub_sample.toggle")))
                .put("state", state());
        Bundle data = new Bundle();
        data.putString("surface_registration_json", registration.toString());
        return data;
    }

    private void publishState() {
        if (!registered) { return; }
        Bundle data = new Bundle();
        data.putString("surface_id", SURFACE_ID);
        data.putString("state_json", state().toString());
        send(UPDATE, data);
    }

    private JSONObject state() {
        java.util.LinkedHashMap<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("toggled", toggled);
        return new JSONObject(value);
    }

    private Bundle requestBase() {
        Bundle data = new Bundle();
        data.putString("request_id", requestId());
        data.putLong("expected_authority_revision", admissionRevision);
        return data;
    }

    private String requestId() {
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        StringBuilder value = new StringBuilder("hub-sample.");
        for (byte item : bytes) { value.append(String.format("%02x", item & 0xff)); }
        java.util.Arrays.fill(bytes, (byte) 0);
        return value.toString();
    }

    private void send(int what, Bundle data) {
        try {
            Message message = Message.obtain(null, what);
            message.setData(data);
            message.replyTo = callback;
            if (broker == null) { throw new IllegalStateException("broker unavailable"); }
            broker.send(message);
        } catch (Exception error) { render("send failed"); }
    }

    private static void requireApplied(JSONObject receipt) throws Exception {
        if (!receipt.getBoolean("applied")) { throw new SecurityException("admission rejected"); }
    }

    private void render(String phase) {
        if (status != null) {
            status.setText("Connection Hub Sample\n" + phase + "\nToggle state: " + toggled);
        }
    }

    private enum Stage { IDLE, EVIDENCE, ISSUE, AUTHORIZE, REGISTER, ACTIVE }
}
