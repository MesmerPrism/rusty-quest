package io.github.mesmerprism.rustyquest.broker_client;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import org.json.JSONObject;

/** Thin signature-scoped Binder adapter shared by independent Quest apps. */
public final class BrokerClientProbeActivity extends Activity {
    private static final String TAG = "RustyBrokerClient";
    private static final String MARKER = "RUSTY_QUEST_BROKER_CLIENT";
    private static final int ISSUE_TOKEN = 1;
    private static final int AUTHORIZE_USE = 2;
    private static final int REVOKE_TOKEN = 3;
    private final Messenger replyMessenger = new Messenger(new ReplyHandler(Looper.getMainLooper()));
    private Messenger service;
    private boolean bound;
    private int stage;
    private long initialRevision;
    private String tokenId;
    private String clientId;
    private String featureLockId;
    private String markerNamespace;
    private String capabilities;
    private String contractFamilies;
    private boolean issueApplied;
    private boolean useApplied;
    private boolean revokeApplied;
    private String replayReason = "missing";
    private String postRevokeReason = "missing";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(
                    getPackageName(), android.content.pm.PackageManager.GET_META_DATA);
            Bundle metadata = info.metaData;
            clientId = required(metadata, "rusty.manifold.client_id");
            featureLockId = required(metadata, "rusty.manifold.feature_lock_id");
            markerNamespace = required(metadata, "rusty.manifold.marker_namespace");
            capabilities = required(metadata, "rusty.manifold.capabilities");
            contractFamilies = required(metadata, "rusty.manifold.contract_families");
            initialRevision = getIntent().getLongExtra("expected_authority_revision", 0L);
            if (initialRevision <= 0L) throw new IllegalArgumentException("missing authority revision");
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(
                    "io.github.mesmerprism.rustymanifold.broker",
                    "io.github.mesmerprism.rustymanifold.broker.ManifoldAdmissionService"));
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) marker("status=rejected reason=bind-returned-false");
        } catch (Exception error) {
            marker("status=rejected reason=" + error.getClass().getSimpleName());
            finish();
        }
    }

    private static String required(Bundle metadata, String key) {
        if (metadata == null) throw new IllegalArgumentException("missing metadata");
        String value = metadata.getString(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            sendIssue(initialRevision);
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    private final class ReplyHandler extends Handler {
        ReplyHandler(Looper looper) { super(looper); }

        @Override public void handleMessage(Message message) {
            try {
                String error = message.getData().getString("error", "");
                if (!error.isEmpty()) throw new IllegalStateException(error);
                JSONObject response = new JSONObject(message.getData().getString("response_json", "{}"));
                JSONObject receipt = response.getJSONObject("receipt");
                long revision = receipt.getLong("resulting_authority_revision");
                if (stage == 0) {
                    issueApplied = receipt.optBoolean("applied", false);
                    tokenId = receipt.getJSONObject("token").getString("token_id");
                    stage = 1;
                    sendUse(requestId("use"), revision);
                } else if (stage == 1) {
                    useApplied = receipt.optBoolean("applied", false);
                    stage = 2;
                    sendUse(requestId("use"), revision);
                } else if (stage == 2) {
                    replayReason = receipt.optString("rejection_reason", "missing");
                    stage = 3;
                    sendRevoke(revision);
                } else if (stage == 3) {
                    revokeApplied = receipt.optBoolean("applied", false);
                    stage = 4;
                    sendUse(requestId("after-revoke"), revision);
                } else if (stage == 4) {
                    postRevokeReason = receipt.optString("rejection_reason", "missing");
                    boolean accepted = issueApplied && useApplied
                            && "replayed_request".equals(replayReason)
                            && revokeApplied && "token_revoked".equals(postRevokeReason);
                    marker("status=" + (accepted ? "accepted" : "rejected")
                            + " contractFamilies=" + contractFamilies
                            + " issueApplied=" + issueApplied + " useApplied=" + useApplied
                            + " replayReason=" + replayReason + " revokeApplied=" + revokeApplied
                            + " postRevokeReason=" + postRevokeReason
                            + " localPolicy=false cleanupRequested=true");
                    cleanupAndFinish();
                }
            } catch (Exception error) {
                marker("status=rejected stage=" + stage + " reason=" + error.getClass().getSimpleName());
                cleanupAndFinish();
            }
        }
    }

    private String requestId(String suffix) {
        return "request." + clientId.replace('-', '_') + "." + suffix;
    }

    private void sendIssue(long revision) {
        Bundle data = new Bundle();
        data.putString("request_id", requestId("issue"));
        data.putLong("expected_authority_revision", revision);
        data.putString("capabilities", capabilities);
        data.putLong("token_ttl_ms", 30_000L);
        send(ISSUE_TOKEN, data);
    }

    private void sendUse(String requestId, long revision) {
        Bundle data = new Bundle();
        data.putString("request_id", requestId);
        data.putLong("expected_authority_revision", revision);
        data.putString("token_id", tokenId);
        data.putString("capability_id", "capability.media.session.observe");
        send(AUTHORIZE_USE, data);
    }

    private void sendRevoke(long revision) {
        Bundle data = new Bundle();
        data.putString("request_id", requestId("revoke"));
        data.putLong("expected_authority_revision", revision);
        data.putString("token_id", tokenId);
        data.putString("reason", "reason.client.completed");
        send(REVOKE_TOKEN, data);
    }

    private void send(int what, Bundle data) {
        try {
            Message message = Message.obtain(null, what);
            message.setData(data);
            message.replyTo = replyMessenger;
            service.send(message);
        } catch (Exception error) {
            marker("status=rejected stage=send reason=" + error.getClass().getSimpleName());
            cleanupAndFinish();
        }
    }

    private void marker(String fields) {
        Log.i(TAG, MARKER + " clientId=" + clientId + " package=" + getPackageName()
                + " featureLockId=" + featureLockId + " markerNamespace=" + markerNamespace
                + " " + fields);
    }

    private void cleanupAndFinish() {
        if (bound) {
            try { unbindService(connection); } catch (Exception ignored) {}
            bound = false;
        }
        finish();
    }

    @Override protected void onDestroy() {
        if (bound) {
            try { unbindService(connection); } catch (Exception ignored) {}
            bound = false;
        }
        super.onDestroy();
    }
}
