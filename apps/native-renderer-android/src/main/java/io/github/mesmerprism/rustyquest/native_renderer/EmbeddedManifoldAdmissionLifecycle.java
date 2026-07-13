package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Platform-authenticated issue/use/mutation lifecycle for the embedded broker.
 * Android supplies package and signer evidence; Rust owns every grant and command decision.
 */
final class EmbeddedManifoldAdmissionLifecycle {
    private static final String OPERATION_SCHEMA =
            "rusty.quest.broker.admission_operation.v1";
    private static final String MUTATION_SCHEMA =
            "rusty.quest.broker.server_mutation_request.v1";
    private static final String COMMAND_REQUEST_SCHEMA =
            "rusty.manifold.runtime_host.command_request.v1";
    private static final long TOKEN_TTL_MS = 20_000L;
    private static final long REQUEST_WINDOW_MS = 10_000L;

    private final EmbeddedCallerIdentityResolver.Identity caller;
    private final SecureRandom secureRandom;
    private final String requestNamespace;
    private String providerEpochId;
    private long admissionAuthorityRevision;
    private long runtimeHostRevision;
    private long requestSequence;

    private EmbeddedManifoldAdmissionLifecycle(
            EmbeddedCallerIdentityResolver.Identity caller,
            JSONObject initializeStatus,
            SecureRandom secureRandom) throws Exception {
        this.caller = caller;
        this.secureRandom = secureRandom;
        this.requestNamespace = entropyHex(secureRandom, 16);
        this.providerEpochId = initializeStatus.getString("provider_epoch_id");
        this.admissionAuthorityRevision =
                initializeStatus.getLong("admission_authority_revision");
        this.runtimeHostRevision = initializeStatus.getLong("runtime_host_revision");
        if (initializeStatus.optBoolean("local_acceptance_rules", true)
                || this.admissionAuthorityRevision <= 0
                || this.runtimeHostRevision <= 0) {
            throw new IllegalStateException("embedded runtime initialization is not authority-bound");
        }
    }

