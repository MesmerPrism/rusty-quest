package io.github.mesmerprism.rustyquest.qcl041;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

final class Qcl041AppNetworkTrace {
    static final String SECTION = "app_network_trace";
    private static final int ACCESS_FINE_LOCATION_MANIFEST_MAX_SDK = 32;

    private final Context context;
    private final Qcl041LifecycleArtifact artifact;
    private final Qcl041ProbeConfig config;
    private final long startedMs = SystemClock.elapsedRealtime();
    private final Object callbackLock = new Object();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback wifiP2pDefaultCallback;
    private ConnectivityManager.NetworkCallback wifiP2pCallback;
    private ConnectivityManager.NetworkCallback localNetworkCallback;
    private ConnectivityManager.NetworkCallback includeOtherUidWifiP2pCallback;
    private ConnectivityManager.NetworkCallback includeOtherUidLocalNetworkCallback;
    private final List<ConnectivityManager.NetworkCallback> requestNetworkCallbacks = new ArrayList<>();
    private int callbackEventCount;
    private int requestNetworkCallbackEventCount;
    private int includeOtherUidCallbackEventCount;
    private int includeOtherUidOnAvailableCount;
    private final JSONArray callbackEvents = new JSONArray();
    private final JSONArray requestNetworkCallbackEvents = new JSONArray();
    private final JSONArray includeOtherUidCallbackEvents = new JSONArray();
    private final List<Network> callbackNetworks = new ArrayList<>();
    private final List<Network> requestNetworkCallbackNetworks = new ArrayList<>();
    private final List<Network> includeOtherUidCallbackNetworks = new ArrayList<>();

    Qcl041AppNetworkTrace(
            Context context,
            Qcl041LifecycleArtifact artifact,
            Qcl041ProbeConfig config) {
        this.context = context.getApplicationContext();
        this.artifact = artifact;
        this.config = config;
    }

    void start() {
        connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        artifact.diagnostic(SECTION, "started", true);
        artifact.diagnostic(SECTION, "connectivity_manager_found", connectivityManager != null);
        recordAppIdentityAndPolicyDiagnostics();
        artifact.diagnostic(
                SECTION,
                "callback_scope",
                config.appNetworkRequestTraceRequested()
                        ? "registerNetworkCallback plus diagnostic requestNetwork callbacks"
                        : "registerNetworkCallback only; no requestNetwork mutation");
        artifact.diagnostic(
                SECTION,
                "request_network_trace_enabled",
                config.appNetworkRequestTraceRequested());
        artifact.diagnostic(
                SECTION,
                "request_network_trace_timeout_ms",
                requestNetworkTraceTimeoutMs());
        artifact.diagnostic(
                SECTION,
                "request_network_trace_scopes",
                config.q2qAppNetworkRequestTraceScopes);
        if (connectivityManager == null) {
            artifact.writeQuietly();
            return;
        }
        recordIncludeOtherUidDefaults();
        registerWifiP2pDefaultCapabilityCallback();
        registerWifiP2pCapabilityCallback();
        registerLocalNetworkCapabilityCallback();
        registerIncludeOtherUidWifiP2pCallback();
        registerIncludeOtherUidLocalNetworkCallback();
        registerBroadWifiCallback();
        startRequestNetworkTraceIfRequested();
        artifact.writeQuietly();
    }

    void stop() {
        unregisterRequestNetworkCallbacks();
        unregisterCallback("wifi", wifiCallback);
        unregisterCallback("wifi_p2p_default", wifiP2pDefaultCallback);
        unregisterCallback("wifi_p2p", wifiP2pCallback);
        unregisterCallback("local_network", localNetworkCallback);
        unregisterCallback("include_other_uid_wifi_p2p", includeOtherUidWifiP2pCallback);
        unregisterCallback("include_other_uid_local_network", includeOtherUidLocalNetworkCallback);
        wifiCallback = null;
        wifiP2pDefaultCallback = null;
        wifiP2pCallback = null;
        localNetworkCallback = null;
        includeOtherUidWifiP2pCallback = null;
        includeOtherUidLocalNetworkCallback = null;
        synchronized (callbackLock) {
            callbackNetworks.clear();
            requestNetworkCallbackNetworks.clear();
            includeOtherUidCallbackNetworks.clear();
        }
        artifact.diagnostic(SECTION, "stopped", true);
        artifact.writeQuietly();
    }

    void recordSnapshot(String phase, InetAddress groupOwnerAddress) {
        String prefix = sanitizePhase(phase) + "_";
        JSONObject snapshot = buildSnapshot(phase, groupOwnerAddress);
        artifact.diagnostic(SECTION, prefix + "elapsed_ms", SystemClock.elapsedRealtime() - startedMs);
        artifact.diagnostic(SECTION, prefix + "snapshot", snapshot);
        artifact.diagnostic(SECTION, prefix + "all_network_count", snapshot.optInt("all_network_count", 0));
        artifact.diagnostic(SECTION, prefix + "p2p_candidate_count", snapshot.optInt("p2p_candidate_count", 0));
        artifact.diagnostic(SECTION, prefix + "route_match_count", snapshot.optInt("route_match_count", 0));
        artifact.diagnostic(SECTION, prefix + "network_interface_count", snapshot.optInt("network_interface_count", 0));
        artifact.diagnostic(
                SECTION,
                prefix + "network_interface_p2p_count",
                snapshot.optInt("network_interface_p2p_count", 0));
        artifact.diagnostic(SECTION, "last_phase", phase);
        artifact.writeQuietly();
    }

    void recordTcpFailure(String phase, InetAddress groupOwnerAddress, Throwable ex) {
        String prefix = sanitizePhase(phase) + "_";
        artifact.diagnostic(
                SECTION,
                prefix + "tcp_failure",
                ex == null ? "" : ex.getClass().getSimpleName() + ": " + ex.getMessage());
        recordSnapshot(phase, groupOwnerAddress);
    }

    void recordTcpVariantPhase(String mode, String phase, InetAddress groupOwnerAddress) {
        recordSnapshot("tcp_variant_" + mode + "_" + phase, groupOwnerAddress);
    }

