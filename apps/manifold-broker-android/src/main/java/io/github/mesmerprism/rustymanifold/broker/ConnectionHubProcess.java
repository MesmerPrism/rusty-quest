package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;

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
                            Message message = Message.obtain(null, ConnectionHubAdmissionService.MESSAGE_SURFACE_COMMAND);
                            Bundle data = new Bundle();
                            data.putString("request_id", dispatch.requestId);
                            data.putString("surface_id", dispatch.surfaceId);
                            data.putString("command", dispatch.command);
                            data.putString("args_json", dispatch.argsJson);
                            data.putString("authority_receipt_json", dispatch.authorityReceiptJson);
                            message.setData(data);
                            message.replyTo = new Messenger(new android.os.Handler(android.os.Looper.getMainLooper()) {
                                @Override public void handleMessage(Message response) {
                                    Bundle result = response.getData();
                                    callback.onResult(
                                            result.getBoolean("provider_applied", false),
                                            result.getString("status", "provider_no_status"),
                                            result.getString("state_json", "{}"));
                                }
                            });
                            provider.send(message);
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
