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
import java.util.Arrays;

/** Signature-scoped Binder projection. Manifold owns every grant/token decision. */
public final class ManifoldAdmissionService extends Service {
    public static final int MESSAGE_ISSUE_TOKEN = 1;
    public static final int MESSAGE_AUTHORIZE_USE = 2;
    public static final int MESSAGE_REVOKE_TOKEN = 3;
    public static final String ADMISSION_PERMISSION =
            "io.github.mesmerprism.rustymanifold.permission.BROKER_ADMISSION";
    private static final String TAG = "RustyManifoldAdmission";
    private static final String OPERATION_SCHEMA = "rusty.quest.broker.admission_operation.v1";
    private final SecureRandom secureRandom = new SecureRandom();
    private final Messenger messenger = new Messenger(new AdmissionHandler(Looper.getMainLooper()));

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ManifoldAdmissionNativeBridge.initialize(GeneratedAdmissionConfig.JSON);
            Log.i(TAG, "status=initialized adapter=android_signature_scoped_binder decisionOwner=rusty.manifold.admission localTokenOrGrantPolicy=false");
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
                JSONObject operation = baseOperation(message.what, caller, data);
                JSONObject response = ManifoldAdmissionNativeBridge.execute(operation);
                reply(message, response.toString(), null);
                JSONObject receipt = response.getJSONObject("receipt");
                Log.i(TAG, "status=receipt operation=" + operation.getString("operation")
                        + " applied=" + receipt.optBoolean("applied", false)
                        + " rejection=" + receipt.optString("rejection_reason", "none")
                        + " callerPackage=" + caller.packageName
                        + " sendingUid=" + caller.uid);
            } catch (Exception error) {
                Log.e(TAG, "status=error stage=handle reason=" + error.getClass().getSimpleName());
                reply(message, null, error.getClass().getSimpleName());
            }
        }
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
            operation.put("expires_at_ms", now + 10_000L);
            byte[] entropy = new byte[32];
            secureRandom.nextBytes(entropy);
            operation.put("entropy_hex", hex(entropy));
        } else if (what == MESSAGE_AUTHORIZE_USE) {
            operation.put("operation", "authorize_use");
            operation.put("token_id", data.getString("token_id", "token.invalid"));
            operation.put("capability_id", data.getString("capability_id", "capability.invalid"));
            operation.put("issued_at_ms", now);
            operation.put("expires_at_ms", now + 10_000L);
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
        if (packages == null || packages.length == 0) {
            throw new SecurityException("Binder UID has no package");
        }
        Arrays.sort(packages);
        String packageName = packages[0];
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
    }
}
