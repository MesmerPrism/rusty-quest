package io.github.mesmerprism.rustyquest.packageupdater;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class UpdateStateStore {
    private static final String STATE_SCHEMA =
            "rusty.quest.package_update_rollback_state.v1";
    private static final int MAX_STATE_BYTES = 64 * 1024;
    private static final long MAX_JCS_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final AtomicFile stateFile;

    UpdateStateStore(Context context) {
        File directory = new File(
                context.getNoBackupFilesDir(), "package-updater/alpha/rollback");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("could_not_create_private_state_directory");
        }
        stateFile = new AtomicFile(new File(directory, "rollback-state.json"));
    }

    synchronized void requireAdvances(VerifiedUpdatePlan plan)
            throws UpdateEnvelopeVerifier.VerificationException {
        for (Checkpoint checkpoint : read()) {
            if (checkpoint.matches(plan)) {
                if (plan.sequence <= checkpoint.sequence) {
                    throw new UpdateEnvelopeVerifier.VerificationException(
                            "sequence_rollback");
                }
                if (plan.artifact.versionCode <= checkpoint.versionCode) {
                    throw new UpdateEnvelopeVerifier.VerificationException(
                            "version_rollback");
                }
                return;
            }
        }
    }

    synchronized void commitInstalled(VerifiedUpdatePlan plan) throws Exception {
        List<Checkpoint> checkpoints = read();
        for (Checkpoint checkpoint : checkpoints) {
            if (checkpoint.matches(plan)
                    && checkpoint.sequence == plan.sequence
                    && checkpoint.versionCode == plan.artifact.versionCode
                    && checkpoint.signedManifestSha256.equals(
                            plan.signedManifestSha256)) {
                return;
            }
        }
        requireAdvances(plan);
        checkpoints.removeIf(checkpoint -> checkpoint.matches(plan));
        checkpoints.add(new Checkpoint(
                plan.channel,
                plan.artifact.packageName,
                plan.rolloutRing,
                plan.signerSha256,
                plan.keyId,
                plan.publicKey,
                plan.httpsOrigin,
                plan.sequence,
                plan.artifact.versionCode,
                plan.signedManifestSha256));
        checkpoints.sort(Comparator.comparing(
                checkpoint -> checkpoint.tupleKey()));

        JSONObject state = new JSONObject();
        state.put("schema", STATE_SCHEMA);
        JSONArray values = new JSONArray();
        for (Checkpoint checkpoint : checkpoints) {
            JSONObject value = new JSONObject();
            value.put("channel", checkpoint.channel);
            value.put("package_name", checkpoint.packageName);
            value.put("rollout_ring", checkpoint.rolloutRing);
            value.put("signer_sha256", checkpoint.signerSha256);
            value.put("key_id", checkpoint.keyId);
            value.put("public_key", checkpoint.publicKey);
            value.put("https_origin", checkpoint.httpsOrigin);
            value.put("sequence", checkpoint.sequence);
            value.put("version_code", checkpoint.versionCode);
            value.put("signed_manifest_sha256", checkpoint.signedManifestSha256);
            values.put(value);
        }
        state.put("checkpoints", values);
        write(state);
    }

    private List<Checkpoint> read()
            throws UpdateEnvelopeVerifier.VerificationException {
        if (!stateFile.getBaseFile().isFile()) {
            return new ArrayList<>();
        }
        try (FileInputStream input = stateFile.openRead()) {
            byte[] bytes = input.readAllBytes();
            if (bytes.length == 0 || bytes.length > MAX_STATE_BYTES) {
                throw new UpdateEnvelopeVerifier.VerificationException(
                        "rollback_state_size_invalid");
            }
            JSONObject state =
                    new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!STATE_SCHEMA.equals(state.getString("schema"))
                    || state.length() != 2) {
                throw new UpdateEnvelopeVerifier.VerificationException(
                        "wrong_rollback_state_schema");
            }
            JSONArray values = state.getJSONArray("checkpoints");
            List<Checkpoint> checkpoints = new ArrayList<>();
            String priorKey = null;
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.getJSONObject(index);
                if (value.length() != 10) {
                    throw new UpdateEnvelopeVerifier.VerificationException(
                            "invalid_rollback_checkpoint");
                }
                Checkpoint checkpoint = new Checkpoint(
                        value.getString("channel"),
                        value.getString("package_name"),
                        value.getString("rollout_ring"),
                        value.getString("signer_sha256"),
                        value.getString("key_id"),
                        value.getString("public_key"),
                        value.getString("https_origin"),
                        value.getLong("sequence"),
                        value.getLong("version_code"),
                        value.getString("signed_manifest_sha256"));
                String key = checkpoint.tupleKey();
                if (!checkpoint.channel.matches("[A-Za-z0-9._-]{1,32}")
                        || !checkpoint.packageName
                                .matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
                        || !checkpoint.rolloutRing.matches("[A-Za-z0-9._-]{1,32}")
                        || !checkpoint.signerSha256.matches("sha256:[0-9a-f]{64}")
                        || !checkpoint.keyId.matches("[A-Za-z0-9._-]{1,96}")
                        || !checkpoint.publicKey.matches("[A-Za-z0-9_-]{43}")
                        || !checkpoint.httpsOrigin
                                .matches("https://[a-z0-9.-]+(?::[1-9][0-9]{0,4})?")
                        || checkpoint.sequence <= 0L
                        || checkpoint.sequence > MAX_JCS_SAFE_INTEGER
                        || checkpoint.versionCode <= 0L
                        || checkpoint.versionCode > MAX_JCS_SAFE_INTEGER
                        || !checkpoint.signedManifestSha256
                                .matches("sha256:[0-9a-f]{64}")
                        || (priorKey != null && priorKey.compareTo(key) >= 0)) {
                    throw new UpdateEnvelopeVerifier.VerificationException(
                            "invalid_rollback_checkpoint");
                }
                priorKey = key;
                checkpoints.add(checkpoint);
            }
            return checkpoints;
        } catch (UpdateEnvelopeVerifier.VerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UpdateEnvelopeVerifier.VerificationException(
                    "rollback_state_unreadable", exception);
        }
    }

    private void write(JSONObject value) throws Exception {
        FileOutputStream output = null;
        try {
            output = stateFile.startWrite();
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            stateFile.finishWrite(output);
        } catch (Exception exception) {
            if (output != null) {
                stateFile.failWrite(output);
            }
            throw exception;
        }
    }

    private static final class Checkpoint {
        final String channel;
        final String packageName;
        final String rolloutRing;
        final String signerSha256;
        final String keyId;
        final String publicKey;
        final String httpsOrigin;
        final long sequence;
        final long versionCode;
        final String signedManifestSha256;

        Checkpoint(
                String channel,
                String packageName,
                String rolloutRing,
                String signerSha256,
                String keyId,
                String publicKey,
                String httpsOrigin,
                long sequence,
                long versionCode,
                String signedManifestSha256) {
            this.channel = channel;
            this.packageName = packageName;
            this.rolloutRing = rolloutRing;
            this.signerSha256 = signerSha256;
            this.keyId = keyId;
            this.publicKey = publicKey;
            this.httpsOrigin = httpsOrigin;
            this.sequence = sequence;
            this.versionCode = versionCode;
            this.signedManifestSha256 = signedManifestSha256;
        }

        boolean matches(VerifiedUpdatePlan plan) {
            return channel.equals(plan.channel)
                    && packageName.equals(plan.artifact.packageName)
                    && rolloutRing.equals(plan.rolloutRing)
                    && signerSha256.equals(plan.signerSha256)
                    && keyId.equals(plan.keyId)
                    && publicKey.equals(plan.publicKey)
                    && httpsOrigin.equals(plan.httpsOrigin);
        }

        String tupleKey() {
            return channel + "\0" + packageName + "\0" + rolloutRing + "\0"
                    + signerSha256 + "\0" + keyId + "\0" + publicKey + "\0"
                    + httpsOrigin;
        }
    }
}
