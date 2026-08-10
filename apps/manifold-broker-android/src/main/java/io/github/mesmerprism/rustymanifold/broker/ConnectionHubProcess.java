package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
/** One process-local Hub adapter alongside the one Manifold Runtime Host. */
public final class ConnectionHubProcess {
    public static final int PORT = 8876;
    private static final String LOG_TAG = "RqConnectionHub";
    private static ConnectionHubProcess instance;

    private final Context context;
    private final ConnectionHubRuntime runtime;
    private final ConnectionHubNsdAdvertiser discovery;
    private final ConnectionHubWifiBinding wifiBinding;
    private final ProviderEffectReplyRouter providerEffectReplies;
    private final Handler providerEffectResponseHandler;
    private final Messenger providerEffectResponseMessenger;
    private ConnectionHubHttpServer server;
    private InetAddress listenerAddress;

    private ConnectionHubProcess(Context context) {
        this.context = context.getApplicationContext();
        // Every Hub entry point (Activity, Service, Binder, or the debug-only
        // shell provider) must establish the shared Manifold admission owner
        // before the Hub authority derives its sealed product admission.
        // Activity/Service callers already do this, but keeping the ordering
        // here makes the process owner independently correct and idempotent.
        try {
            ManifoldRuntimeAuthorityBridge.initialize();
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Manifold broker authority initialization failed for Connection Hub",
                    error);
        }
        this.runtime = new ConnectionHubRuntime(
                new ManifoldConnectionHubAuthority(),
                new AndroidConnectionHubStateStore(this.context),
                new HubSurfaceRegistry(),
                new SecureRandom());
        this.providerEffectReplies = new ProviderEffectReplyRouter();
        this.providerEffectResponseHandler = new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message response) {
                Bundle result = response.getData();
                String outcome = providerEffectReplies.complete(
                        result.getString("effect_binding_json", ""),
                        result.getString("effect_status", ""),
                        result.getBoolean("provider_applied", false),
                        result.getString("state_json", "{}"));
                Log.i(LOG_TAG, "channel=rusty-connection-hub status=" + outcome);
            }
        };
        this.providerEffectResponseMessenger = new Messenger(providerEffectResponseHandler);
        this.discovery = new ConnectionHubNsdAdvertiser(this.context);
        this.wifiBinding = new ConnectionHubWifiBinding(
                this.context,
                new ConnectionHubWifiBinding.Listener() {
                    @Override public void onWifiBinding(InetAddress address) {
                        synchronized (ConnectionHubProcess.this) {
                            if (!runtime.desiredRunning()) { return; }
                            if (address.equals(listenerAddress) && server != null) { return; }
                            closeListenerOnly();
                            try { startListenerAt(address); }
                            catch (IOException failure) {
                                runtime.noteListenerFailure("wifi_listener_rebind_failed");
                            }
                        }
                    }
                    @Override public void onWifiUnavailable(String reason) {
                        synchronized (ConnectionHubProcess.this) {
                            closeListenerOnly();
                            runtime.noteListenerFailure(reason);
                        }
                    }
                });
    }

    public static synchronized ConnectionHubProcess get(Context context) {
        if (instance == null) {
            instance = new ConnectionHubProcess(context);
        }
        return instance;
    }

    public ConnectionHubRuntime runtime() { return runtime; }

    public ConnectionHubOperatorController operatorController() {
        return new ConnectionHubOperatorController(
                new ConnectionHubOperatorController.Port() {
                    @Override public JSONObject status() {
                        JSONObject status = runtime.status();
                        putJson(status, "active_controller_sessions", runtime.activeSessionCount());
                        putJson(status, "origin", displayOrigin());
                        return status;
                    }

                    @Override public void start() throws IOException {
                        startFromWearer();
                    }

                    @Override public void stop() {
                        stopFromWearer();
                    }

                    @Override public JSONObject pair(String code, String identity) {
                        JSONObject request = new JSONObject();
                        putJson(request, "$schema", ConnectionHubProtocol.PAIR_REQUEST_SCHEMA);
                        putJson(request, "pairing_code", code);
                        putJson(request, "controller_identity_sha256", identity);
                        return runtime.pair(request, "evidence.operator.wearer-action");
                    }

                    @Override public JSONObject revoke(String session, String reason) {
                        JSONObject request = new JSONObject();
                        putJson(request, "$schema", ConnectionHubProtocol.REVOKE_REQUEST_SCHEMA);
                        putJson(request, "session", session);
                        putJson(request, "reason", reason);
                        return runtime.revoke(request);
                    }

                    @Override public JSONObject forget() {
                        ConnectionHubAuthorityPort.Receipt authority = forgetFromWearer();
                        JSONObject result = new JSONObject();
                        putJson(result, "applied", authority.applied);
                        putJson(result, "status", authority.status);
                        return result;
                    }
                },
                new java.util.function.Supplier<String>() {
                    @Override public String get() {
                        return "operator." + Long.toUnsignedString(
                                java.util.concurrent.ThreadLocalRandom.current().nextLong(), 16);
                    }
                });
    }

    private static void putJson(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception error) {
            throw new IllegalStateException("connection_hub_operator_json_encode_failed", error);
        }
    }

    public synchronized void startFromWearer() throws IOException {
        if (!ConnectionHubStartService.isForegroundReady()) {
            stopFromWearer();
            return;
        }
        if (runtime.desiredRunning()) {
            wifiBinding.start();
            return;
        }
        runtime.startRequested();
        wifiBinding.start();
    }

    public synchronized void resumeDesiredListener() throws IOException {
        if (runtime.desiredRunning()) { wifiBinding.start(); }
    }

    private void startListenerAt(InetAddress bindAddress) throws IOException {
        if (server != null) { return; }
        ConnectionHubHttpServer next = new ConnectionHubHttpServer(
                runtime,
                new ConnectionHubHttpServer.AssetLoader() {
                    @Override public ConnectionHubHttpServer.Asset load(String path) throws IOException {
                        String contentType = path.endsWith(".js")
                                ? "text/javascript; charset=utf-8"
                                : path.endsWith(".css")
                                        ? "text/css; charset=utf-8"
                                        : "text/html; charset=utf-8";
                        try (java.io.InputStream input = context.getAssets().open(path)) {
                            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[4096];
                            int count;
                            while ((count = input.read(buffer)) >= 0) { output.write(buffer, 0, count); }
                            return new ConnectionHubHttpServer.Asset(contentType, output.toByteArray());
                        }
                    }
                },
                new ConnectionHubHttpServer.DiagnosticSink() {
                    @Override public void onStatus(String status, String reason) {
                        String marker = "channel=rusty-connection-hub status=" + status;
                        if (!"none".equals(reason)) { marker += " reason=" + reason; }
                        if (status.endsWith("_failed")) { Log.w(LOG_TAG, marker); }
                        else { Log.i(LOG_TAG, marker); }
                    }
                });
        try {
            int port = next.start(PORT);
            server = next;
            listenerAddress = bindAddress;
            discovery.start(port);
            runtime.noteListenerStarted();
        } catch (IOException failure) {
            next.close();
            runtime.noteListenerFailure("listener_start_failed_" + failure.getClass().getSimpleName());
            throw failure;
        }
    }

    public synchronized void stopFromWearer() {
        wifiBinding.close();
        closeListenerOnly();
        runtime.stopRequested();
    }

    public synchronized ConnectionHubAuthorityPort.Receipt forgetFromWearer() {
        return runtime.forgetRequested();
    }

    private void closeListenerOnly() {
        discovery.close();
        if (server != null) {
            server.close();
            server = null;
        }
        listenerAddress = null;
    }

    public String displayOrigin() {
        String host = listenerAddress == null ? null : listenerAddress.getHostAddress();
        return host == null ? "network unavailable" : "http://" + host + ":" + PORT;
    }

    public ConnectionHubAuthorityPort.Receipt registerSurface(
            HubProviderIdentity identity,
            String providerInstanceId,
            String admissionUseRequestId,
            JSONObject registration,
            final Messenger provider) throws Exception {
        return runtime.registerSurface(
                identity,
                providerInstanceId,
                admissionUseRequestId,
                registration,
                new HubSurfaceRegistry.Endpoint() {
                    @Override public void dispatch(
                            HubSurfaceRegistry.CommandDispatch dispatch,
                            final HubSurfaceRegistry.CommandResultCallback callback) {
                        String registeredEffectBinding = null;
                        try {
                            final JSONObject effectBinding = new JSONObject()
                                    .put("$schema", "rusty.quest.connection_hub.provider_effect_binding.v1")
                                    .put("request_id", dispatch.requestId)
                                    .put("surface_id", dispatch.surfaceId)
                                    .put("command", dispatch.command)
                                    .put("provider_instance_id", dispatch.providerInstanceId)
                                    .put("transport_epoch", dispatch.transportEpoch)
                                    .put("authorized_state_revision", dispatch.authorizedStateRevision)
                                    .put("authority_receipt_sha256", dispatch.authorityReceiptSha256);
                            final String expectedEffectBinding = effectBinding.toString();
                            registeredEffectBinding = expectedEffectBinding;
                            ProviderEffectReplyRouter.Registration pending =
                                    providerEffectReplies.register(
                                            dispatch.requestId,
                                            dispatch.providerInstanceId,
                                            expectedEffectBinding,
                                            new ProviderEffectReplyRouter.Completion() {
                                                @Override public void onResult(
                                                        boolean applied,
                                                        String status,
                                                        String stateJson) {
                                                    callback.onResult(applied, status, stateJson);
                                                    Log.i(LOG_TAG,
                                                            "channel=rusty-connection-hub "
                                                                    + "status=provider_effect_runtime_callback_completed");
                                                }
                                            });
                            if (!pending.accepted) {
                                Log.w(LOG_TAG, "channel=rusty-connection-hub status=" + pending.status);
                                callback.onResult(false, pending.status, "{}");
                                return;
                            }
                            Message message = Message.obtain(null, ConnectionHubAdmissionService.MESSAGE_SURFACE_COMMAND);
                            Bundle data = new Bundle();
                            data.putString("request_id", dispatch.requestId);
                            data.putString("surface_id", dispatch.surfaceId);
                            data.putString("command", dispatch.command);
                            data.putString("args_json", dispatch.argsJson);
                            data.putString("authority_receipt_json", dispatch.authorityReceiptJson);
                            data.putString("effect_binding_json", expectedEffectBinding);
                            message.setData(data);
                            message.replyTo = providerEffectResponseMessenger;
                            provider.send(message);
                            providerEffectResponseHandler.postDelayed(new Runnable() {
                                @Override public void run() {
                                    String outcome = providerEffectReplies.timeout(
                                            dispatch.requestId, expectedEffectBinding);
                                    if (!"provider_effect_reply_late_or_unknown".equals(outcome)) {
                                        Log.w(LOG_TAG,
                                                "channel=rusty-connection-hub status=" + outcome);
                                    }
                                }
                            }, ConnectionHubProtocol.PROVIDER_EFFECT_RECEIPT_DEADLINE_MS);
                        } catch (Exception error) {
                            String outcome;
                            if (registeredEffectBinding == null) {
                                callback.onResult(false, "provider_dispatch_failed", "{}");
                                outcome = "provider_effect_reply_dispatch_failed_before_registration";
                            } else {
                                outcome = providerEffectReplies.dispatchFailed(
                                        dispatch.requestId, registeredEffectBinding);
                            }
                            Log.w(LOG_TAG, "channel=rusty-connection-hub status=" + outcome);
                        }
                    }
                });
    }

    public int unregisterProvider(
            HubProviderIdentity identity,
            String providerInstanceId,
            String reason) {
        int cancelled = providerEffectReplies.cancelProvider(
                providerInstanceId, "provider_unregistered_before_effect_receipt");
        if (cancelled > 0) {
            Log.w(LOG_TAG,
                    "channel=rusty-connection-hub "
                            + "status=provider_effect_replies_cancelled_on_unregister count="
                            + cancelled);
        }
        return runtime.unregisterProvider(identity, providerInstanceId, reason);
    }

}