    static EmbeddedManifoldAdmissionLifecycle create(
            Context context,
            JSONObject initializeStatus) throws Exception {
        String packageName = context.getPackageName();
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES);
        Signature[] signers = packageInfo.signingInfo == null
                ? new Signature[0]
                : packageInfo.signingInfo.getApkContentsSigners();
        byte[][] signerBytes = new byte[signers.length][];
        for (int index = 0; index < signers.length; index += 1) {
            signerBytes[index] = signers[index].toByteArray();
        }
        EmbeddedCallerIdentityResolver.Identity identity =
                EmbeddedCallerIdentityResolver.requireExact(
                        Process.myUid(),
                        packageName,
                        GeneratedEmbeddedManifoldRuntimeConfig.PACKAGE_NAME,
                        signerBytes);
        return new EmbeddedManifoldAdmissionLifecycle(
                identity,
                initializeStatus,
                new SecureRandom());
    }

    synchronized JSONObject mutate(JSONObject untrustedRequest) throws Exception {
        JSONObject sourceCommand = untrustedRequest.getJSONObject("command");
        String commandId = sourceCommand.getString("command_id");
        String capabilityId = commandCapability(commandId);
        long nowMs = System.currentTimeMillis();

        JSONObject issue = baseOperation("issue_token", nextRequestId("issue"));
        issue.put("expected_authority_revision", admissionAuthorityRevision);
        issue.put("requested_capabilities", new JSONArray().put(capabilityId));
        issue.put("requested_token_ttl_ms", TOKEN_TTL_MS);
        issue.put("issued_at_ms", nowMs);
        issue.put("expires_at_ms", nowMs + REQUEST_WINDOW_MS);
        issue.put("entropy_hex", entropyHex(secureRandom, 32));
        JSONObject issueResponse = EmbeddedManifoldRuntimeAuthorityBridge.admit(issue);
        JSONObject issueReceipt = issueResponse.getJSONObject("receipt");
        admissionAuthorityRevision = issueReceipt.getLong("resulting_authority_revision");
        if (!issueReceipt.optBoolean("applied", false)) {
            return issueResponse;
        }
        String tokenId = issueReceipt.getJSONObject("token").getString("token_id");

        String useRequestId = nextRequestId("use");
        JSONObject authorizeUse = baseOperation("authorize_use", useRequestId);
        authorizeUse.put("expected_authority_revision", admissionAuthorityRevision);
        authorizeUse.put("token_id", tokenId);
        authorizeUse.put("capability_id", capabilityId);
        authorizeUse.put("issued_at_ms", nowMs);
        authorizeUse.put("expires_at_ms", nowMs + REQUEST_WINDOW_MS);
        JSONObject useResponse = EmbeddedManifoldRuntimeAuthorityBridge.admit(authorizeUse);
        JSONObject useReceipt = useResponse.getJSONObject("receipt");
        admissionAuthorityRevision = useReceipt.getLong("resulting_authority_revision");
        if (!useReceipt.optBoolean("applied", false)) {
            return useResponse;
        }

        JSONObject command = new JSONObject(sourceCommand.toString());
        command.put("$schema", COMMAND_REQUEST_SCHEMA);
        command.put("expected_authority_revision", runtimeHostRevision);
        command.put("requester_id", GeneratedEmbeddedManifoldRuntimeConfig.CLIENT_ID);
        command.put("issued_at_ms", nowMs);
        command.put("expires_at_ms", nowMs + REQUEST_WINDOW_MS);

        JSONObject mutation = new JSONObject();
        mutation.put("$schema", MUTATION_SCHEMA);
        mutation.put("bridge_kind", "embedded_in_process_jni");
        mutation.put("provider_epoch_id", providerEpochId);
        mutation.put("admission_use_request_id", useRequestId);
        mutation.put("token_id", tokenId);
        mutation.put("expected_admission_authority_revision", admissionAuthorityRevision);
        mutation.put("command", command);
        mutation.put("params", new JSONObject(untrustedRequest.getJSONObject("params").toString()));

        JSONObject response = EmbeddedManifoldRuntimeAuthorityBridge.mutate(mutation);
        JSONObject mutationReceipt = response.getJSONObject("mutation_receipt");
        admissionAuthorityRevision =
                mutationReceipt.getLong("admission_authority_revision");
        JSONObject adapterReceipt = mutationReceipt.optJSONObject("adapter_receipt");
        if (adapterReceipt != null) {
            JSONObject application = adapterReceipt.optJSONObject("application");
            if (application != null) {
                runtimeHostRevision =
                        application.getLong("resulting_authority_revision");
            }
        }
        return response;
    }

    private JSONObject baseOperation(String operation, String requestId) throws Exception {
        JSONObject callerJson = new JSONObject();
        callerJson.put("sending_uid", caller.uid);
        callerJson.put("package_name", caller.packageName);
        callerJson.put("signing_certificate_sha256", caller.signingCertificateSha256);
        JSONObject request = new JSONObject();
        request.put("operation", operation);
        request.put("$schema", OPERATION_SCHEMA);
        request.put("caller", callerJson);
        request.put("request_id", requestId);
        return request;
    }

    private String nextRequestId(String operation) {
        requestSequence += 1;
        return "request.embedded." + requestNamespace + "." + requestSequence + "." + operation;
    }

    private static String commandCapability(String commandId) {
        String suffix = commandId.startsWith("command.")
                ? commandId.substring("command.".length())
                : commandId;
        return "capability.command." + suffix;
    }

    private static String entropyHex(SecureRandom random, int bytes) {
        byte[] entropy = new byte[bytes];
        random.nextBytes(entropy);
        StringBuilder result = new StringBuilder(bytes * 2);
        for (byte value : entropy) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
