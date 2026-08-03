package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** One process-local Hub adapter alongside the one Manifold Runtime Host. */
public final class ConnectionHubProcess {
    public static final int PORT = 8876;
    private static ConnectionHubProcess instance;

    private final Context context;
    private final ConnectionHubRuntime runtime;
    private final ConnectionHubNsdAdvertiser discovery;
    private final ConnectionHubWifiBinding wifiBinding;
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

    public synchronized void startFromWearer() throws IOException {
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
                });
        try {
            int port = next.start(bindAddress, PORT);
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
                            final AtomicBoolean completed = new AtomicBoolean();
                            Message message = Message.obtain(null, ConnectionHubAdmissionService.MESSAGE_SURFACE_COMMAND);
                            Bundle data = new Bundle();
                            data.putString("request_id", dispatch.requestId);
                            data.putString("surface_id", dispatch.surfaceId);
                            data.putString("command", dispatch.command);
                            data.putString("args_json", dispatch.argsJson);
                            data.putString("authority_receipt_json", dispatch.authorityReceiptJson);
                            data.putString("effect_binding_json", expectedEffectBinding);
                            message.setData(data);
                            android.os.Handler responseHandler = new android.os.Handler(android.os.Looper.getMainLooper()) {
                                @Override public void handleMessage(Message response) {
                                    if (!completed.compareAndSet(false, true)) return;
                                    Bundle result = response.getData();
                                    String returnedBinding = result.getString("effect_binding_json", "");
                                    String effectStatus = result.getString("effect_status", "");
                                    if (!expectedEffectBinding.equals(returnedBinding)
                                            || !("queued".equals(effectStatus)
                                                    || "observed".equals(effectStatus)
                                                    || "rejected".equals(effectStatus))) {
                                        callback.onResult(false, "provider_effect_receipt_invalid", "{}");
                                        return;
                                    }
                                    boolean observed = "observed".equals(effectStatus)
                                            && result.getBoolean("provider_applied", false);
                                    callback.onResult(observed,
                                            observed ? "provider_effect_observed"
                                                    : ("queued".equals(effectStatus)
                                                            ? "provider_effect_queued"
                                                            : "provider_effect_rejected"),
                                            result.getString("state_json", "{}"));
                                }
                            };
                            message.replyTo = new Messenger(responseHandler);
                            provider.send(message);
                            responseHandler.postDelayed(new Runnable() {
                                @Override public void run() {
                                    if (completed.compareAndSet(false, true)) {
                                        callback.onResult(false, "provider_effect_receipt_timeout", "{}");
                                    }
                                }
                            }, ConnectionHubProtocol.PROVIDER_EFFECT_RECEIPT_DEADLINE_MS);
                        } catch (Exception error) {
                            callback.onResult(false, "provider_dispatch_failed", "{}");
                        }
                    }
                });
    }

    public int unregisterProvider(
            HubProviderIdentity identity,
            String providerInstanceId,
            String reason) {
        return runtime.unregisterProvider(identity, providerInstanceId, reason);
    }

}
