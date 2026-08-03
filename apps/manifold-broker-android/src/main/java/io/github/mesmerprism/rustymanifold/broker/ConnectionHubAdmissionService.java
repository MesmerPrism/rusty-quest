package io.github.mesmerprism.rustymanifold.broker;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Signature-scoped Binder projection. Manifold owns every grant/token decision. */
public final class ConnectionHubAdmissionService extends Service {
    private static final long ADMISSION_OPERATION_WINDOW_MS = 60_000L;
    public static final int MESSAGE_ISSUE_TOKEN = 1;
    public static final int MESSAGE_AUTHORIZE_USE = 2;
    public static final int MESSAGE_REVOKE_TOKEN = 3;
    public static final int MESSAGE_MUTATE_RUNTIME = 4;
    public static final int MESSAGE_COMPLETE_MEDIA_ACTION = 5;
    public static final int MESSAGE_RUNTIME_EVIDENCE = 6;
    public static final int MESSAGE_REGISTER_SURFACE = 20;
    public static final int MESSAGE_UPDATE_SURFACE_STATE = 21;
    public static final int MESSAGE_UNREGISTER_SURFACE = 22;
    /** Broker-to-provider callback; clients must never send this to the service. */
    public static final int MESSAGE_SURFACE_COMMAND = 23;
    public static final String ADMISSION_PERMISSION =
            "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION";
    private static final String TAG = "RustyManifoldAdmission";
    private static final String OPERATION_SCHEMA = "rusty.quest.broker.admission_operation.v1";
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, String> retainedHubProviderAdmissions = new HashMap<>();
    private final Map<String, String> activeHubProviderInstances = new HashMap<>();
    private final Messenger messenger = new Messenger(new AdmissionHandler(Looper.getMainLooper()));

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            JSONObject status = ManifoldRuntimeAuthorityBridge.initialize();
            Log.i(TAG, "status=initialized adapter=android_signature_scoped_binder"
                    + " providerEpoch=" + status.optString("provider_epoch_id", "missing")
                    + " existingAuthorityPreserved="
                    + status.optBoolean("existing_authority_preserved", false)
                    + " localTokenOrGrantPolicy=false");
        } catch (Exception error) {
            Log.e(TAG, "status=error stage=initialize reason=" + error.getClass().getSimpleName());
            stopSelf();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    private final class AdmissionHandler extends Handler {
        AdmissionHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            try {
                QuestCaller caller = callerForUid(message.sendingUid);
                Bundle data = message.getData();
                JSONObject response;
                JSONObject operation;
                if (message.what == MESSAGE_REGISTER_SURFACE) {
                    response = registerSurface(message, caller, data);
                    operation = new JSONObject().put("operation", "register_connection_hub_surface");
                } else if (message.what == MESSAGE_UPDATE_SURFACE_STATE) {
                    response = updateSurfaceState(caller, data);
                    operation = new JSONObject().put("operation", "update_connection_hub_surface_state");
                } else if (message.what == MESSAGE_UNREGISTER_SURFACE) {
                    response = unregisterSurface(caller, data);
                    operation = new JSONObject().put("operation", "unregister_connection_hub_surface");
                } else if (message.what == MESSAGE_SURFACE_COMMAND) {
                    throw new SecurityException("surface command callback cannot target admission service");
                } else if (message.what == MESSAGE_MUTATE_RUNTIME) {
                    operation = new JSONObject(data.getString("mutation_json", "{}"));
                    response = ManifoldRuntimeAuthorityBridge.evaluateMutation(operation);
                } else if (message.what == MESSAGE_COMPLETE_MEDIA_ACTION) {
                    operation = new JSONObject(data.getString("completion_json", "{}"));
                    response = ManifoldRuntimeAuthorityBridge.completeMediaAction(operation);
                } else if (message.what == MESSAGE_RUNTIME_EVIDENCE) {
                    operation = new JSONObject().put("operation", "runtime_evidence");
                    response = ManifoldRuntimeAuthorityBridge.evidence();
                } else {
                    operation = baseOperation(message.what, caller, data);
                    response = ManifoldAdmissionNativeBridge.execute(operation);
                    retainHubProviderAdmissionIfApplied(message.what, caller, data, response);
                }
                reply(message, response.toString(), null);
                JSONObject receipt = response.optJSONObject("receipt");
                JSONObject mutationReceipt = response.optJSONObject("mutation_receipt");
                Log.i(TAG, "status=receipt operation=" + operation.optString("operation", "runtime")
                        + " applied=" + (receipt != null
                                ? receipt.optBoolean("applied", false)
                                : response.optBoolean("accepted", false))
                        + " rejection=" + (receipt != null
                                ? receipt.optString("rejection_reason", "none")
                                : response.optString("status", "none"))
                        + " mutationReceipt=" + (mutationReceipt != null)
                        + " callerPackage=" + caller.packageName
                        + " sendingUid=" + caller.uid);
            } catch (Exception error) {
                Log.e(TAG, "status=error stage=handle reason=" + error.getClass().getSimpleName());
                reply(message, null, error.getClass().getSimpleName());
            }
        }
    }

    private JSONObject registerSurface(Message message, QuestCaller caller, Bundle data)
            throws Exception {
        if (message.replyTo == null) {
            throw new SecurityException("provider callback Messenger is required");
        }
        final HubProviderIdentity identity = caller.toHubIdentity();
        final String providerInstanceId;
        synchronized (activeHubProviderInstances) {
            String existing = activeHubProviderInstances.get(identity.stableKey());
            providerInstanceId = existing == null
                    ? "provider.instance." + randomHex(16)
                    : existing;
        }
        JSONObject registration = new JSONObject(data.getString("surface_registration_json", "{}"));
        String admittedEvidence;
        synchronized (retainedHubProviderAdmissions) {
            admittedEvidence = retainedHubProviderAdmissions.remove(identity.stableKey());
        }
        if (admittedEvidence == null) {
            throw new SecurityException("current Manifold provider admission use is required");
        }
        final IBinder callbackBinder = message.replyTo.getBinder();
        final AtomicBoolean registrationCommitted = new AtomicBoolean();
        final AtomicBoolean deathObserved = new AtomicBoolean();
        final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
            @Override public void binderDied() {
                deathObserved.set(true);
                if (registrationCommitted.compareAndSet(true, false)) {
                    ConnectionHubProcess.get(ConnectionHubAdmissionService.this).unregisterProvider(
                            identity,
                            providerInstanceId,
                            "provider_binder_died");
                    synchronized (activeHubProviderInstances) {
                        activeHubProviderInstances.remove(identity.stableKey());
                    }
                }
                callbackBinder.unlinkToDeath(this, 0);
            }
        };
        callbackBinder.linkToDeath(deathRecipient, 0);
        ConnectionHubAuthorityPort.Receipt receipt;
        try {
            receipt = ConnectionHubProcess.get(this).registerSurface(
                    identity,
                    providerInstanceId,
                    admittedEvidence,
                    registration,
                    message.replyTo);
        } catch (Exception failure) {
            callbackBinder.unlinkToDeath(deathRecipient, 0);
            throw failure;
        }
        if (receipt.applied && !deathObserved.get()) {
            synchronized (activeHubProviderInstances) {
                activeHubProviderInstances.put(identity.stableKey(), providerInstanceId);
            }
            registrationCommitted.set(true);
            if (deathObserved.get() && registrationCommitted.compareAndSet(true, false)) {
                ConnectionHubProcess.get(this).unregisterProvider(
                        identity, providerInstanceId, "provider_binder_died_during_registration");
                synchronized (activeHubProviderInstances) {
                    activeHubProviderInstances.remove(identity.stableKey());
                }
                receipt = ConnectionHubAuthorityPort.Receipt.rejected(
                        "provider_binder_died_during_registration");
            }
        } else {
            callbackBinder.unlinkToDeath(deathRecipient, 0);
            if (receipt.applied) {
                ConnectionHubProcess.get(this).unregisterProvider(
                        identity, providerInstanceId, "provider_binder_died_during_registration");
                receipt = ConnectionHubAuthorityPort.Receipt.rejected(
                        "provider_binder_died_during_registration");
            }
        }
        return hubReceipt(receipt);
    }

    private void retainHubProviderAdmissionIfApplied(
            int operation,
            QuestCaller caller,
            Bundle request,
            JSONObject response) {
        if (operation != MESSAGE_AUTHORIZE_USE
                || !"capability.connection_hub.provider.register".equals(
                        request.getString("capability_id", ""))) {
            return;
        }
        JSONObject receipt = response.optJSONObject("receipt");
        if (receipt == null || !receipt.optBoolean("applied", false)) {
            return;
        }
        HubProviderIdentity identity = caller.toHubIdentity();
        synchronized (retainedHubProviderAdmissions) {
            retainedHubProviderAdmissions.put(
                    identity.stableKey(),
                    request.getString("request_id", ""));
        }
    }

    private JSONObject updateSurfaceState(QuestCaller caller, Bundle data) throws Exception {
        HubProviderIdentity identity = caller.toHubIdentity();
        String surfaceId = data.getString("surface_id", "");
        JSONObject state = new JSONObject(data.getString("state_json", "{}"));
        ConnectionHubProcess.get(this).runtime().updateSurfaceState(identity, surfaceId, state);
        return new JSONObject()
                .put("$schema", "rusty.quest.connection_hub.provider_operation_receipt.v1")
                .put("applied", true)
                .put("status", "surface_state_updated");
    }

    private JSONObject unregisterSurface(QuestCaller caller, Bundle data) throws Exception {
        HubProviderIdentity identity = caller.toHubIdentity();
        boolean applied = ConnectionHubProcess.get(this).runtime().unregisterSurface(
                identity,
                data.getString("surface_id", ""),
                "provider_lifecycle_end");
        return new JSONObject()
                .put("$schema", "rusty.quest.connection_hub.provider_operation_receipt.v1")
                .put("applied", applied)
                .put("status", applied ? "surface_unregistered" : "surface_not_registered");
    }

    private static String requireProviderInstanceId(String value) {
        return HubSurfaceDescriptor.requireToken(value, 96, "provider_instance_id");
    }

    private String randomHex(int byteCount) {
        byte[] value = new byte[byteCount];
        secureRandom.nextBytes(value);
        String output = hex(value);
        java.util.Arrays.fill(value, (byte) 0);
        return output;
    }

    private static JSONObject hubReceipt(ConnectionHubAuthorityPort.Receipt receipt)
            throws Exception {
        return new JSONObject()
                .put("$schema", "rusty.quest.connection_hub.provider_operation_receipt.v1")
                .put("applied", receipt.applied)
                .put("status", receipt.status)
                .put("authority_receipt", new JSONObject(receipt.authorityReceiptJson));
    }

    private JSONObject baseOperation(int what, QuestCaller caller, Bundle data) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject operation = new JSONObject();
        operation.put("$schema", OPERATION_SCHEMA);
        operation.put("caller", caller.toJson());
        operation.put("request_id", data.getString("request_id", "request.invalid"));
        operation.put("expected_authority_revision", data.getLong("expected_authority_revision", 0));
        if (what == MESSAGE_ISSUE_TOKEN) {
            operation.put("operation", "issue_token");
            operation.put("requested_capabilities", csvArray(data.getString("capabilities", "")));
            operation.put("requested_token_ttl_ms", data.getLong("token_ttl_ms", 0));
            operation.put("issued_at_ms", now);
            operation.put("expires_at_ms", now + ADMISSION_OPERATION_WINDOW_MS);
            byte[] entropy = new byte[32];
            secureRandom.nextBytes(entropy);
            operation.put("entropy_hex", hex(entropy));
        } else if (what == MESSAGE_AUTHORIZE_USE) {
            operation.put("operation", "authorize_use");
            operation.put("token_id", data.getString("token_id", "token.invalid"));
            operation.put("capability_id", data.getString("capability_id", "capability.invalid"));
            operation.put("issued_at_ms", now);
            operation.put("expires_at_ms", now + ADMISSION_OPERATION_WINDOW_MS);
        } else if (what == MESSAGE_REVOKE_TOKEN) {
            operation.put("operation", "revoke_token");
            operation.put("token_id", data.getString("token_id", "token.invalid"));
            operation.put("reason", data.getString("reason", "reason.client.request"));
        } else {
            throw new IllegalArgumentException("unknown admission Binder operation");
        }
        return operation;
    }

    private QuestCaller callerForUid(int uid) throws Exception {
        PackageManager packageManager = getPackageManager();
        String[] packages = packageManager.getPackagesForUid(uid);
        String packageName = BinderCallerPackageResolver.requireUnambiguousPackage(packages);
        PackageInfo info = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES);
        Signature[] signatures = info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners()
                : new Signature[0];
        if (signatures.length != 1) {
            throw new SecurityException("exactly one APK signing certificate is required");
        }
        String fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
        return new QuestCaller(uid, packageName, fingerprint);
    }

    private static JSONArray csvArray(String value) {
        JSONArray output = new JSONArray();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                output.put(trimmed);
            }
        }
        return output;
    }

    private static String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) {
            builder.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        }
        return builder.toString();
    }

    private static void reply(Message request, String responseJson, String error) {
        if (request.replyTo == null) {
            return;
        }
        try {
            Message response = Message.obtain(null, request.what);
            Bundle bundle = new Bundle();
            if (responseJson != null) {
                bundle.putString("response_json", responseJson);
            }
            if (error != null) {
                bundle.putString("error", error);
            }
            response.setData(bundle);
            request.replyTo.send(response);
        } catch (Exception ignored) {
            // The calling process may have exited; Manifold audit remains authoritative.
        }
    }

    private static final class QuestCaller {
        final int uid;
        final String packageName;
        final String fingerprint;

        QuestCaller(int uid, String packageName, String fingerprint) {
            this.uid = uid;
            this.packageName = packageName;
            this.fingerprint = fingerprint;
        }

        JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("sending_uid", uid);
            value.put("package_name", packageName);
            value.put("signing_certificate_sha256", fingerprint);
            return value;
        }

        HubProviderIdentity toHubIdentity() {
            return new HubProviderIdentity(uid, packageName, fingerprint);
        }
    }
}