    Network latestCallbackWifiDirectNetwork(
            InetAddress groupOwnerAddress,
            String section,
            String prefix) {
        artifact.diagnostic(section, prefix + "callback_selection_attempted", true);
        artifact.diagnostic(section, prefix + "callback_trace_started", connectivityManager != null);
        if (connectivityManager == null) {
            artifact.diagnostic(section, prefix + "callback_seen", false);
            artifact.diagnostic(section, prefix + "callback_selected", false);
            return null;
        }
        List<Network> networks;
        synchronized (callbackLock) {
            networks = new ArrayList<>(callbackNetworks);
        }
        artifact.diagnostic(section, prefix + "callback_cached_network_count", networks.size());
        int inspected = 0;
        for (int index = networks.size() - 1; index >= 0; index--) {
            Network network = networks.get(index);
            CallbackCandidate candidate = inspectCallbackCandidate(network, groupOwnerAddress);
            String key = prefix + "callback_candidate_" + inspected + "_";
            artifact.diagnostic(section, key + "network", networkText(network));
            if (network != null) {
                artifact.diagnostic(section, key + "network_handle", network.getNetworkHandle());
            }
            artifact.diagnostic(section, key + "interface", candidate.interfaceName);
            artifact.diagnostic(section, key + "link_properties_found", candidate.linkPropertiesFound);
            artifact.diagnostic(section, key + "route_matches_group_owner", candidate.routeMatchesGroupOwner);
            artifact.diagnostic(section, key + "address_same_subnet_as_group_owner", candidate.addressSameSubnet);
            artifact.diagnostic(section, key + "p2p_interface", candidate.p2pInterface);
            artifact.diagnostic(section, key + "wifi_p2p_capability", candidate.wifiP2pCapability);
            artifact.diagnostic(section, key + "local_network_capability", candidate.localNetworkCapability);
            artifact.diagnostic(section, key + "wifi_transport", candidate.wifiTransport);
            artifact.diagnostic(section, key + "accepted", candidate.accepted);
            inspected++;
            if (candidate.accepted) {
                artifact.diagnostic(section, prefix + "callback_seen", true);
                artifact.diagnostic(section, prefix + "callback_selected", true);
                artifact.diagnostic(section, prefix + "callback_selected_network", networkText(network));
                artifact.diagnostic(section, prefix + "callback_selected_network_handle", network.getNetworkHandle());
                artifact.diagnostic(section, prefix + "callback_selected_interface", candidate.interfaceName);
                artifact.diagnostic(
                        section,
                        prefix + "callback_selected_link_properties_found",
                        candidate.linkPropertiesFound);
                artifact.diagnostic(
                        section,
                        prefix + "callback_selected_route_matches_group_owner",
                        candidate.routeMatchesGroupOwner);
                artifact.diagnostic(
                        section,
                        prefix + "callback_selected_wifi_p2p_capability",
                        candidate.wifiP2pCapability);
                artifact.diagnostic(
                        section,
                        prefix + "callback_selected_local_network_capability",
                        candidate.localNetworkCapability);
                artifact.diagnostic(SECTION, "latest_callback_wifi_direct_network_seen", true);
                artifact.diagnostic(SECTION, "latest_callback_wifi_direct_network", networkText(network));
                artifact.diagnostic(SECTION, "latest_callback_wifi_direct_network_handle", network.getNetworkHandle());
                artifact.diagnostic(SECTION, "latest_callback_wifi_direct_interface", candidate.interfaceName);
                artifact.diagnostic(
                        SECTION,
                        "latest_callback_wifi_direct_route_matches_group_owner",
                        candidate.routeMatchesGroupOwner);
                return network;
            }
        }
        artifact.diagnostic(section, prefix + "callback_seen", inspected > 0);
        artifact.diagnostic(section, prefix + "callback_selected", false);
        artifact.diagnostic(SECTION, "latest_callback_wifi_direct_network_seen", false);
        return null;
    }

