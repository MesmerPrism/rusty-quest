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
    private final Map<String, RetainedHubProviderAdmission> retainedHubProviderAdmissions =
            new HashMap<>();
    private final Map<String, ActiveHubProvider> activeHubProviders = new HashMap<>();
    private final Messenger messenger = new Messenger(new AdmissionHandler(Looper.getMainLooper()));
    private volatile String brokerEpochId = "provider.epoch.unavailable";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            JSONObject status = ManifoldRuntimeAuthorityBridge.initialize();
            brokerEpochId = status.optString("provider_epoch_id", brokerEpochId);
            // Establish the Hub authority and restore (or fail-closed migrate)
            // its durable envelope before this Binder can issue any provider
            // token. Otherwise the first provider authorization after a cold
            // start becomes the authority's admission floor and cannot prove
            // itself strictly newer during registration.
            ConnectionHubProcess.get(this);
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
            Bundle data = message.getData();
            String correlationId = data.getString("correlation_id", "correlation.missing");
            long sessionGeneration = data.getLong("session_generation", 0L);
            progress("broker_dequeue", message.sendingUid, "unresolved", correlationId,
                    sessionGeneration);
            try {
                QuestCaller caller = callerForUid(message.sendingUid);
                progress("identity_derived", message.sendingUid, caller.packageName, correlationId,
                        sessionGeneration);
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
                progress("authority_returned", message.sendingUid, caller.packageName,
                        correlationId, sessionGeneration);
                reply(message, response.toString(), null, correlationId, sessionGeneration,
                        "authority_returned");
                progress("reply_enqueued", message.sendingUid, caller.packageName, correlationId,
                        sessionGeneration);
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
                reply(message, null, error.getClass().getSimpleName(), correlationId,
                        sessionGeneration, "broker_rejected");
            }
        }
    }

    private JSONObject registerSurface(Message message, QuestCaller caller, Bundle data)
            throws Exception {
        if (message.replyTo == null) {
            throw new SecurityException("provider callback Messenger is required");
        }
        final HubProviderIdentity identity = caller.toHubIdentity();
        JSONObject registration = new JSONObject(data.getString("surface_registration_json", "{}"));
        final String registrationId = requireProviderInstanceId(
                data.getString("registration_id", ""));
        final String registrationFingerprint = requireSha256(
                data.getString("registration_fingerprint_sha256", ""));
        final String computedFingerprint = "sha256:" + sha256Hex(registration.toString());
        if (!registrationFingerprint.equals(computedFingerprint)) {
            throw new SecurityException("surface registration fingerprint mismatch");
        }
        final IBinder callbackBinder = message.replyTo.getBinder();
        synchronized (activeHubProviders) {
            ActiveHubProvider existing = activeHubProviders.get(identity.stableKey());
            if (existing != null) {
                if (existing.registrationId.equals(registrationId)
                        && existing.registrationFingerprint.equals(registrationFingerprint)
                        && existing.surfaceId.equals(registration.getString("surface_id"))
                        && existing.sessionGeneration
                                == data.getLong("session_generation", 0L)
                        && existing.callbackBinder.equals(callbackBinder)) {
                    return new JSONObject(existing.appliedReceiptJson)
                            .put("status", "surface_registration_equivalent")
                            .put("equivalent", true);
                }
                throw new SecurityException("conflicting active surface registration");
            }
        }
        final String providerInstanceId = "provider.instance." + randomHex(16);
        RetainedHubProviderAdmission admission;
        synchronized (retainedHubProviderAdmissions) {
            admission = retainedHubProviderAdmissions.remove(identity.stableKey());
        }
        if (admission == null) {
            throw new SecurityException("current Manifold provider admission use is required");
        }
        if (admission.sessionGeneration != data.getLong("session_generation", 0L)) {
            throw new SecurityException("provider admission session generation mismatch");
        }
        if (!admission.correlationId.equals(
                data.getString("authorization_correlation_id", ""))) {
            throw new SecurityException("provider authorization correlation mismatch");
        }
        final AtomicBoolean registrationCommitted = new AtomicBoolean();
        final AtomicBoolean deathObserved = new AtomicBoolean();
        final ActiveHubProvider[] activeHolder = new ActiveHubProvider[1];
        final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
            @Override public void binderDied() {
                deathObserved.set(true);
                if (registrationCommitted.compareAndSet(true, false)) {
                    ConnectionHubProcess.get(ConnectionHubAdmissionService.this).unregisterProvider(
                            identity,
                            providerInstanceId,
                            "provider_binder_died");
                    synchronized (activeHubProviders) {
                        activeHubProviders.remove(identity.stableKey(), activeHolder[0]);
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
                    admission.requestId,
                    registration,
                    message.replyTo);
        } catch (Exception failure) {
            callbackBinder.unlinkToDeath(deathRecipient, 0);
            throw failure;
        }
        if (receipt.applied && !deathObserved.get()) {
            String appliedReceiptJson = hubReceipt(receipt)
                    .put("registration_id", registrationId)
                    .put("registration_fingerprint_sha256", registrationFingerprint)
                    .put("equivalent", false)
                    .toString();
            ActiveHubProvider active = new ActiveHubProvider(
                    identity,
                    providerInstanceId,
                    registration.getString("surface_id"),
                    registrationId,
                    registrationFingerprint,
                    admission.sessionGeneration,
                    appliedReceiptJson,
                    callbackBinder,
                    deathRecipient,
                    registrationCommitted);
            activeHolder[0] = active;
            synchronized (activeHubProviders) {
                if (activeHubProviders.put(identity.stableKey(), active) != null) {
                    throw new IllegalStateException("provider registration ownership collision");
                }
                registrationCommitted.set(true);
            }
            if (deathObserved.get() && registrationCommitted.compareAndSet(true, false)) {
                ConnectionHubProcess.get(this).unregisterProvider(
                        identity, providerInstanceId, "provider_binder_died_during_registration");
                synchronized (activeHubProviders) {
                    activeHubProviders.remove(identity.stableKey(), active);
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
        return hubReceipt(receipt)
                .put("registration_id", registrationId)
                .put("registration_fingerprint_sha256", registrationFingerprint)
                .put("equivalent", false);
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
                    new RetainedHubProviderAdmission(
                            request.getString("request_id", ""),
                            request.getString("correlation_id", ""),
                            request.getLong("session_generation", 0L)));
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
        String surfaceId = data.getString("surface_id", "");
        ActiveHubProvider active;
        synchronized (activeHubProviders) {
            active = activeHubProviders.get(identity.stableKey());
            if (active == null || !active.surfaceId.equals(surfaceId)
                    || !active.registrationCommitted.compareAndSet(true, false)) {
                active = null;
            } else {
                activeHubProviders.remove(identity.stableKey(), active);
            }
        }
        boolean applied = active != null
                && ConnectionHubProcess.get(this).unregisterProvider(
                        identity,
                        active.providerInstanceId,
                        "provider_lifecycle_end") > 0;
        if (active != null) {
            active.callbackBinder.unlinkToDeath(active.deathRecipient, 0);
        }
        return new JSONObject()
                .put("$schema", "rusty.quest.connection_hub.provider_operation_receipt.v1")
                .put("applied", applied)
                .put("status", applied ? "surface_unregistered" : "surface_not_registered");
    }

    private static final class ActiveHubProvider {
        final HubProviderIdentity identity;
        final String providerInstanceId;
        final String surfaceId;
        final String registrationId;
        final String registrationFingerprint;
        final long sessionGeneration;
        final String appliedReceiptJson;
        final IBinder callbackBinder;
        final IBinder.DeathRecipient deathRecipient;
        final AtomicBoolean registrationCommitted;

        ActiveHubProvider(
                HubProviderIdentity identity,
                String providerInstanceId,
                String surfaceId,
                String registrationId,
                String registrationFingerprint,
                long sessionGeneration,
                String appliedReceiptJson,
                IBinder callbackBinder,
                IBinder.DeathRecipient deathRecipient,
                AtomicBoolean registrationCommitted) {
            this.identity = identity;
            this.providerInstanceId = providerInstanceId;
            this.surfaceId = surfaceId;
            this.registrationId = registrationId;
            this.registrationFingerprint = registrationFingerprint;
            this.sessionGeneration = sessionGeneration;
            this.appliedReceiptJson = appliedReceiptJson;
            this.callbackBinder = callbackBinder;
            this.deathRecipient = deathRecipient;
            this.registrationCommitted = registrationCommitted;
        }
    }

    private static final class RetainedHubProviderAdmission {
        final String requestId;
        final String correlationId;
        final long sessionGeneration;

        RetainedHubProviderAdmission(
                String requestId,
                String correlationId,
                long sessionGeneration) {
            this.requestId = requestId;
            this.correlationId = correlationId;
            this.sessionGeneration = sessionGeneration;
        }
    }

    private static String requireProviderInstanceId(String value) {
        return HubSurfaceDescriptor.requireToken(value, 96, "provider_instance_id");
    }

    private static String requireSha256(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("registration fingerprint must be SHA-256");
        }
        return normalized;
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

    private void reply(
            Message request,
            String responseJson,
            String error,
            String correlationId,
            long sessionGeneration,
            String brokerStage) {
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
            bundle.putString("correlation_id", correlationId);
            bundle.putLong("session_generation", sessionGeneration);
            bundle.putString("broker_epoch_id", brokerEpochId);
            bundle.putString("broker_stage", brokerStage);
            response.setData(bundle);
            request.replyTo.send(response);
        } catch (Exception ignored) {
            // The calling process may have exited; Manifold audit remains authoritative.
        }
    }

    private static String sha256Hex(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void progress(
            String stage,
            int uid,
            String packageName,
            String correlationId,
            long sessionGeneration) {
        Log.i(TAG, "status=progress stage=" + sanitizeMarker(stage)
                + " correlationId=" + sanitizeMarker(correlationId)
                + " sessionGeneration=" + sessionGeneration
                + " callerPackage=" + sanitizeMarker(packageName)
                + " sendingUid=" + uid);
    }

    private static String sanitizeMarker(String value) {
        if (value == null) return "missing";
        return value.replaceAll("[^A-Za-z0-9_.-]", "_").substring(
                0, Math.min(96, value.length()));
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
