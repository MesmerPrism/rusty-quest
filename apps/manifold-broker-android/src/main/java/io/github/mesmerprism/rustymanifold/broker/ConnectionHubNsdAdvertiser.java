package io.github.mesmerprism.rustymanifold.broker;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.nio.charset.StandardCharsets;

/** Fixed non-secret DNS-SD projection for the explicitly enabled listener. */
public final class ConnectionHubNsdAdvertiser implements AutoCloseable {
    private final NsdManager manager;
    private NsdManager.RegistrationListener registration;

    public ConnectionHubNsdAdvertiser(Context context) {
        manager = (NsdManager) context.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
    }

    public synchronized void start(int port) {
        close();
        if (manager == null) { return; }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("Rusty Connection Hub");
        info.setServiceType("_rusty-hub._tcp.");
        info.setPort(port);
        info.setAttribute("protocol", ConnectionHubProtocol.PROTOCOL_SCHEMA);
        info.setAttribute("security", ConnectionHubProtocol.SECURITY_MODE);
        info.setAttribute("confidentiality", ConnectionHubProtocol.CONFIDENTIALITY);
        info.setAttribute("production", "false");
        registration = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {}
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
        };
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    }

    @Override
    public synchronized void close() {
        if (manager != null && registration != null) {
            try { manager.unregisterService(registration); } catch (Exception ignored) {}
            registration = null;
        }
    }
}