    private void registerBroadWifiCallback() {
        try {
            wifiCallback = new TraceNetworkCallback("wifi");
            NetworkRequest request = baseNetworkRequestBuilder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            artifact.diagnostic(SECTION, "wifi_callback_request", request.toString());
            artifact.diagnostic(SECTION, "callback_wifi_transport_clear_capabilities_request", request.toString());
            connectivityManager.registerNetworkCallback(request, wifiCallback);
            artifact.diagnostic(SECTION, "wifi_callback_registered", true);
            artifact.diagnostic(SECTION, "callback_wifi_transport_clear_capabilities_registered", true);
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, "wifi_callback_register_error", errorText(ex));
            artifact.diagnostic(SECTION, "callback_wifi_transport_clear_capabilities_registered", false);
            wifiCallback = null;
        }
    }

    private void registerWifiP2pDefaultCapabilityCallback() {
        int wifiP2pCapability = capabilityByName("NET_CAPABILITY_WIFI_P2P");
        artifact.diagnostic(
                SECTION,
                "wifi_p2p_default_callback_capability_constant_found",
                wifiP2pCapability >= 0);
        artifact.diagnostic(SECTION, "wifi_p2p_default_callback_capability_constant_value", wifiP2pCapability);
        if (wifiP2pCapability < 0) {
            artifact.diagnostic(
                    SECTION,
                    "wifi_p2p_default_callback_skipped",
                    "NET_CAPABILITY_WIFI_P2P_unavailable");
            artifact.diagnostic(SECTION, "callback_wifi_p2p_default_registered", false);
            return;
        }
        try {
            wifiP2pDefaultCallback = new TraceNetworkCallback("wifi_p2p_default");
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(wifiP2pCapability)
                    .build();
            artifact.diagnostic(SECTION, "wifi_p2p_default_callback_request", request.toString());
            artifact.diagnostic(SECTION, "callback_wifi_p2p_default_request", request.toString());
            connectivityManager.registerNetworkCallback(request, wifiP2pDefaultCallback);
            artifact.diagnostic(SECTION, "wifi_p2p_default_callback_registered", true);
            artifact.diagnostic(SECTION, "callback_wifi_p2p_default_registered", true);
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, "wifi_p2p_default_callback_register_error", errorText(ex));
            artifact.diagnostic(SECTION, "callback_wifi_p2p_default_registered", false);
            wifiP2pDefaultCallback = null;
        }
    }

    private void registerWifiP2pCapabilityCallback() {
        int wifiP2pCapability = capabilityByName("NET_CAPABILITY_WIFI_P2P");
        artifact.diagnostic(SECTION, "wifi_p2p_callback_capability_constant_found", wifiP2pCapability >= 0);
        artifact.diagnostic(SECTION, "wifi_p2p_callback_capability_constant_value", wifiP2pCapability);
        if (wifiP2pCapability < 0) {
            artifact.diagnostic(SECTION, "wifi_p2p_callback_skipped", "NET_CAPABILITY_WIFI_P2P_unavailable");
            artifact.diagnostic(SECTION, "callback_wifi_p2p_clear_capabilities_registered", false);
            return;
        }
        try {
            wifiP2pCallback = new TraceNetworkCallback("wifi_p2p");
            NetworkRequest request = baseNetworkRequestBuilder()
                    .addCapability(wifiP2pCapability)
                    .build();
            artifact.diagnostic(SECTION, "wifi_p2p_callback_request", request.toString());
            artifact.diagnostic(SECTION, "callback_wifi_p2p_clear_capabilities_request", request.toString());
            connectivityManager.registerNetworkCallback(request, wifiP2pCallback);
            artifact.diagnostic(SECTION, "wifi_p2p_callback_registered", true);
            artifact.diagnostic(SECTION, "callback_wifi_p2p_clear_capabilities_registered", true);
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, "wifi_p2p_callback_register_error", errorText(ex));
            artifact.diagnostic(SECTION, "callback_wifi_p2p_clear_capabilities_registered", false);
            wifiP2pCallback = null;
        }
    }

    private void registerLocalNetworkCapabilityCallback() {
        int localNetworkCapability = capabilityByName("NET_CAPABILITY_LOCAL_NETWORK");
        artifact.diagnostic(
                SECTION,
                "local_network_callback_capability_constant_found",
                localNetworkCapability >= 0);
        artifact.diagnostic(
                SECTION,
                "local_network_callback_capability_constant_value",
                localNetworkCapability);
        if (localNetworkCapability < 0) {
            artifact.diagnostic(SECTION, "local_network_callback_skipped", "NET_CAPABILITY_LOCAL_NETWORK_unavailable");
            artifact.diagnostic(SECTION, "callback_local_network_reflection_registered", false);
            return;
        }
        try {
            localNetworkCallback = new TraceNetworkCallback("local_network");
            NetworkRequest request = baseNetworkRequestBuilder()
                    .addCapability(localNetworkCapability)
                    .build();
            artifact.diagnostic(SECTION, "local_network_callback_request", request.toString());
            artifact.diagnostic(SECTION, "callback_local_network_reflection_request", request.toString());
            connectivityManager.registerNetworkCallback(request, localNetworkCallback);
            artifact.diagnostic(SECTION, "local_network_callback_registered", true);
            artifact.diagnostic(SECTION, "callback_local_network_reflection_registered", true);
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, "local_network_callback_register_error", errorText(ex));
            artifact.diagnostic(SECTION, "callback_local_network_reflection_registered", false);
            localNetworkCallback = null;
        }
    }

    private void registerIncludeOtherUidWifiP2pCallback() {
        int wifiP2pCapability = capabilityByName("NET_CAPABILITY_WIFI_P2P");
        artifact.diagnostic(
                SECTION,
                "include_other_uid_wifi_p2p_callback_capability_constant_found",
                wifiP2pCapability >= 0);
        artifact.diagnostic(
                SECTION,
                "include_other_uid_wifi_p2p_callback_capability_constant_value",
                wifiP2pCapability);
        if (wifiP2pCapability < 0) {
            artifact.diagnostic(
                    SECTION,
                    "include_other_uid_wifi_p2p_callback_skipped",
                    "NET_CAPABILITY_WIFI_P2P_unavailable");
            artifact.diagnostic(SECTION, "callback_include_other_uid_wifi_p2p_registered", false);
            return;
        }
        includeOtherUidWifiP2pCallback = registerIncludeOtherUidCallback(
                "include_other_uid_wifi_p2p",
                baseNetworkRequestBuilder().addCapability(wifiP2pCapability));
    }

    private void registerIncludeOtherUidLocalNetworkCallback() {
        int localNetworkCapability = capabilityByName("NET_CAPABILITY_LOCAL_NETWORK");
        artifact.diagnostic(
                SECTION,
                "include_other_uid_local_network_callback_capability_constant_found",
                localNetworkCapability >= 0);
        artifact.diagnostic(
                SECTION,
                "include_other_uid_local_network_callback_capability_constant_value",
                localNetworkCapability);
        if (localNetworkCapability < 0) {
            artifact.diagnostic(
                    SECTION,
                    "include_other_uid_local_network_callback_skipped",
                    "NET_CAPABILITY_LOCAL_NETWORK_unavailable");
            artifact.diagnostic(SECTION, "callback_include_other_uid_local_network_registered", false);
            return;
        }
        includeOtherUidLocalNetworkCallback = registerIncludeOtherUidCallback(
                "include_other_uid_local_network",
                baseNetworkRequestBuilder().addCapability(localNetworkCapability));
    }

    private ConnectivityManager.NetworkCallback registerIncludeOtherUidCallback(
            String scope,
            NetworkRequest.Builder builder) {
        String callbackAlias = "callback_" + scope + "_registered";
        artifact.diagnostic(SECTION, scope + "_callback_attempted", true);
        if (!applyIncludeOtherUidNetworks(builder, scope)) {
            artifact.diagnostic(SECTION, scope + "_callback_registered", false);
            artifact.diagnostic(SECTION, callbackAlias, false);
            return null;
        }
        try {
            TraceNetworkCallback callback = new TraceNetworkCallback(scope, false, true);
            NetworkRequest request = builder.build();
            artifact.diagnostic(SECTION, scope + "_callback_request", request.toString());
            connectivityManager.registerNetworkCallback(request, callback);
            artifact.diagnostic(SECTION, scope + "_callback_registered", true);
            artifact.diagnostic(SECTION, callbackAlias, true);
            artifact.diagnostic(SECTION, "include_other_uid_callback_registered", true);
            return callback;
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, scope + "_callback_register_error", errorText(ex));
            artifact.diagnostic(SECTION, scope + "_callback_registered", false);
            artifact.diagnostic(SECTION, callbackAlias, false);
            return null;
        }
    }

    private boolean applyIncludeOtherUidNetworks(NetworkRequest.Builder builder, String scope) {
        artifact.diagnostic(SECTION, scope + "_set_include_other_uid_attempted", true);
        try {
            Method method = NetworkRequest.Builder.class.getMethod(
                    "setIncludeOtherUidNetworks",
                    boolean.class);
            method.invoke(builder, true);
            artifact.diagnostic(SECTION, "include_other_uid_supported", true);
            artifact.diagnostic(SECTION, scope + "_include_other_uid_supported", true);
            return true;
        } catch (Throwable ex) {
            artifact.diagnostic(SECTION, "include_other_uid_supported", false);
            artifact.diagnostic(SECTION, scope + "_include_other_uid_supported", false);
            artifact.diagnostic(SECTION, scope + "_include_other_uid_error", errorText(ex));
            return false;
        }
    }

    private void startRequestNetworkTraceIfRequested() {
        if (!config.appNetworkRequestTraceRequested()) {
            artifact.diagnostic(SECTION, "request_network_trace_started", false);
            return;
        }
        artifact.diagnostic(SECTION, "request_network_trace_started", true);
        int wifiP2pCapability = capabilityByName("NET_CAPABILITY_WIFI_P2P");
        artifact.diagnostic(
                SECTION,
                "request_wifi_p2p_capability_constant_found",
                wifiP2pCapability >= 0);
        artifact.diagnostic(
                SECTION,
                "request_wifi_p2p_capability_constant_value",
                wifiP2pCapability);
        if (!config.appNetworkRequestTraceScopeRequested("wifi_p2p")) {
            artifact.diagnostic(SECTION, "request_wifi_p2p_skipped", "scope_not_requested");
        } else if (wifiP2pCapability >= 0) {
            requestNetworkTrace(
                    "request_wifi_p2p",
                    baseNetworkRequestBuilder()
                            .addCapability(wifiP2pCapability)
                            .build());
        } else {
            artifact.diagnostic(SECTION, "request_wifi_p2p_skipped", "NET_CAPABILITY_WIFI_P2P_unavailable");
        }

        int localNetworkCapability = capabilityByName("NET_CAPABILITY_LOCAL_NETWORK");
        artifact.diagnostic(
                SECTION,
                "request_local_network_capability_constant_found",
                localNetworkCapability >= 0);
        artifact.diagnostic(
                SECTION,
                "request_local_network_capability_constant_value",
                localNetworkCapability);
        if (!config.appNetworkRequestTraceScopeRequested("local_network")) {
            artifact.diagnostic(SECTION, "request_local_network_skipped", "scope_not_requested");
        } else if (localNetworkCapability >= 0) {
            requestNetworkTrace(
                    "request_local_network",
                    baseNetworkRequestBuilder()
                            .addCapability(localNetworkCapability)
                            .build());
        } else {
            artifact.diagnostic(SECTION, "request_local_network_skipped", "NET_CAPABILITY_LOCAL_NETWORK_unavailable");
        }

        if (config.appNetworkRequestTraceScopeRequested("wifi")) {
            requestNetworkTrace(
                    "request_wifi",
                    baseNetworkRequestBuilder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .build());
        } else {
            artifact.diagnostic(SECTION, "request_wifi_skipped", "scope_not_requested");
        }
    }

    private void requestNetworkTrace(String scope, NetworkRequest request) {
        String prefix = scope + "_";
        artifact.diagnostic(SECTION, prefix + "request_network_attempted", true);
        artifact.diagnostic(SECTION, prefix + "request_network_timeout_ms", requestNetworkTraceTimeoutMs());
        artifact.diagnostic(SECTION, prefix + "request_network_request", request.toString());
        artifact.diagnostic(SECTION, prefix + "request_network_security_exception", false);
        artifact.diagnostic(SECTION, prefix + "request_network_restricted_network_security_exception", false);
        ConnectivityManager.NetworkCallback callback = new TraceNetworkCallback(scope, true);
        try {
            connectivityManager.requestNetwork(request, callback, requestNetworkTraceTimeoutMs());
            requestNetworkCallbacks.add(callback);
            artifact.diagnostic(SECTION, prefix + "request_network_requested", true);
        } catch (Exception ex) {
            String error = errorText(ex);
            boolean securityException = ex instanceof SecurityException;
            boolean restrictedNetwork = securityException
                    && error.toLowerCase(Locale.US).contains("restricted");
            artifact.diagnostic(SECTION, prefix + "request_network_error", error);
            artifact.diagnostic(SECTION, prefix + "request_network_security_exception", securityException);
            artifact.diagnostic(
                    SECTION,
                    prefix + "request_network_restricted_network_security_exception",
                    restrictedNetwork);
        }
    }

    private int requestNetworkTraceTimeoutMs() {
        return Math.max(500, config.q2qAppNetworkRequestTraceTimeoutMs);
    }

    private NetworkRequest.Builder baseNetworkRequestBuilder() {
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        try {
            builder.clearCapabilities();
        } catch (Throwable ignored) {
            removeCapabilityIfPresent(builder, "NET_CAPABILITY_INTERNET");
            removeCapabilityIfPresent(builder, "NET_CAPABILITY_TRUSTED");
            removeCapabilityIfPresent(builder, "NET_CAPABILITY_NOT_RESTRICTED");
            removeCapabilityIfPresent(builder, "NET_CAPABILITY_NOT_VPN");
        }
        return builder;
    }

    private void removeCapabilityIfPresent(NetworkRequest.Builder builder, String capabilityName) {
        int capability = capabilityByName(capabilityName);
        if (capability < 0) {
            return;
        }
        try {
            builder.removeCapability(capability);
        } catch (Throwable ignored) {
        }
    }

    private void unregisterCallback(String name, ConnectivityManager.NetworkCallback callback) {
        if (connectivityManager == null || callback == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(callback);
            artifact.diagnostic(SECTION, name + "_callback_unregistered", true);
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, name + "_callback_unregister_error", errorText(ex));
        }
    }

    private void unregisterRequestNetworkCallbacks() {
        if (requestNetworkCallbacks.isEmpty()) {
            return;
        }
        List<ConnectivityManager.NetworkCallback> callbacks =
                new ArrayList<>(requestNetworkCallbacks);
        requestNetworkCallbacks.clear();
        for (int index = 0; index < callbacks.size(); index++) {
            unregisterCallback("request_network_" + index, callbacks.get(index));
        }
    }

    private JSONObject buildSnapshot(String phase, InetAddress groupOwnerAddress) {
        JSONObject snapshot = new JSONObject();
        try {
            snapshot.put("phase", phase);
            snapshot.put("elapsed_ms", SystemClock.elapsedRealtime() - startedMs);
            snapshot.put("elapsed_realtime_ms", SystemClock.elapsedRealtime());
            snapshot.put("group_owner_address", addressText(groupOwnerAddress));
            snapshot.put("connectivity_manager_found", connectivityManager != null);
            snapshot.put("app_identity_and_policy", buildAppIdentityAndPolicySnapshot());
            snapshot.put("request_network_trace_enabled", config.appNetworkRequestTraceRequested());
            snapshot.put("request_network_trace_timeout_ms", requestNetworkTraceTimeoutMs());
            snapshot.put("request_network_trace_scopes", config.q2qAppNetworkRequestTraceScopes);
            synchronized (callbackLock) {
                snapshot.put("connectivity_callback_event_count", callbackEventCount);
                snapshot.put("connectivity_callback_events", new JSONArray(callbackEvents.toString()));
                snapshot.put("connectivity_callback_cached_network_count", callbackNetworks.size());
                snapshot.put("request_network_callback_event_count", requestNetworkCallbackEventCount);
                snapshot.put(
                        "request_network_callback_events",
                        new JSONArray(requestNetworkCallbackEvents.toString()));
                snapshot.put(
                        "request_network_callback_cached_network_count",
                        requestNetworkCallbackNetworks.size());
                snapshot.put("include_other_uid_callback_event_count", includeOtherUidCallbackEventCount);
                snapshot.put("include_other_uid_on_available_count", includeOtherUidOnAvailableCount);
                snapshot.put(
                        "include_other_uid_callback_events",
                        new JSONArray(includeOtherUidCallbackEvents.toString()));
                snapshot.put(
                        "include_other_uid_callback_cached_network_count",
                        includeOtherUidCallbackNetworks.size());
            }
            if (connectivityManager != null) {
                addConnectivityManagerState(snapshot, groupOwnerAddress);
                addRequestNetworkCallbackState(snapshot, groupOwnerAddress);
                addIncludeOtherUidCallbackState(snapshot, groupOwnerAddress);
            }
            addNetworkInterfaces(snapshot, groupOwnerAddress);
        } catch (JSONException ex) {
            artifact.diagnostic(SECTION, sanitizePhase(phase) + "_snapshot_error", errorText(ex));
        }
        return snapshot;
    }

    private void recordIncludeOtherUidDefaults() {
        artifact.diagnostic(SECTION, "include_other_uid_supported", false);
        artifact.diagnostic(SECTION, "include_other_uid_callback_registered", false);
        artifact.diagnostic(SECTION, "include_other_uid_callback_event_count", 0);
        artifact.diagnostic(SECTION, "include_other_uid_on_available_count", 0);
        artifact.diagnostic(SECTION, "include_other_uid_callback_cached_network_count", 0);
        artifact.diagnostic(SECTION, "include_other_uid_wifi_direct_candidate_seen", false);
        artifact.diagnostic(SECTION, "include_other_uid_network_handle", "");
        artifact.diagnostic(SECTION, "include_other_uid_link_properties_present", false);
        artifact.diagnostic(SECTION, "include_other_uid_interface", "");
        artifact.diagnostic(SECTION, "include_other_uid_has_wifi_p2p", false);
        artifact.diagnostic(SECTION, "include_other_uid_has_local_network", false);
        artifact.diagnostic(SECTION, "include_other_uid_bind_socket_attempted", false);
        artifact.diagnostic(SECTION, "include_other_uid_bind_socket_result", "not_attempted");
    }

    private void recordAppIdentityAndPolicyDiagnostics() {
        try {
            JSONObject snapshot = buildAppIdentityAndPolicySnapshot();
            artifact.diagnostic(SECTION, "app_identity_and_policy", snapshot);
            artifact.diagnostic(SECTION, "package_name", snapshot.optString("package_name", ""));
            artifact.diagnostic(SECTION, "uid", snapshot.optInt("uid", -1));
            artifact.diagnostic(SECTION, "pid", snapshot.optInt("pid", -1));
            artifact.diagnostic(
                    SECTION,
                    "network_permission_grants_all_present",
                    snapshot.optBoolean("network_permission_grants_all_present", false));
            artifact.diagnostic(
                    SECTION,
                    "network_permission_grants_all_declared_present",
                    snapshot.optBoolean("network_permission_grants_all_declared_present", false));
            artifact.diagnostic(
                    SECTION,
                    "sdk_int",
                    snapshot.optInt("sdk_int", -1));
            artifact.diagnostic(
                    SECTION,
                    "target_sdk_int",
                    snapshot.optInt("target_sdk_int", -1));
            artifact.diagnostic(
                    SECTION,
                    "permission_nearby_wifi_devices_applicable",
                    nearbyWifiDevicesPermissionApplies());
            artifact.diagnostic(
                    SECTION,
                    "permission_access_fine_location_applicable",
                    fineLocationPermissionApplies());
            artifact.diagnostic(
                    SECTION,
                    "permission_access_fine_location_manifest_max_sdk",
                    ACCESS_FINE_LOCATION_MANIFEST_MAX_SDK);
            artifact.diagnostic(
                    SECTION,
                    "permission_internet_granted",
                    permissionGranted(Manifest.permission.INTERNET));
            artifact.diagnostic(
                    SECTION,
                    "permission_access_network_state_granted",
                    permissionGranted(Manifest.permission.ACCESS_NETWORK_STATE));
            artifact.diagnostic(
                    SECTION,
                    "permission_change_network_state_granted",
                    permissionGranted(Manifest.permission.CHANGE_NETWORK_STATE));
            artifact.diagnostic(
                    SECTION,
                    "permission_access_wifi_state_granted",
                    permissionGranted(Manifest.permission.ACCESS_WIFI_STATE));
            artifact.diagnostic(
                    SECTION,
                    "permission_change_wifi_state_granted",
                    permissionGranted(Manifest.permission.CHANGE_WIFI_STATE));
            artifact.diagnostic(
                    SECTION,
                    "permission_nearby_wifi_devices_granted",
                    permissionGranted(Manifest.permission.NEARBY_WIFI_DEVICES));
            artifact.diagnostic(
                    SECTION,
                    "permission_access_fine_location_granted",
                    permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION));
            artifact.diagnostic(
                    SECTION,
                    "appop_nearby_wifi_devices_mode",
                    appOpModeName(checkAppOpMode("android:nearby_wifi_devices")));
            artifact.diagnostic(
                    SECTION,
                    "appop_fine_location_mode",
                    appOpModeName(checkAppOpMode("android:fine_location")));
            artifact.diagnostic(
                    SECTION,
                    "appop_wifi_scan_mode",
                    appOpModeName(checkAppOpMode("android:wifi_scan")));
        } catch (Exception ex) {
            artifact.diagnostic(SECTION, "app_identity_and_policy_error", errorText(ex));
        }
    }

    private JSONObject buildAppIdentityAndPolicySnapshot() throws JSONException {
        JSONObject snapshot = new JSONObject();
        snapshot.put("package_name", context.getPackageName());
        snapshot.put("uid", android.os.Process.myUid());
        snapshot.put("pid", android.os.Process.myPid());
        snapshot.put("sdk_int", Build.VERSION.SDK_INT);
        snapshot.put("target_sdk_int", context.getApplicationInfo().targetSdkVersion);
        snapshot.put("nearby_wifi_devices_permission_applicable", nearbyWifiDevicesPermissionApplies());
        snapshot.put("access_fine_location_permission_applicable", fineLocationPermissionApplies());
        snapshot.put(
                "access_fine_location_permission_manifest_max_sdk",
                ACCESS_FINE_LOCATION_MANIFEST_MAX_SDK);
        snapshot.put("permissions", buildPermissionSnapshot());
        snapshot.put("network_permission_grants_all_present", networkPermissionGrantsAllPresent());
        snapshot.put(
                "network_permission_grants_all_declared_present",
                networkPermissionGrantsAllDeclaredPresent());
        snapshot.put("app_ops", buildAppOpsSnapshot());
        return snapshot;
    }

    private JSONArray buildPermissionSnapshot() throws JSONException {
        JSONArray permissions = new JSONArray();
        addPermission(permissions, "internet", Manifest.permission.INTERNET, true);
        addPermission(permissions, "access_network_state", Manifest.permission.ACCESS_NETWORK_STATE, true);
        addPermission(permissions, "change_network_state", Manifest.permission.CHANGE_NETWORK_STATE, true);
        addPermission(permissions, "access_wifi_state", Manifest.permission.ACCESS_WIFI_STATE, true);
        addPermission(permissions, "change_wifi_state", Manifest.permission.CHANGE_WIFI_STATE, true);
        addPermission(
                permissions,
                "nearby_wifi_devices",
                Manifest.permission.NEARBY_WIFI_DEVICES,
                nearbyWifiDevicesPermissionApplies());
        addPermission(
                permissions,
                "access_fine_location",
                Manifest.permission.ACCESS_FINE_LOCATION,
                fineLocationPermissionApplies());
        return permissions;
    }

    private void addPermission(
            JSONArray permissions,
            String label,
            String permission,
            boolean applicable)
            throws JSONException {
        JSONObject item = new JSONObject();
        item.put("label", label);
        item.put("permission", permission);
        item.put("applicable", applicable);
        item.put("granted", permissionGranted(permission));
        permissions.put(item);
    }

    private boolean networkPermissionGrantsAllPresent() {
        return permissionGranted(Manifest.permission.INTERNET)
                && permissionGranted(Manifest.permission.ACCESS_NETWORK_STATE)
                && permissionGranted(Manifest.permission.CHANGE_NETWORK_STATE)
                && permissionGranted(Manifest.permission.ACCESS_WIFI_STATE)
                && permissionGranted(Manifest.permission.CHANGE_WIFI_STATE)
                && (!nearbyWifiDevicesPermissionApplies()
                        || permissionGranted(Manifest.permission.NEARBY_WIFI_DEVICES))
                && (!fineLocationPermissionApplies()
                        || permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION));
    }

    private boolean networkPermissionGrantsAllDeclaredPresent() {
        return permissionGranted(Manifest.permission.INTERNET)
                && permissionGranted(Manifest.permission.ACCESS_NETWORK_STATE)
                && permissionGranted(Manifest.permission.CHANGE_NETWORK_STATE)
                && permissionGranted(Manifest.permission.ACCESS_WIFI_STATE)
                && permissionGranted(Manifest.permission.CHANGE_WIFI_STATE)
                && permissionGranted(Manifest.permission.NEARBY_WIFI_DEVICES)
                && permissionGranted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean nearbyWifiDevicesPermissionApplies() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    private boolean fineLocationPermissionApplies() {
        return Build.VERSION.SDK_INT <= ACCESS_FINE_LOCATION_MANIFEST_MAX_SDK;
    }

    private boolean permissionGranted(String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception ex) {
            return false;
        }
    }

    private JSONArray buildAppOpsSnapshot() throws JSONException {
        JSONArray appOps = new JSONArray();
        addAppOp(appOps, "nearby_wifi_devices", "android:nearby_wifi_devices");
        addAppOp(appOps, "fine_location", "android:fine_location");
        addAppOp(appOps, "coarse_location", "android:coarse_location");
        addAppOp(appOps, "wifi_scan", "android:wifi_scan");
        return appOps;
    }

    private void addAppOp(JSONArray appOps, String label, String op) throws JSONException {
        JSONObject item = new JSONObject();
        int mode = checkAppOpMode(op);
        item.put("label", label);
        item.put("op", op);
        item.put("mode", mode);
        item.put("mode_name", appOpModeName(mode));
        appOps.put(item);
    }

    private int checkAppOpMode(String op) {
        try {
            AppOpsManager appOpsManager =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOpsManager == null) {
                return Integer.MIN_VALUE;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return appOpsManager.unsafeCheckOpNoThrow(
                        op,
                        android.os.Process.myUid(),
                        context.getPackageName());
            }
            return appOpsManager.checkOpNoThrow(
                    op,
                    android.os.Process.myUid(),
                    context.getPackageName());
        } catch (Exception ex) {
            return Integer.MIN_VALUE;
        }
    }

    private static String appOpModeName(int mode) {
        if (mode == Integer.MIN_VALUE) {
            return "unavailable_or_unknown";
        }
        if (mode == AppOpsManager.MODE_ALLOWED) {
            return "allowed";
        }
        if (mode == AppOpsManager.MODE_IGNORED) {
            return "ignored";
        }
        if (mode == AppOpsManager.MODE_ERRORED) {
            return "errored";
        }
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return "default";
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && mode == AppOpsManager.MODE_FOREGROUND) {
            return "foreground";
        }
        return "mode_" + mode;
    }

    private void addConnectivityManagerState(JSONObject snapshot, InetAddress groupOwnerAddress)
            throws JSONException {
        Network activeNetwork = null;
        try {
            activeNetwork = connectivityManager.getActiveNetwork();
        } catch (Exception ex) {
            snapshot.put("active_network_error", errorText(ex));
        }
        snapshot.put("active_network", networkText(activeNetwork));
        try {
            Network boundNetwork = connectivityManager.getBoundNetworkForProcess();
            snapshot.put("bound_process_network", networkText(boundNetwork));
            if (boundNetwork != null) {
                snapshot.put("bound_process_network_handle", boundNetwork.getNetworkHandle());
            }
        } catch (Exception ex) {
            snapshot.put("bound_process_network_error", errorText(ex));
        }
        Network[] networks = connectivityManager.getAllNetworks();
        JSONArray networkArray = new JSONArray();
        int p2pCandidateCount = 0;
        int routeMatchCount = 0;
        for (Network network : networks) {
            JSONObject networkJson = describeNetwork(network, groupOwnerAddress);
            if (networkJson.optBoolean("p2p_candidate", false)) {
                p2pCandidateCount++;
            }
            if (networkJson.optBoolean("route_matches_group_owner", false)) {
                routeMatchCount++;
            }
            networkArray.put(networkJson);
        }
        snapshot.put("all_network_count", networks.length);
        snapshot.put("p2p_candidate_count", p2pCandidateCount);
        snapshot.put("route_match_count", routeMatchCount);
        snapshot.put("networks", networkArray);
    }

    private void addRequestNetworkCallbackState(JSONObject snapshot, InetAddress groupOwnerAddress)
            throws JSONException {
        JSONArray networkArray = new JSONArray();
        int p2pCandidateCount = 0;
        int routeMatchCount = 0;
        List<Network> networks;
        synchronized (callbackLock) {
            networks = new ArrayList<>(requestNetworkCallbackNetworks);
        }
        for (Network network : networks) {
            JSONObject networkJson = describeNetwork(network, groupOwnerAddress);
            if (networkJson.optBoolean("p2p_candidate", false)) {
                p2pCandidateCount++;
            }
            if (networkJson.optBoolean("route_matches_group_owner", false)) {
                routeMatchCount++;
            }
            networkArray.put(networkJson);
        }
        snapshot.put("request_network_cached_network_count", networks.size());
        snapshot.put("request_network_p2p_candidate_count", p2pCandidateCount);
        snapshot.put("request_network_route_match_count", routeMatchCount);
        snapshot.put("request_networks", networkArray);
    }

    private void addIncludeOtherUidCallbackState(JSONObject snapshot, InetAddress groupOwnerAddress)
            throws JSONException {
        JSONArray networkArray = new JSONArray();
        int p2pCandidateCount = 0;
        int routeMatchCount = 0;
        List<Network> networks;
        synchronized (callbackLock) {
            networks = new ArrayList<>(includeOtherUidCallbackNetworks);
        }
        for (Network network : networks) {
            JSONObject networkJson = describeNetwork(network, groupOwnerAddress);
            if (networkJson.optBoolean("p2p_candidate", false)) {
                p2pCandidateCount++;
            }
            if (networkJson.optBoolean("route_matches_group_owner", false)) {
                routeMatchCount++;
            }
            networkArray.put(networkJson);
        }
        snapshot.put("include_other_uid_cached_network_count", networks.size());
        snapshot.put("include_other_uid_p2p_candidate_count", p2pCandidateCount);
        snapshot.put("include_other_uid_route_match_count", routeMatchCount);
        snapshot.put("include_other_uid_networks", networkArray);
    }

    private JSONObject describeNetwork(Network network, InetAddress groupOwnerAddress) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("network", networkText(network));
        if (network == null) {
            return item;
        }
        item.put("network_handle", network.getNetworkHandle());
        LinkProperties properties = null;
        NetworkCapabilities capabilities = null;
        try {
            properties = connectivityManager.getLinkProperties(network);
        } catch (Exception ex) {
            item.put("link_properties_error", errorText(ex));
        }
        try {
            capabilities = connectivityManager.getNetworkCapabilities(network);
        } catch (Exception ex) {
            item.put("network_capabilities_error", errorText(ex));
        }
        boolean routeMatches = false;
        boolean addressSameSubnet = false;
        String interfaceName = "";
        if (properties != null) {
            interfaceName = properties.getInterfaceName() == null ? "" : properties.getInterfaceName();
            item.put("interface", interfaceName);
            item.put("link_addresses", linkAddressesJson(properties, groupOwnerAddress));
            JSONArray routes = new JSONArray();
            for (RouteInfo route : properties.getRoutes()) {
                boolean matches = false;
                try {
                    matches = groupOwnerAddress != null && route.matches(groupOwnerAddress);
                } catch (Exception ignored) {
                }
                if (matches) {
                    routeMatches = true;
                }
                JSONObject routeJson = new JSONObject();
                routeJson.put("route", route.toString());
                routeJson.put("matches_group_owner", matches);
                routes.put(routeJson);
            }
            item.put("routes", routes);
            for (android.net.LinkAddress address : properties.getLinkAddresses()) {
                InetAddress inetAddress = address.getAddress();
                if (Qcl041WifiDirectNetworkBinder.sameIpv4Slash24(inetAddress, groupOwnerAddress)) {
                    addressSameSubnet = true;
                    break;
                }
            }
        } else {
            item.put("link_properties_found", false);
        }
        if (capabilities != null) {
            item.put("capabilities", capabilities.toString());
            item.put("has_transport_wifi", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            item.put("has_capability_internet", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            item.put("has_capability_validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            item.put("has_capability_not_metered", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
            item.put("has_capability_wifi_p2p", hasCapabilityByName(capabilities, "NET_CAPABILITY_WIFI_P2P"));
            item.put("has_capability_local_network", hasCapabilityByName(capabilities, "NET_CAPABILITY_LOCAL_NETWORK"));
        } else {
            item.put("network_capabilities_found", false);
        }
        boolean p2pInterface = interfaceName.toLowerCase(Locale.US).contains("p2p");
        boolean wifiP2pCapability = hasCapabilityByName(capabilities, "NET_CAPABILITY_WIFI_P2P");
        boolean localNetworkCapability = hasCapabilityByName(capabilities, "NET_CAPABILITY_LOCAL_NETWORK");
        item.put("p2p_interface", p2pInterface);
        item.put("route_matches_group_owner", routeMatches);
        item.put("address_same_subnet_as_group_owner", addressSameSubnet);
        item.put(
                "p2p_candidate",
                p2pInterface
                        || wifiP2pCapability
                        || localNetworkCapability
                        || routeMatches
                        || addressSameSubnet);
        return item;
    }

    private JSONArray linkAddressesJson(LinkProperties properties, InetAddress groupOwnerAddress)
            throws JSONException {
        JSONArray addresses = new JSONArray();
        for (android.net.LinkAddress address : properties.getLinkAddresses()) {
            InetAddress inetAddress = address.getAddress();
            JSONObject item = new JSONObject();
            item.put("address", address.toString());
            item.put("host_address", addressText(inetAddress));
            item.put(
                    "same_subnet_as_group_owner",
                    Qcl041WifiDirectNetworkBinder.sameIpv4Slash24(inetAddress, groupOwnerAddress));
            addresses.put(item);
        }
        return addresses;
    }

    private void addNetworkInterfaces(JSONObject snapshot, InetAddress groupOwnerAddress)
            throws JSONException {
        JSONArray interfaces = new JSONArray();
        int count = 0;
        int p2pCount = 0;
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                JSONObject item = new JSONObject();
                String name = networkInterface.getName();
                boolean p2pInterface = name != null && name.toLowerCase(Locale.US).contains("p2p");
                if (p2pInterface) {
                    p2pCount++;
                }
                item.put("name", name == null ? "" : name);
                item.put("display_name", networkInterface.getDisplayName());
                item.put("is_up", safeIsUp(networkInterface));
                item.put("is_loopback", safeIsLoopback(networkInterface));
                item.put("is_virtual", networkInterface.isVirtual());
                item.put("p2p_interface", p2pInterface);
                JSONArray addresses = new JSONArray();
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    JSONObject addressJson = new JSONObject();
                    addressJson.put("address", addressText(address));
                    addressJson.put("ipv4", address instanceof Inet4Address);
                    addressJson.put("loopback", address.isLoopbackAddress());
                    addressJson.put(
                            "same_subnet_as_group_owner",
                            Qcl041WifiDirectNetworkBinder.sameIpv4Slash24(address, groupOwnerAddress));
                    addresses.put(addressJson);
                }
                item.put("addresses", addresses);
                interfaces.put(item);
                count++;
            }
        } catch (Exception ex) {
            snapshot.put("network_interface_error", errorText(ex));
        }
        snapshot.put("network_interface_count", count);
        snapshot.put("network_interface_p2p_count", p2pCount);
        snapshot.put("network_interfaces", interfaces);
    }

    private void noteCallback(String scope, String callbackName, Network network) {
        noteCallback(scope, callbackName, network, false, false);
    }

    private void noteCallback(
            String scope,
            String callbackName,
            Network network,
            boolean requestNetworkCallback) {
        noteCallback(scope, callbackName, network, requestNetworkCallback, false);
    }

    private void noteCallback(
            String scope,
            String callbackName,
            Network network,
            boolean requestNetworkCallback,
            boolean includeOtherUidCallback) {
        synchronized (callbackLock) {
            if (includeOtherUidCallback) {
                includeOtherUidCallbackEventCount++;
                if ("onAvailable".equals(callbackName)) {
                    includeOtherUidOnAvailableCount++;
                }
            } else if (requestNetworkCallback) {
                requestNetworkCallbackEventCount++;
            } else {
                callbackEventCount++;
            }
            JSONObject event = new JSONObject();
            try {
                event.put(
                        "index",
                        includeOtherUidCallback
                                ? includeOtherUidCallbackEventCount
                                : requestNetworkCallback ? requestNetworkCallbackEventCount : callbackEventCount);
                event.put("scope", scope);
                event.put("callback", callbackName);
                event.put(
                        "callback_registration",
                        includeOtherUidCallback
                                ? "registerNetworkCallback(includeOtherUid)"
                                : requestNetworkCallback ? "requestNetwork" : "registerNetworkCallback");
                event.put("elapsed_ms", SystemClock.elapsedRealtime() - startedMs);
                event.put("network", networkText(network));
                if (network != null) {
                    event.put("network_handle", network.getNetworkHandle());
                    if (connectivityManager != null) {
                        LinkProperties properties = connectivityManager.getLinkProperties(network);
                        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
                        event.put(
                                "interface",
                                properties == null || properties.getInterfaceName() == null
                                        ? ""
                                        : properties.getInterfaceName());
                        event.put("capabilities", capabilities == null ? "" : capabilities.toString());
                    }
                }
                cacheCallbackNetworkLocked(
                        callbackName,
                        network,
                        requestNetworkCallback,
                        includeOtherUidCallback);
                if (includeOtherUidCallback) {
                    event.put("cached_network_count", includeOtherUidCallbackNetworks.size());
                    includeOtherUidCallbackEvents.put(event);
                    artifact.diagnostic(
                            SECTION,
                            "include_other_uid_callback_event_count",
                            includeOtherUidCallbackEventCount);
                    artifact.diagnostic(
                            SECTION,
                            "include_other_uid_on_available_count",
                            includeOtherUidOnAvailableCount);
                    artifact.diagnostic(SECTION, "last_include_other_uid_callback_event", event);
                    artifact.diagnostic(
                            SECTION,
                            "include_other_uid_callback_cached_network_count",
                            includeOtherUidCallbackNetworks.size());
                    artifact.diagnostic(
                            SECTION,
                            "include_other_uid_wifi_direct_candidate_seen",
                            anyCachedCallbackCandidateLocked(null, false, true));
                    recordIncludeOtherUidCandidateDiagnosticsLocked(null);
                } else if (requestNetworkCallback) {
                    event.put("cached_network_count", requestNetworkCallbackNetworks.size());
                    requestNetworkCallbackEvents.put(event);
                    artifact.diagnostic(
                            SECTION,
                            "request_network_callback_event_count",
                            requestNetworkCallbackEventCount);
                    artifact.diagnostic(SECTION, "last_request_network_callback_event", event);
                    artifact.diagnostic(
                            SECTION,
                            "request_network_callback_cached_network_count",
                            requestNetworkCallbackNetworks.size());
                    artifact.diagnostic(
                            SECTION,
                            "request_network_wifi_direct_candidate_seen",
                            anyCachedCallbackCandidateLocked(null, true));
                } else {
                    event.put("cached_network_count", callbackNetworks.size());
                    callbackEvents.put(event);
                    artifact.diagnostic(SECTION, "callback_event_count", callbackEventCount);
                    artifact.diagnostic(SECTION, "last_callback_event", event);
                    artifact.diagnostic(SECTION, "callback_cached_network_count", callbackNetworks.size());
                    artifact.diagnostic(
                            SECTION,
                            "callback_wifi_direct_candidate_seen",
                            anyCachedCallbackCandidateLocked(null, false));
                }
            } catch (Exception ex) {
                artifact.diagnostic(
                        SECTION,
                        includeOtherUidCallback
                                ? "include_other_uid_callback_event_error"
                                : requestNetworkCallback
                                ? "request_network_callback_event_error"
                                : "callback_event_error",
                        errorText(ex));
            }
        }
        artifact.writeQuietly();
    }

    private void cacheCallbackNetworkLocked(String callbackName, Network network) {
        cacheCallbackNetworkLocked(callbackName, network, false, false);
    }

    private void cacheCallbackNetworkLocked(
            String callbackName,
            Network network,
            boolean requestNetworkCallback) {
        cacheCallbackNetworkLocked(callbackName, network, requestNetworkCallback, false);
    }

    private void cacheCallbackNetworkLocked(
            String callbackName,
            Network network,
            boolean requestNetworkCallback,
            boolean includeOtherUidCallback) {
        if (network == null) {
            return;
        }
        List<Network> cache = includeOtherUidCallback
                ? includeOtherUidCallbackNetworks
                : requestNetworkCallback ? requestNetworkCallbackNetworks : callbackNetworks;
        if ("onLost".equals(callbackName)) {
            removeCallbackNetworkLocked(network, cache);
            return;
        }
        for (Network cached : cache) {
            if (cached != null && cached.getNetworkHandle() == network.getNetworkHandle()) {
                return;
            }
        }
        cache.add(network);
    }

    private void removeCallbackNetworkLocked(Network network) {
        removeCallbackNetworkLocked(network, callbackNetworks);
    }

    private void removeCallbackNetworkLocked(Network network, List<Network> cache) {
        for (int index = cache.size() - 1; index >= 0; index--) {
            Network cached = cache.get(index);
            if (cached != null && cached.getNetworkHandle() == network.getNetworkHandle()) {
                cache.remove(index);
            }
        }
    }

    private boolean anyCachedCallbackCandidateLocked(InetAddress groupOwnerAddress) {
        return anyCachedCallbackCandidateLocked(groupOwnerAddress, false);
    }

    private boolean anyCachedCallbackCandidateLocked(
            InetAddress groupOwnerAddress,
            boolean requestNetworkCallback) {
        return anyCachedCallbackCandidateLocked(groupOwnerAddress, requestNetworkCallback, false);
    }

    private boolean anyCachedCallbackCandidateLocked(
            InetAddress groupOwnerAddress,
            boolean requestNetworkCallback,
            boolean includeOtherUidCallback) {
        List<Network> networks = includeOtherUidCallback
                ? includeOtherUidCallbackNetworks
                : requestNetworkCallback ? requestNetworkCallbackNetworks : callbackNetworks;
        for (Network network : networks) {
            if (inspectCallbackCandidate(network, groupOwnerAddress).accepted) {
                return true;
            }
        }
        return false;
    }

    private void recordIncludeOtherUidCandidateDiagnosticsLocked(InetAddress groupOwnerAddress) {
        Network selectedNetwork = null;
        CallbackCandidate selectedCandidate = null;
        boolean candidateSeen = false;
        for (int index = includeOtherUidCallbackNetworks.size() - 1; index >= 0; index--) {
            Network network = includeOtherUidCallbackNetworks.get(index);
            CallbackCandidate candidate = inspectCallbackCandidate(network, groupOwnerAddress);
            if (candidate.accepted) {
                candidateSeen = true;
                selectedNetwork = network;
                selectedCandidate = candidate;
                break;
            }
            if (selectedNetwork == null) {
                selectedNetwork = network;
                selectedCandidate = candidate;
            }
        }
        artifact.diagnostic(SECTION, "include_other_uid_wifi_direct_candidate_seen", candidateSeen);
        if (selectedNetwork == null || selectedCandidate == null) {
            artifact.diagnostic(SECTION, "include_other_uid_network_handle", "");
            artifact.diagnostic(SECTION, "include_other_uid_link_properties_present", false);
            artifact.diagnostic(SECTION, "include_other_uid_interface", "");
            artifact.diagnostic(SECTION, "include_other_uid_has_wifi_p2p", false);
            artifact.diagnostic(SECTION, "include_other_uid_has_local_network", false);
            artifact.diagnostic(SECTION, "include_other_uid_bind_socket_attempted", false);
            artifact.diagnostic(SECTION, "include_other_uid_bind_socket_result", "not_attempted");
            return;
        }
        artifact.diagnostic(SECTION, "include_other_uid_network", networkText(selectedNetwork));
        artifact.diagnostic(SECTION, "include_other_uid_network_handle", selectedNetwork.getNetworkHandle());
        artifact.diagnostic(
                SECTION,
                "include_other_uid_link_properties_present",
                selectedCandidate.linkPropertiesFound);
        artifact.diagnostic(SECTION, "include_other_uid_interface", selectedCandidate.interfaceName);
        artifact.diagnostic(SECTION, "include_other_uid_has_wifi_p2p", selectedCandidate.wifiP2pCapability);
        artifact.diagnostic(
                SECTION,
                "include_other_uid_has_local_network",
                selectedCandidate.localNetworkCapability);
        artifact.diagnostic(SECTION, "include_other_uid_bind_socket_attempted", true);
        artifact.diagnostic(
                SECTION,
                "include_other_uid_bind_socket_result",
                attemptDiagnosticBindSocket(selectedNetwork));
    }

    private String attemptDiagnosticBindSocket(Network network) {
        if (network == null) {
            return "network_missing";
        }
        Socket socket = new Socket();
        try {
            network.bindSocket(socket);
            return "pass";
        } catch (Throwable ex) {
            return errorText(ex);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private CallbackCandidate inspectCallbackCandidate(Network network, InetAddress groupOwnerAddress) {
        CallbackCandidate candidate = new CallbackCandidate();
        if (network == null || connectivityManager == null) {
            return candidate;
        }
        LinkProperties properties = null;
        NetworkCapabilities capabilities = null;
        try {
            properties = connectivityManager.getLinkProperties(network);
        } catch (Exception ignored) {
        }
        try {
            capabilities = connectivityManager.getNetworkCapabilities(network);
        } catch (Exception ignored) {
        }
        candidate.linkPropertiesFound = properties != null;
        if (properties != null) {
            candidate.interfaceName = properties.getInterfaceName() == null ? "" : properties.getInterfaceName();
            candidate.p2pInterface = candidate.interfaceName.toLowerCase(Locale.US).contains("p2p");
            for (RouteInfo route : properties.getRoutes()) {
                try {
                    if (groupOwnerAddress != null && route.matches(groupOwnerAddress)) {
                        candidate.routeMatchesGroupOwner = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            for (android.net.LinkAddress address : properties.getLinkAddresses()) {
                if (Qcl041WifiDirectNetworkBinder.sameIpv4Slash24(address.getAddress(), groupOwnerAddress)) {
                    candidate.addressSameSubnet = true;
                    break;
                }
            }
        }
        if (capabilities != null) {
            candidate.wifiTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            candidate.wifiP2pCapability = hasCapabilityByName(capabilities, "NET_CAPABILITY_WIFI_P2P");
            candidate.localNetworkCapability = hasCapabilityByName(capabilities, "NET_CAPABILITY_LOCAL_NETWORK");
        }
        candidate.accepted =
                candidate.p2pInterface
                        || candidate.wifiP2pCapability
                        || candidate.localNetworkCapability
                        || candidate.routeMatchesGroupOwner
                        || candidate.addressSameSubnet;
        return candidate;
    }

    private static final class CallbackCandidate {
        String interfaceName = "";
        boolean linkPropertiesFound;
        boolean p2pInterface;
        boolean routeMatchesGroupOwner;
        boolean addressSameSubnet;
        boolean wifiTransport;
        boolean wifiP2pCapability;
        boolean localNetworkCapability;
        boolean accepted;
    }

    private final class TraceNetworkCallback extends ConnectivityManager.NetworkCallback {
        private final String scope;
        private final boolean requestNetworkCallback;
        private final boolean includeOtherUidCallback;

        TraceNetworkCallback(String scope) {
            this(scope, false);
        }

        TraceNetworkCallback(String scope, boolean requestNetworkCallback) {
            this(scope, requestNetworkCallback, false);
        }

        TraceNetworkCallback(String scope, boolean requestNetworkCallback, boolean includeOtherUidCallback) {
            this.scope = scope;
            this.requestNetworkCallback = requestNetworkCallback;
            this.includeOtherUidCallback = includeOtherUidCallback;
        }

        @Override
        public void onAvailable(Network network) {
            noteCallback(scope, "onAvailable", network, requestNetworkCallback, includeOtherUidCallback);
        }

        @Override
        public void onLost(Network network) {
            noteCallback(scope, "onLost", network, requestNetworkCallback, includeOtherUidCallback);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            noteCallback(
                    scope,
                    "onCapabilitiesChanged",
                    network,
                    requestNetworkCallback,
                    includeOtherUidCallback);
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
            noteCallback(
                    scope,
                    "onLinkPropertiesChanged",
                    network,
                    requestNetworkCallback,
                    includeOtherUidCallback);
        }

        @Override
        public void onUnavailable() {
            noteCallback(scope, "onUnavailable", null, requestNetworkCallback, includeOtherUidCallback);
        }
    }

    private static boolean safeIsUp(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean safeIsLoopback(NetworkInterface networkInterface) {
        try {
            return networkInterface.isLoopback();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int capabilityByName(String fieldName) {
        try {
            return NetworkCapabilities.class.getField(fieldName).getInt(null);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static boolean hasCapabilityByName(NetworkCapabilities capabilities, String fieldName) {
        int capability = capabilityByName(fieldName);
        return capabilities != null && capability >= 0 && capabilities.hasCapability(capability);
    }

    private static String sanitizePhase(String phase) {
        String text = phase == null ? "" : phase.trim().toLowerCase(Locale.US);
        if (text.isEmpty()) {
            return "unknown";
        }
        return text.replaceAll("[^a-z0-9_]+", "_");
    }

    private static String networkText(Network network) {
        return network == null ? "" : network.toString();
    }

    private static String addressText(InetAddress address) {
        return address == null ? "" : address.getHostAddress();
    }

    private static String errorText(Throwable ex) {
        return ex == null ? "" : ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }
}
