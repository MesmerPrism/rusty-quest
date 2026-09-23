package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Debug-build shell adapter that requests, but never applies, one Manifold media action. */
public final class MediaActionDebugControlProvider extends ContentProvider {
    private static final String CLIENT_ID = "client.quest.spatial-camera-panel";
    private static final String AUTHORITY_PACKAGE = "io.github.mesmerprism.rustymanifold.broker";
    private static final String AUTHORITY_SERVICE = AUTHORITY_PACKAGE + ".ManifoldAdmissionService";
    private static final String RECEIPT_SCHEMA = "rusty.quest.media_action.debug_request_receipt.v1";
    private static final int ISSUE_TOKEN = 1;
    private static final int AUTHORIZE_USE = 2;
    private static final int MUTATE_RUNTIME = 4;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        if (!BuildConfig.DEBUG) {
            throw new SecurityException("media_action_debug_operator_requires_debug_build");
        }
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw new SecurityException("media_action_debug_operator_requires_shell_uid");
        }
        if (!"hold-pending".equals(method)) {
            throw new IllegalArgumentException("media_action_debug_method_not_registered");
        }
        if (!ACTIVE.compareAndSet(false, true)) {
            throw new IllegalStateException("media_action_debug_operation_already_active");
        }
        try {
            String operation = requireOperation(extras);
            long revision = requirePositiveLong(extras, "expected_admission_authority_revision");
            JSONObject receipt = new PendingRequest(requireApplicationContext(), operation, revision).execute();
            Bundle output = new Bundle();
            output.putString("receipt_b64", Base64.encodeToString(
                    receipt.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            return output;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "media_action_debug_request_failed_" + error.getClass().getSimpleName(), error);
        } finally {
            ACTIVE.set(false);
        }
    }

    private Context requireApplicationContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("media_action_debug_context_missing");
        return context.getApplicationContext();
    }

    private static String requireOperation(Bundle extras) {
        String value = extras == null ? "" : extras.getString("operation", "").trim();
        if (!"start".equals(value) && !"stop".equals(value)) {
            throw new IllegalArgumentException("media_action_debug_invalid_operation");
        }
        return value;
    }

    private static long requirePositiveLong(Bundle extras, String field) {
        String value = extras == null ? "" : extras.getString(field, "").trim();
        if (!value.matches("[0-9]{1,19}")) {
            throw new IllegalArgumentException("media_action_debug_invalid_" + field);
        }
        long parsed = Long.parseLong(value);
        if (parsed <= 0L) throw new IllegalArgumentException("media_action_debug_invalid_" + field);
        return parsed;
    }

    private static final class PendingRequest {
        private final Context context;
        private final String operation;
        private final String capability;
        private final String commandId;
        private final long expectedAdmissionRevision;
        private final String namespace = "request.media_debug."
                + UUID.randomUUID().toString().replace("-", "");
        private final CountDownLatch finished = new CountDownLatch(1);
        private final Messenger reply = new Messenger(new ReplyHandler(Looper.getMainLooper()));
        private Messenger service;
        private boolean bound;
        private int stage;
        private String tokenId;
        private String useRequestId;
        private String providerEpochId;
        private long admissionRevision;
        private long runtimeRevision;
        private JSONObject receipt;
        private Exception failure;

        PendingRequest(Context context, String operation, long expectedAdmissionRevision) {
            this.context = context;
            this.operation = operation;
            this.expectedAdmissionRevision = expectedAdmissionRevision;
            capability = "capability.command.media.session." + operation;
            commandId = "command.media.session." + operation;
        }

        JSONObject execute() throws Exception {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(AUTHORITY_PACKAGE, AUTHORITY_SERVICE));
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) throw new IllegalStateException("media_action_debug_bind_rejected");
            try {
                if (!finished.await(15L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("media_action_debug_timeout");
                }
                if (failure != null) throw failure;
                if (receipt == null) throw new IllegalStateException("media_action_debug_receipt_missing");
                return receipt;
            } finally {
                if (bound) {
                    try { context.unbindService(connection); } catch (Exception ignored) {}
                    bound = false;
                }
            }
        }

        private final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = new Messenger(binder);
                try {
                    Bundle data = new Bundle();
                    data.putString("request_id", requestId("issue"));
                    data.putLong("expected_authority_revision", expectedAdmissionRevision);
                    data.putString("capabilities", capability);
                    data.putLong("token_ttl_ms", 30_000L);
                    send(ISSUE_TOKEN, data);
                } catch (Exception error) { fail(error); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (finished.getCount() > 0L) {
                    fail(new IllegalStateException("media_action_debug_authority_disconnected"));
                }
            }
        };

        private final class ReplyHandler extends Handler {
            ReplyHandler(Looper looper) { super(looper); }
            @Override public void handleMessage(Message message) {
                try {
                    String error = message.getData().getString("error", "");
                    if (!error.isEmpty()) throw new IllegalStateException(error);
                    JSONObject response = new JSONObject(
                            message.getData().getString("response_json", "{}"));
                    if (stage == 0) {
                        JSONObject responseReceipt = response.getJSONObject("receipt");
                        if (!responseReceipt.optBoolean("applied", false)) {
                            throw new IllegalStateException("media_action_debug_issue_rejected");
                        }
                        tokenId = responseReceipt.getJSONObject("token").getString("token_id");
                        admissionRevision = responseReceipt.getLong("resulting_authority_revision");
                        runtimeRevision = response.getLong("runtime_host_revision");
                        providerEpochId = response.getString("provider_epoch_id");
                        useRequestId = requestId("authorize");
                        stage = 1;
                        Bundle data = new Bundle();
                        data.putString("request_id", useRequestId);
                        data.putLong("expected_authority_revision", admissionRevision);
                        data.putString("token_id", tokenId);
                        data.putString("capability_id", capability);
                        send(AUTHORIZE_USE, data);
                    } else if (stage == 1) {
                        JSONObject responseReceipt = response.getJSONObject("receipt");
                        if (!responseReceipt.optBoolean("applied", false)) {
                            throw new IllegalStateException("media_action_debug_authorize_rejected");
                        }
                        admissionRevision = responseReceipt.getLong("resulting_authority_revision");
                        stage = 2;
                        Bundle data = new Bundle();
                        data.putString("mutation_json", mutation().toString());
                        send(MUTATE_RUNTIME, data);
                    } else if (stage == 2) {
                        if (!response.optBoolean("accepted", false)) {
                            throw new IllegalStateException("media_action_debug_mutation_rejected");
                        }
                        JSONObject application = response.getJSONObject("mutation_receipt")
                                .getJSONObject("adapter_receipt").getJSONObject("application");
                        runtimeRevision = application.getLong("resulting_authority_revision");
                        receipt = new JSONObject()
                                .put("$schema", RECEIPT_SCHEMA)
                                .put("operation", operation)
                                .put("accepted", true)
                                .put("client_id", CLIENT_ID)
                                .put("provider_epoch_id", providerEpochId)
                                .put("admission_authority_revision", admissionRevision)
                                .put("runtime_authority_revision", runtimeRevision)
                                .put("platform_completion_separate", true)
                                .put("token_serialized", false)
                                .put("secret_serialized", false);
                        finished.countDown();
                    } else {
                        throw new IllegalStateException("media_action_debug_unexpected_stage");
                    }
                } catch (Exception error) { fail(error); }
            }
        }

        private JSONObject mutation() throws Exception {
            String canonical = "{\"$schema\":\"rusty.quest.broker.effect_params.v1\","
                    + "\"command_id\":\"" + commandId + "\",\"values\":{}}";
            JSONObject digest = new JSONObject()
                    .put("$schema", "rusty.manifold.runtime_host.typed_params_digest.v1")
                    .put("params_type_id", "rusty.quest.broker.effect_params.v1")
                    .put("canonical_sha256", "sha256:" + sha256Hex(canonical))
                    .put("canonical_size_bytes", canonical.getBytes(StandardCharsets.UTF_8).length);
            long now = System.currentTimeMillis();
            JSONObject command = new JSONObject()
                    .put("$schema", "rusty.manifold.runtime_host.command_request.v1")
                    .put("request_id", requestId("command"))
                    .put("expected_authority_revision", runtimeRevision)
                    .put("requester_id", CLIENT_ID)
                    .put("command_id", commandId)
                    .put("lease_id", "lease.broker.media-session." + CLIENT_ID)
                    .put("params_digest", digest)
                    .put("issued_at_ms", now)
                    .put("expires_at_ms", now + 30_000L);
            JSONObject params = new JSONObject()
                    .put("$schema", "rusty.quest.broker.effect_params.v1")
                    .put("command_id", commandId)
                    .put("values", new JSONObject());
            return new JSONObject()
                    .put("$schema", "rusty.quest.broker.server_mutation_request.v1")
                    .put("bridge_kind", "standalone_process_jni")
                    .put("provider_epoch_id", providerEpochId)
                    .put("admission_use_request_id", useRequestId)
                    .put("token_id", tokenId)
                    .put("expected_admission_authority_revision", admissionRevision)
                    .put("command", command)
                    .put("params", params);
        }

        private String requestId(String suffix) { return namespace + "." + suffix; }
        private void send(int what, Bundle data) throws Exception {
            if (service == null) throw new IllegalStateException("media_action_debug_service_missing");
            Message message = Message.obtain(null, what);
            message.setData(data);
            message.replyTo = reply;
            service.send(message);
        }
        private void fail(Exception error) { failure = error; finished.countDown(); }
        private static String sha256Hex(String value) throws Exception {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read_call_only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read_call_only"); }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) { throw new UnsupportedOperationException("read_call_only"); }
}
