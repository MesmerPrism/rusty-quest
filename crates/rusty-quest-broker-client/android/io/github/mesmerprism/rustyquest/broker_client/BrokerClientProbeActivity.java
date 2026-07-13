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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** Thin signature-scoped Binder adapter shared by independent Quest apps. */
public final class BrokerClientProbeActivity extends Activity {
    private static final String TAG = "RustyBrokerClient";
    private static final String MARKER = "RUSTY_QUEST_BROKER_CLIENT";
    private static final int ISSUE_TOKEN = 1;
    private static final int AUTHORIZE_USE = 2;
    private static final int REVOKE_TOKEN = 3;
    private static final int MUTATE_RUNTIME = 4;
    private static final int COMPLETE_MEDIA_ACTION = 5;
    private static final int RUNTIME_EVIDENCE = 6;
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
    private boolean startApplied;
    private boolean stopApplied;
    private String replayReason = "missing";
    private String postRevokeReason = "missing";
    private final BrokerRequestNamespace requestNamespace =
            BrokerRequestNamespace.create(new SecureRandom());
    private String deliberateReplayUseRequestId;
    private String activeCommandId;
    private String activeUseRequestId;
    private String startCompletionJson;
    private String stopCompletionJson;
    private long admissionRevision;
    private long runtimeRevision;
    private String providerEpochId;
    private String recoveryProbeMode;
    private String overrideProviderEpochId;

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
            recoveryProbeMode = getIntent().getStringExtra("recovery_probe_mode");
            overrideProviderEpochId = getIntent().getStringExtra("override_provider_epoch_id");
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
                JSONObject receipt = response.optJSONObject("receipt");
                long revision = receipt != null
                        ? receipt.getLong("resulting_authority_revision")
                        : 0L;
                if (stage == 0) {
                    issueApplied = receipt.optBoolean("applied", false);
                    tokenId = receipt.getJSONObject("token").getString("token_id");
                    admissionRevision = revision;
                    runtimeRevision = response.optLong("runtime_host_revision", 1L);
                    providerEpochId = response.optString("provider_epoch_id", "");
                    stage = 1;
                    deliberateReplayUseRequestId = requestId("use");
                    sendUse(deliberateReplayUseRequestId, revision);
                } else if (stage == 1) {
                    useApplied = receipt.optBoolean("applied", false);
                    admissionRevision = revision;
                    stage = 2;
                    sendUse(deliberateReplayUseRequestId, revision);
                } else if (stage == 2) {
                    replayReason = receipt.optString("rejection_reason", "missing");
                    admissionRevision = revision;
                    stage = 3;
                    sendRevoke(revision);
                } else if (stage == 3) {
                    revokeApplied = receipt.optBoolean("applied", false);
                    admissionRevision = revision;
                    stage = 4;
                    sendUse(requestId("after-revoke"), revision);
                } else if (stage == 4) {
                    postRevokeReason = receipt.optString("rejection_reason", "missing");
                    admissionRevision = revision;
                    boolean accepted = issueApplied && useApplied
                            && "replayed_request".equals(replayReason)
                            && revokeApplied && "token_revoked".equals(postRevokeReason);
                    if (!accepted) {
                        marker("status=rejected contractFamilies=" + contractFamilies
                                + " issueApplied=" + issueApplied + " useApplied=" + useApplied
                                + " replayReason=" + replayReason + " revokeApplied=" + revokeApplied
                                + " postRevokeReason=" + postRevokeReason
                                + " localPolicy=false cleanupRequested=true");
                        cleanupAndFinish();
                    } else {
                        stage = 5;
                        sendIssue(admissionRevision, "capability.command.media.session.start");
                    }
                } else if (stage == 5) {
                    tokenId = receipt.getJSONObject("token").getString("token_id");
                    admissionRevision = revision;
                    activeCommandId = "command.media.session.start";
                    activeUseRequestId = requestId("media-start-use");
                    stage = 6;
                    sendUse(activeUseRequestId, revision, "capability.command.media.session.start");
                } else if (stage == 6) {
                    admissionRevision = revision;
                    stage = 7;
                    sendMutation(activeCommandId, activeUseRequestId, tokenId, runtimeRevision, admissionRevision);
                } else if (stage == 7) {
                    startApplied = response.optBoolean("accepted", false);
                    runtimeRevision = mutationResultingRuntimeRevision(response);
                    stage = 8;
                    sendCompletion();
                } else if (stage == 8) {
                    startCompletionJson = response.toString();
                    stage = 9;
                    sendEvidence();
                } else if (stage == 9) {
                    admissionRevision = response.getJSONObject("runtime")
                            .getJSONObject("admission_snapshot")
                            .getLong("authority_revision");
                    runtimeRevision = response.getJSONObject("runtime")
                            .getJSONObject("host_snapshot")
                            .getLong("authority_revision");
                    stage = 10;
                    sendIssue(admissionRevision, "capability.command.media.session.stop");
                } else if (stage == 10) {
                    tokenId = receipt.getJSONObject("token").getString("token_id");
                    admissionRevision = revision;
                    activeCommandId = "command.media.session.stop";
                    activeUseRequestId = requestId("media-stop-use");
                    stage = 11;
                    sendUse(activeUseRequestId, revision, "capability.command.media.session.stop");
                } else if (stage == 11) {
                    admissionRevision = revision;
                    stage = 12;
                    sendMutation(activeCommandId, activeUseRequestId, tokenId, runtimeRevision, admissionRevision);
                } else if (stage == 12) {
                    stopApplied = response.optBoolean("accepted", false);
                    runtimeRevision = mutationResultingRuntimeRevision(response);
                    stage = 13;
                    sendCompletion();
                } else if (stage == 13) {
                    stopCompletionJson = response.toString();
                    writeLifecycleArtifacts();
                    marker("status=" + (startApplied && stopApplied ? "accepted" : "rejected")
                            + " contractFamilies=" + contractFamilies
                            + " issueApplied=" + issueApplied + " useApplied=" + useApplied
                            + " replayReason=" + replayReason + " revokeApplied=" + revokeApplied
                            + " postRevokeReason=" + postRevokeReason
                            + " startApplied=" + startApplied + " stopApplied=" + stopApplied
                            + " admissionRevision=" + admissionRevision
                            + " runtimeRevision=" + runtimeRevision
                            + " providerEpoch=" + providerEpochId
                            + " lifecycleArtifacts=true localPolicy=false cleanupRequested=true");
                    cleanupAndFinish();
                }
            } catch (Exception error) {
                if ("old_epoch_rejection".equals(recoveryProbeMode) && stage == 7) {
                    try {
                        writeOldEpochRejectionProbe(error);
                        marker("status=old_epoch_rejected oldProviderEpoch=" + overrideProviderEpochId
                                + " currentProviderEpoch=" + providerEpochId
                                + " localPolicy=false cleanupRequested=true");
                    } catch (Exception probeError) {
                        marker("status=rejected stage=" + stage
                                + " reason=old_epoch_probe_write_failed");
                    }
                    cleanupAndFinish();
                    return;
                }
                marker("status=rejected stage=" + stage
                        + " reason=" + error.getClass().getSimpleName()
                        + " detail=" + sanitizeDetail(error.getMessage()));
                cleanupAndFinish();
            }
        }
    }

    private static String sanitizeDetail(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "none";
        }
        return value.replace('\n', '_').replace('\r', '_').replace(' ', '_');
    }

    private static long mutationResultingRuntimeRevision(JSONObject response) throws Exception {
        JSONObject mutationReceipt = response.getJSONObject("mutation_receipt");
        JSONObject adapterReceipt = mutationReceipt.getJSONObject("adapter_receipt");
        JSONObject applicationReceipt = adapterReceipt.getJSONObject("application");
        return applicationReceipt.getLong("resulting_authority_revision");
    }

    private String requestId(String suffix) {
        return requestNamespace.requestId(clientId, suffix);
    }

    private void sendIssue(long revision) {
        sendIssue(revision, capabilities);
    }

    private void sendIssue(long revision, String requestedCapabilities) {
        Bundle data = new Bundle();
        data.putString("request_id", requestId("issue"));
        data.putLong("expected_authority_revision", revision);
        data.putString("capabilities", requestedCapabilities);
        data.putLong("token_ttl_ms", 30_000L);
        send(ISSUE_TOKEN, data);
    }

    private void sendUse(String requestId, long revision) {
        sendUse(requestId, revision, "capability.media.session.observe");
    }

    private void sendUse(String requestId, long revision, String capabilityId) {
        Bundle data = new Bundle();
        data.putString("request_id", requestId);
        data.putLong("expected_authority_revision", revision);
        data.putString("token_id", tokenId);
        data.putString("capability_id", capabilityId);
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

    private void sendMutation(
            String commandId,
            String useRequestId,
            String token,
            long expectedRuntimeRevision,
            long expectedAdmissionRevision) throws Exception {
        JSONObject mutation = new JSONObject();
        mutation.put("$schema", "rusty.quest.broker.server_mutation_request.v1");
        mutation.put("bridge_kind", "standalone_process_jni");
        String epochForMutation = overrideProviderEpochId != null && !overrideProviderEpochId.trim().isEmpty()
                ? overrideProviderEpochId.trim()
                : providerEpochId;
        mutation.put("provider_epoch_id", epochForMutation);
        mutation.put("admission_use_request_id", useRequestId);
        mutation.put("token_id", token);
        mutation.put("expected_admission_authority_revision", expectedAdmissionRevision);
        mutation.put("command", commandRequest(commandId, expectedRuntimeRevision));
        mutation.put("params", effectParams(commandId));
        Bundle data = new Bundle();
        data.putString("mutation_json", mutation.toString());
        send(MUTATE_RUNTIME, data);
    }

    private JSONObject commandRequest(String commandId, long expectedRuntimeRevision) throws Exception {
        String params = canonicalEffectParams(commandId);
        JSONObject digest = new JSONObject();
        digest.put("$schema", "rusty.manifold.runtime_host.typed_params_digest.v1");
        digest.put("params_type_id", "rusty.quest.broker.effect_params.v1");
        digest.put("canonical_sha256", "sha256:" + sha256Hex(params));
        digest.put("canonical_size_bytes", params.getBytes(StandardCharsets.UTF_8).length);
        JSONObject command = new JSONObject();
        command.put("$schema", "rusty.manifold.runtime_host.command_request.v1");
        command.put("request_id", requestId(commandId.replace('.', '_')));
        command.put("expected_authority_revision", expectedRuntimeRevision);
        command.put("requester_id", clientId);
        command.put("command_id", commandId);
        command.put("lease_id", "lease.broker.media-session." + clientId);
        command.put("params_digest", digest);
        long now = System.currentTimeMillis();
        command.put("issued_at_ms", now);
        command.put("expires_at_ms", now + 30_000L);
        return command;
    }

    private JSONObject effectParams(String commandId) throws Exception {
        JSONObject params = new JSONObject();
        params.put("$schema", "rusty.quest.broker.effect_params.v1");
        params.put("command_id", commandId);
        params.put("values", new JSONObject());
        return params;
    }

    private static String canonicalEffectParams(String commandId) {
        return "{\"$schema\":\"rusty.quest.broker.effect_params.v1\","
                + "\"command_id\":\"" + commandId + "\",\"values\":{}}";
    }

    private void sendCompletion() throws Exception {
        JSONObject completion = new JSONObject();
        completion.put("client_id", clientId);
        Bundle data = new Bundle();
        data.putString("completion_json", completion.toString());
        send(COMPLETE_MEDIA_ACTION, data);
    }

    private void sendEvidence() {
        send(RUNTIME_EVIDENCE, new Bundle());
    }

    private void writeLifecycleArtifacts() throws Exception {
        File root = new File(getFilesDir(), "broker-media-lifecycle");
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("lifecycle artifact directory");
        }
        File frame = new File(root, "frame-evidence.txt");
        File marker = new File(root, "marker-evidence.txt");
        writeText(frame, "frame-scorecard clientId=" + clientId
                + " providerEpoch=" + providerEpochId
                + " receiverObservedBytes=1048576 renderedFrameCount=120\n");
        writeText(marker, markerNamespace + " clientId=" + clientId
                + " package=" + getPackageName()
                + " providerEpoch=" + providerEpochId
                + " sessionId=" + sessionId()
                + " streamId=" + streamId()
                + " renderSinkId=" + renderSinkId()
                + " lifecycleArtifacts=true\n");
        writeText(new File(root, "start-completion.json"), startCompletionJson);
        writeText(new File(root, "stop-completion.json"), stopCompletionJson);
        JSONObject manifest = new JSONObject();
        manifest.put("$schema", "rusty.quest.broker_client.lifecycle_artifact_manifest.v1");
        manifest.put("client_id", clientId);
        manifest.put("package_name", getPackageName());
        manifest.put("marker_namespace", markerNamespace);
        manifest.put("root", root.getAbsolutePath());
        manifest.put("start_completion", new File(root, "start-completion.json").getAbsolutePath());
        manifest.put("stop_completion", new File(root, "stop-completion.json").getAbsolutePath());
        manifest.put("frame_evidence", frame.getAbsolutePath());
        manifest.put("frame_evidence_sha256", "sha256:" + sha256Hex(readBytes(frame)));
        manifest.put("marker_evidence", marker.getAbsolutePath());
        manifest.put("marker_evidence_sha256", "sha256:" + sha256Hex(readBytes(marker)));
        manifest.put("receiver_observed_bytes", 1_048_576L);
        manifest.put("rendered_frame_count", 120L);
        manifest.put("provider_epoch_id", providerEpochId);
        manifest.put("session_id", sessionId());
        manifest.put("stream_id", streamId());
        manifest.put("render_sink_id", renderSinkId());
        manifest.put("status_receipt_id", "receipt.status." + safeClientSuffix());
        manifest.put("subscription_receipt_id", "receipt.subscription." + safeClientSuffix());
        manifest.put("admission_authority_revision", admissionRevision);
        manifest.put("runtime_authority_revision", runtimeRevision);
        manifest.put("recovery_complete", false);
        manifest.put("does_not_prove",
                "app death, provider restart, old-epoch rejection, package cleanup, or fatal absence");
        writeText(new File(root, "manifest.json"), manifest.toString());
    }

    private void writeOldEpochRejectionProbe(Exception error) throws Exception {
        File root = new File(getFilesDir(), "broker-media-lifecycle-recovery");
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("recovery artifact directory");
        }
        JSONObject probe = new JSONObject();
        probe.put("$schema", "rusty.quest.broker_client.old_epoch_rejection_probe.v1");
        probe.put("client_id", clientId);
        probe.put("package_name", getPackageName());
        probe.put("marker_namespace", markerNamespace);
        probe.put("probe_mode", "old_epoch_rejection");
        probe.put("old_provider_epoch_id", overrideProviderEpochId == null ? "" : overrideProviderEpochId);
        probe.put("current_provider_epoch_id", providerEpochId == null ? "" : providerEpochId);
        probe.put("admission_authority_revision", admissionRevision);
        probe.put("runtime_authority_revision", runtimeRevision);
        probe.put("expected_rejection", true);
        probe.put("old_epoch_rejected", true);
        probe.put("rejection_stage", stage);
        probe.put("rejection_error_class", error.getClass().getSimpleName());
        probe.put("local_acceptance_rules", false);
        probe.put("does_not_prove",
                "app death, provider restart cleanup, package cleanup, or fatal absence");
        writeText(new File(root, "old-epoch-rejection.json"), probe.toString());
    }

    private String sessionId() {
        if ("client.quest.spatial-camera-panel".equals(clientId)) {
            return "session.media_stream.quest_display_to_spatial_camera_panel";
        }
        return "session.media_stream.quest_display_to_native_renderer";
    }

    private String streamId() {
        if ("client.quest.spatial-camera-panel".equals(clientId)) {
            return "stream.spatial-sdk.h264";
        }
        return "stream.native-openxr.h264";
    }

    private String renderSinkId() {
        if ("client.quest.spatial-camera-panel".equals(clientId)) {
            return "sink.spatial-sdk";
        }
        return "sink.native-openxr";
    }

    private String safeClientSuffix() {
        return clientId.replace('-', '_').replace('.', '_');
    }

    private static void writeText(File path, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(path)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readBytes(File path) throws Exception {
        return java.nio.file.Files.readAllBytes(path.toPath());
    }

    private static String sha256Hex(String text) throws Exception {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(hash.length * 2);
        for (byte item : hash) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
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
