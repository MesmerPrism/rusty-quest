package io.github.mesmerprism.rustymanifold.admission_client;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import org.json.JSONObject;

import java.util.UUID;

/** Device-test client for the signature-scoped admission Binder contract. */
public final class AdmissionClientActivity extends Activity {
    private static final String TAG = "RustyAdmissionClient";
    private static final String MARKER = "RUSTY_QUEST_BROKER_ADMISSION_CLIENT";
    private static final int ISSUE_TOKEN = 1;
    private static final int AUTHORIZE_USE = 2;
    private static final int REVOKE_TOKEN = 3;
    private static final String CAPABILITY = "capability.command.session.list";
    private final String requestNamespace = "request.device." + UUID.randomUUID();
    private final Messenger replyMessenger = new Messenger(new ReplyHandler(Looper.getMainLooper()));
    private Messenger service;
    private int stage;
    private String tokenId;
    private boolean issueApplied;
    private boolean useApplied;
    private boolean revokeApplied;
    private String replayReason = "missing";
    private String postRevokeReason = "missing";
    private long initialRevision = 1L;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        initialRevision = getIntent().getLongExtra("expected_authority_revision", 1L);
        String variant = getPackageName().endsWith("untrusted") ? "unauthorized" : "authorized";
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "io.github.mesmerprism.rustymanifold.broker",
                "io.github.mesmerprism.rustymanifold.broker.ManifoldAdmissionService"));
        try {
            boolean bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                marker("status=unauthorized-rejected variant=" + variant + " reason=bind-returned-false");
            } else {
                marker("status=bind-requested variant=" + variant);
            }
        } catch (SecurityException error) {
            marker("status=unauthorized-rejected variant=" + variant + " reason=signature-permission");
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            sendIssue(initialRevision);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final class ReplyHandler extends Handler {
        ReplyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            try {
                String error = message.getData().getString("error", "");
                if (!error.isEmpty()) {
                    marker("status=error stage=" + stage + " reason=" + error);
                    return;
                }
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
                    boolean accepted = issueApplied
                            && useApplied
                            && "replayed_request".equals(replayReason)
                            && revokeApplied
                            && "token_revoked".equals(postRevokeReason);
                    marker("status=" + (accepted ? "accepted" : "rejected")
                            + " variant=authorized"
                            + " issueApplied=" + issueApplied
                            + " useApplied=" + useApplied
                            + " replayReason=" + replayReason
                            + " revokeApplied=" + revokeApplied
                            + " postRevokeReason=" + postRevokeReason);
                }
            } catch (Exception error) {
                marker("status=error stage=" + stage + " reason=" + error.getClass().getSimpleName());
            }
        }
    }

    private void sendIssue(long revision) {
        Bundle data = new Bundle();
        data.putString("request_id", requestId("issue"));
        data.putLong("expected_authority_revision", revision);
        data.putString("capabilities", CAPABILITY);
        data.putLong("token_ttl_ms", 30_000L);
        send(ISSUE_TOKEN, data);
    }

    private void sendUse(String requestId, long revision) {
        Bundle data = new Bundle();
        data.putString("request_id", requestId);
        data.putLong("expected_authority_revision", revision);
        data.putString("token_id", tokenId);
        data.putString("capability_id", CAPABILITY);
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
            marker("status=error stage=send reason=" + error.getClass().getSimpleName());
        }
    }

    private String requestId(String suffix) {
        return requestNamespace + "." + suffix;
    }

    private static void marker(String fields) {
        Log.i(TAG, MARKER + " " + fields);
    }
}
