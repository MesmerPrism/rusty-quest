package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;

import io.github.mesmerprism.rustyquest.media.MediaOwnerAction;
import io.github.mesmerprism.rustyquest.media.MediaProductBinding;
import io.github.mesmerprism.rustyquest.media.OwnerDispatchTcpEndpoint;
import io.github.mesmerprism.rustyquest.media.PackagedAndroidMediaOwnerRegistry;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/** App-private JNI callbacks. Authority stays in the embedded Rust Runtime Host. */
final class EmbeddedDuplexPlatform {
    private volatile EmbeddedDuplexProcessFence.CallbackGuard processCallbacks;
    void bindProcessFence(EmbeddedDuplexProcessFence fence) {
        if (fence == null || processCallbacks != null) throw new IllegalStateException("callback fence unavailable");
        processCallbacks = fence.callbacks();
    }
    void retireProcessCallbacks() {
        EmbeddedDuplexProcessFence.CallbackGuard guard = processCallbacks;
        if (guard != null) guard.retire();
    }
    private void requireProcessCallback() {
        EmbeddedDuplexProcessFence.CallbackGuard guard = processCallbacks;
        if (guard == null) throw new IllegalStateException("app callback fence absent");
        guard.requireLive();
    }
    private static final int MAX_REPLAY_BYTES = 16 * 1024 * 1024;
    private final EmbeddedDuplexIdentity.Identity identity;
    private final EmbeddedDuplexDisplay display;
    private final String localPeerId;
    private final String remotePeerId;
    private final String routeConfigurationSha256;
    private final InetAddress remoteControlAddress;
    private final int remoteControlPort;
    private final File replayDirectory;
    private final AtomicFile replayFile;
    private final AtomicFile activationReplayFile;
    private final AtomicFile retainedCleanupReplayFile;
    private final AtomicFile cleanupPreparationsFile;
    private final boolean localFixture;
    private volatile PackagedAndroidMediaOwnerRegistry registry;
    private volatile OwnerDispatchTcpEndpoint endpoint;
    private volatile EmbeddedDuplexResources resources;
    private volatile EmbeddedDuplexActivationGate activationGate;

    EmbeddedDuplexPlatform(Context context, EmbeddedDuplexDisplay display,
            EmbeddedDuplexIdentity.Identity identity, String localPeerId, String remotePeerId,
            String routeConfigurationSha256, String remoteControlIp, int remoteControlPort) throws Exception {
        this(context, display, identity, localPeerId, remotePeerId, routeConfigurationSha256,
                remoteControlIp, remoteControlPort, false);
    }

    EmbeddedDuplexPlatform(Context context, EmbeddedDuplexDisplay display,
            EmbeddedDuplexIdentity.Identity identity, String localPeerId, String remotePeerId,
            String routeConfigurationSha256, String remoteControlIp, int remoteControlPort,
            boolean localFixture) throws Exception {
        if (context == null || display == null || identity == null || localPeerId.equals(remotePeerId)
                || !routeConfigurationSha256.matches("sha256:[0-9a-f]{64}")
                || remoteControlPort <= 0 || remoteControlPort > 65535) {
            throw new IllegalArgumentException("embedded platform binding");
        }
        this.identity = identity;
        this.display = display;
        this.localPeerId = localPeerId;
        this.remotePeerId = remotePeerId;
        this.routeConfigurationSha256 = routeConfigurationSha256;
        this.remoteControlAddress = numericIpv4(remoteControlIp);
        this.remoteControlPort = remoteControlPort;
        this.localFixture = localFixture;
        replayDirectory = new File(context.getNoBackupFilesDir(), "embedded-duplex-replay");
        rejectLink(replayDirectory);
        if (!replayDirectory.isDirectory() && !replayDirectory.mkdir()) {
            throw new IllegalStateException("replay directory unavailable");
        }
        Os.chmod(replayDirectory.getAbsolutePath(), 0700);
        File base = new File(replayDirectory, "owner-dispatch.v1.json");
        rejectLink(base);
        rejectLink(new File(base.getPath() + ".new"));
        rejectLink(new File(base.getPath() + ".bak"));
        replayFile = new AtomicFile(base);
        File activationBase = new File(replayDirectory, "product-activation.v1.json");
        rejectLink(activationBase);
        rejectLink(new File(activationBase.getPath() + ".new"));
        rejectLink(new File(activationBase.getPath() + ".bak"));
        activationReplayFile = new AtomicFile(activationBase);
        File cleanupBase = new File(replayDirectory, "retained-cleanup.v2.json");
        rejectLink(cleanupBase);
        rejectLink(new File(cleanupBase.getPath() + ".new"));
        rejectLink(new File(cleanupBase.getPath() + ".bak"));
        retainedCleanupReplayFile = new AtomicFile(cleanupBase);
        File preparedBase = new File(replayDirectory, "retained-cleanup-preparations.v1.json");
        rejectLink(preparedBase);
        rejectLink(new File(preparedBase.getPath() + ".new"));
        rejectLink(new File(preparedBase.getPath() + ".bak"));
        cleanupPreparationsFile = new AtomicFile(preparedBase);
    }

    /** Called once with resource tuples derived by Rust from packaged bindings. */
    void installRegistry(long generation, MediaProductBinding binding) {
        if (registry != null || endpoint != null) throw new IllegalStateException("registry already installed");
        registry = new PackagedAndroidMediaOwnerRegistry(generation, binding);
    }

    /** Installs the exact resource closure returned by the embedded Runtime Host. */
    void installResources(EmbeddedDuplexResources installed) {
        if (installed == null || resources != null || activationGate != null) {
            throw new IllegalStateException("resources already installed");
        }
        installRegistry(installed.generation(), installed.binding());
        resources = installed;
        activationGate = new EmbeddedDuplexActivationGate(installed);
    }

    /** No Start can be issued until every peer's authenticated control endpoint is ready. */
    void startControl(String localControlIp, int localControlPort) throws Exception {
        if (registry == null || endpoint != null) throw new IllegalStateException("control initialization order");
        endpoint = new OwnerDispatchTcpEndpoint(numericIpv4(localControlIp), localControlPort,
                frame -> {
                    requireProcessCallback();
                    if (localFixture) {
                        throw new IllegalStateException("local fixture has no peer owner authority");
                    }
                    return EmbeddedDuplexNative.handleOwnerFrame(frame);
                });
        if (!endpoint.ready()) throw new IllegalStateException("control endpoint not ready");
    }

    boolean controlReady() { return endpoint != null && endpoint.ready(); }

    // Public visibility is solely for GetMethodID. This package-private object is
    // retained by native GlobalRef and is never exported through an Android component.
    enum OwnerStage { NONE, CALLBACK_FENCE, PROJECTION_BINDING, REGISTRY_BINDING,
        INCOMING_FENCE, LOCAL_QUIESCENCE, PROVIDER_EXECUTION, RECEIPT_VERIFICATION, INCOMING_ARM_VERIFICATION }
    private volatile OwnerStage failedOwnerStage = OwnerStage.NONE;
    private volatile String failedSinkStage = "NONE";
    private volatile String failedOwnerAction = "NONE";
    private volatile ProviderReason failedProviderReason = ProviderReason.NONE;
    enum ProviderReason { NONE, TICKET_PARSE, STALE_GENERATION, UNDECLARED_BINDING,
        REGISTRY_CLOSED, PROVIDER_BUSY, CAPACITY, PREPARATION_ALREADY_ATTEMPTED,
        FOREIGN_READBACK, RECEIPT_COLLISION, DISPLAY_LOCAL_SHUTDOWN, DISPLAY_DISPATCH_FENCED, DISPLAY_TRANSITION_TIMEOUT, DISPLAY_ADMISSION_REJECTED, DISPLAY_NATIVE_ACTIVE_EPOCH, DISPLAY_NATIVE_ACTIVE_STATE, DISPLAY_NATIVE_BOUNDS, DISPLAY_NATIVE_CAPTURE, DISPLAY_NATIVE_CARRIER, DISPLAY_NATIVE_CLOCK, DISPLAY_NATIVE_FRAME_ABSENT, DISPLAY_NATIVE_FRAME_EPOCH, DISPLAY_NATIVE_FRAME_FUTURE, DISPLAY_NATIVE_FRAME_STALE, DISPLAY_NATIVE_INPUT, DISPLAY_NATIVE_LOCAL, DISPLAY_NATIVE_PROCESS_EPOCH, DISPLAY_NATIVE_SOURCE_STATE, DISPLAY_NATIVE_SUPERSEDED, DISPLAY_OWN_CAPTURE_STATE, DISPLAY_OWN_CAPTURE_FRESH, DISPLAY_OWN_CARRIER_SUPERSEDED, DISPLAY_NATIVE_SHAPE, DISPLAY_ROUTING_SUPERSEDED, OTHER }
    // Fixed owner-local categories only. Never return or log exception messages or ticket fields.
    private volatile String failedOwnerKind = "NONE";
    private volatile String failedCause = "NONE";
    static String failureCategory(Throwable failure) {
        boolean state = false;
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof android.media.MediaCodec.CodecException) return "CODEC";
            if (failure instanceof java.util.concurrent.TimeoutException) return "TIMEOUT";
            if (failure instanceof java.io.IOException) return "IO";
            if (failure instanceof SecurityException) return "SECURITY";
            if (failure instanceof IllegalArgumentException) return "ARGUMENT";
            state |= failure instanceof IllegalStateException;
        }
        return state ? "STATE" : "OTHER";
    }
    static ProviderReason providerReason(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            String message = failure.getMessage();
            if (failure instanceof java.util.concurrent.TimeoutException) return ProviderReason.DISPLAY_TRANSITION_TIMEOUT;
            if (message == null) continue;
            if ((message.equals("embedded receiver requires actual local acquisition shutdown") || message.equals("old native local acquisition remains Pending") || message.equals("local capture still owns camera resources") || message.equals("local camera shutdown incomplete"))) return ProviderReason.DISPLAY_LOCAL_SHUTDOWN;
            if (message.equals("Activity lifecycle is already fenced")) return ProviderReason.DISPLAY_DISPATCH_FENCED;
            if (message.equals("concurrent Own native admission ACTIVE_EPOCH")) return ProviderReason.DISPLAY_NATIVE_ACTIVE_EPOCH;
            if (message.equals("concurrent Own native admission ACTIVE_STATE")) return ProviderReason.DISPLAY_NATIVE_ACTIVE_STATE;
            if (message.equals("concurrent Own native admission BOUNDS")) return ProviderReason.DISPLAY_NATIVE_BOUNDS;
            if (message.equals("concurrent Own native admission CAPTURE")) return ProviderReason.DISPLAY_NATIVE_CAPTURE;
            if (message.equals("concurrent Own native admission CARRIER")) return ProviderReason.DISPLAY_NATIVE_CARRIER;
            if (message.equals("concurrent Own native admission CLOCK")) return ProviderReason.DISPLAY_NATIVE_CLOCK;
            if (message.equals("concurrent Own native admission FRAME_ABSENT")) return ProviderReason.DISPLAY_NATIVE_FRAME_ABSENT;
            if (message.equals("concurrent Own native admission FRAME_EPOCH")) return ProviderReason.DISPLAY_NATIVE_FRAME_EPOCH;
            if (message.equals("concurrent Own native admission FRAME_FUTURE")) return ProviderReason.DISPLAY_NATIVE_FRAME_FUTURE;
            if (message.equals("concurrent Own native admission FRAME_STALE")) return ProviderReason.DISPLAY_NATIVE_FRAME_STALE;
            if (message.equals("concurrent Own native admission INPUT")) return ProviderReason.DISPLAY_NATIVE_INPUT;
            if (message.equals("concurrent Own native admission LOCAL")) return ProviderReason.DISPLAY_NATIVE_LOCAL;
            if (message.equals("concurrent Own native admission PROCESS_EPOCH")) return ProviderReason.DISPLAY_NATIVE_PROCESS_EPOCH;
            if (message.equals("concurrent Own native admission SOURCE_STATE")) return ProviderReason.DISPLAY_NATIVE_SOURCE_STATE;
            if (message.equals("concurrent Own native admission SUPERSEDED")) return ProviderReason.DISPLAY_NATIVE_SUPERSEDED;
            if (message.equals("concurrent Own capture unavailable") || message.equals("concurrent Own capture not Live") || message.equals("concurrent Own capture superseded")) return ProviderReason.DISPLAY_OWN_CAPTURE_STATE;
            if (message.equals("concurrent Own capture not fresh")) return ProviderReason.DISPLAY_OWN_CAPTURE_FRESH;
            if (message.equals("concurrent Own carrier superseded")) return ProviderReason.DISPLAY_OWN_CARRIER_SUPERSEDED;
            if (message.equals("concurrent Own native admission unavailable")) return ProviderReason.DISPLAY_NATIVE_SHAPE;
            if (message.equals("concurrent Peer reservation superseded")) return ProviderReason.DISPLAY_ROUTING_SUPERSEDED;
            if (message.startsWith("concurrent Own ") || message.equals("concurrent Peer reservation superseded")) return ProviderReason.DISPLAY_ADMISSION_REJECTED;
            if (message.equals("media execution generation is stale") || message.equals("stale registry generation")) return ProviderReason.STALE_GENERATION;
            if (message.equals("undeclared media provider binding")) return ProviderReason.UNDECLARED_BINDING;
            if (message.equals("no-media registry closed")) return ProviderReason.REGISTRY_CLOSED;
            if (message.equals("ProviderBusy")) return ProviderReason.PROVIDER_BUSY;
            if (message.equals("media execution registry full")) return ProviderReason.CAPACITY;
            if (message.equals("receiver preparation already attempted")) return ProviderReason.PREPARATION_ALREADY_ATTEMPTED;
            if (message.equals("provider returned foreign readback")) return ProviderReason.FOREIGN_READBACK;
            if (message.equals("receipt collision")) return ProviderReason.RECEIPT_COLLISION;
            if (failure instanceof IllegalArgumentException) {
                for (StackTraceElement frame : failure.getStackTrace()) {
                    if (frame.getClassName().equals(MediaOwnerAction.class.getName())) return ProviderReason.TICKET_PARSE;
                }
            }
        }
        return ProviderReason.OTHER;
    }
    public String ownerFailureDiagnostic() throws Exception {
        return new JSONObject().put("stage", failedOwnerStage.name())
                .put("sink_stage", failedSinkStage).put("action", failedOwnerAction)
                .put("provider_reason", failedProviderReason.name()).put("owner", failedOwnerKind)
                .put("cause", failedCause).put("code", failedOwnerStage == OwnerStage.NONE
                        ? "NONE" : "OWNER_EFFECT_REJECTED").toString();
    }
    public String executeAndVerify(String authorityJson, String ticketJson, boolean compensate) throws Exception {
        OwnerStage stage = OwnerStage.CALLBACK_FENCE;
        MediaOwnerAction ticket = null;
        try {
        requireProcessCallback();
        JSONObject authority = new JSONObject(authorityJson);
        ticket = MediaOwnerAction.parse(ticketJson);
        stage = OwnerStage.PROJECTION_BINDING;
        if (!"rusty.quest.c1.owner_projection.v1".equals(authority.getString("$schema"))
                || !localPeerId.equals(authority.getString("executor_peer_id"))
                || !routeConfigurationSha256.equals(authority.getString("route_configuration_sha256"))
                || authority.getLong("expires_at_ms") <= System.currentTimeMillis()
                || !ticket.authorityEpochId().equals(authority.getString("authority_provider_epoch_id"))
                || !ticket.clientId().equals(authority.getString("authority_client_id"))
                || !ticket.leaseId().equals(authority.getString("authority_runtime_lease_id"))) {
            throw new IllegalStateException("platform projection binding rejected");
        }
        stage = OwnerStage.REGISTRY_BINDING;
        PackagedAndroidMediaOwnerRegistry current = registry;
        if (current == null) throw new IllegalStateException("platform registry absent");
        requireProcessCallback();
        EmbeddedDuplexActivationGate gate = activationGate;
        EmbeddedDuplexActivationGate.MediaTicket activationTicket = activationTicket(ticket);
        stage = OwnerStage.INCOMING_FENCE;
        if (gate != null) gate.beforeOwnerEffect(authority, activationTicket, compensate);
        if (!compensate && "source".equals(ticket.ownerKind()) && "start".equals(ticket.actionKind())) {
            stage = OwnerStage.LOCAL_QUIESCENCE;
            display.ensureLocalCaptureStopped();
            if (!EmbeddedDuplexNative.localCameraQuiescent()) {
                throw new IllegalStateException("local Camera2 ownership remains live");
            }
        }
        stage = OwnerStage.PROVIDER_EXECUTION;
        String readback = current.execute(ticketJson, compensate);
        stage = OwnerStage.RECEIPT_VERIFICATION;
        String verified = current.verifyAndReadEvidence(ticketJson, readback);
        if (verified == null) throw new IllegalStateException("live provider evidence rejected");
        if (gate != null) {
            stage = OwnerStage.INCOMING_ARM_VERIFICATION;
            gate.afterVerifiedOwnerEffect(authority, activationTicket,
                    new JSONObject(readback), new JSONObject(verified), compensate);
        }
        JSONObject result = new JSONObject();
        result.put("readback", new JSONObject(readback));
        result.put("readback_json", readback);
        result.put("verified", new JSONObject(verified));
        return result.toString();
        } catch (Exception failure) {
            // Retain the primary failure within this incarnation. Cleanup failures must not erase it.
            synchronized (this) {
                if (failedOwnerStage == OwnerStage.NONE) {
                    EmbeddedDuplexResources currentResources = resources;
                    failedSinkStage = ticket != null && "sink".equals(ticket.ownerKind())
                            && currentResources != null ? currentResources.incoming().failedArmStage() : "NONE";
                    String kind = ticket == null ? "NONE" : ticket.actionKind();
                    failedOwnerAction = "arm_receiver".equals(kind) ? "ARM_RECEIVER"
                            : "arm_cleanup".equals(kind) ? "ARM_CLEANUP" : "start".equals(kind) ? "START"
                            : "stop".equals(kind) ? "STOP" : "cleanup".equals(kind) ? "CLEANUP" : "BEFORE_TICKET";
                    failedProviderReason = stage == OwnerStage.PROVIDER_EXECUTION
                            ? providerReason(failure) : ProviderReason.NONE;
                    failedOwnerKind = ticket == null ? "NONE" : ticket.ownerKind();
                    failedCause = stage == OwnerStage.PROVIDER_EXECUTION ? failureCategory(failure) : "NONE";
                    failedOwnerStage = stage;
                }
            }
            android.util.Log.i("RQSpatialCameraPanel", "channel=embedded-duplex status=owner-effect-rejected stage="
                    + stage.name() + " primaryStage=" + failedOwnerStage.name() + " primaryAction=" + failedOwnerAction
                    + " sinkStage=" + failedSinkStage + " providerReason=" + failedProviderReason.name()
                    + " code=OWNER_EFFECT_REJECTED");
            throw failure;
        }
    }

    // Public visibility is required by the native ProductActivationRegistry callback.
    public String activateProduct(String activationId, String authorityJson, String proofJson)
            throws Exception {
        requireProcessCallback();
        EmbeddedDuplexActivationGate gate = activationGate;
        if (gate == null || resources == null) throw new IllegalStateException("resources absent");
        return gate.activate(activationId, authorityJson, proofJson);
    }

    public byte[] signAuthorityBytes(byte[] exactNativeBytes) throws Exception {
        requireProcessCallback();
        return EmbeddedDuplexIdentity.signExactAuthorityBytes(identity, exactNativeBytes);
    }

    // Native calls this only after current route, enrollment, endpoint and time
    // validation. The generic dispatch signer cannot sign this domain.
    public byte[] signValidatedCommonLanBytes(byte[] validatedNativeBytes) throws Exception {
        requireProcessCallback();
        return EmbeddedDuplexIdentity.signValidatedCommonLanBytes(identity, validatedNativeBytes);
    }

    public byte[] signPairCeremonyBytes(byte[] exactNativeBytes) throws Exception {
        requireProcessCallback();
        return EmbeddedDuplexIdentity.signPairCeremonyBytes(identity, exactNativeBytes);
    }

    public byte[] localPublicKeyBytes() {
        requireProcessCallback();
        return identity.rawPublicKey();
    }

    public byte[] exchangeOwnerFrame(String targetPeerId, byte[] exactFrame) throws Exception {
        requireProcessCallback();
        if (!remotePeerId.equals(targetPeerId) || !controlReady()) {
            throw new IllegalStateException("owner control target unavailable");
        }
        return OwnerDispatchTcpEndpoint.exchange(remoteControlAddress, remoteControlPort, exactFrame);
    }

    /** Fixed native v2 callback; target identity is preserved independently of requester. */
    public String executeRetainedCleanupAndVerify(String authorityJson, String ticketJson,
            boolean compensate) throws Exception {
        requireProcessCallback();
        JSONObject authority = new JSONObject(authorityJson);
        MediaOwnerAction ticket = MediaOwnerAction.parse(ticketJson);
        if (!"rusty.quest.android.media.retained_cleanup_projection.v2".equals(authority.getString("$schema"))
                || !localPeerId.equals(authority.getString("executor_peer_id"))
                || authority.getLong("expires_at_ms") <= System.currentTimeMillis()
                || authority.getLong("requester_expires_at_ms") <= System.currentTimeMillis()
                || !ticket.authorityEpochId().equals(authority.getString("provider_epoch_id"))
                || !ticket.clientId().equals(authority.getString("target_client_id"))
                || !ticket.leaseId().equals(authority.getString("target_runtime_lease_id"))
                || !"stop".equals(ticket.operation())
                || !("stop".equals(ticket.actionKind()) || "cleanup".equals(ticket.actionKind()))) {
            throw new IllegalStateException("retained cleanup target binding rejected");
        }
        boolean distinct = authority.getBoolean("trusted_revoker");
        if (distinct != (!ticket.clientId().equals(authority.getString("requester_id"))
                && !ticket.leaseId().equals(authority.getString("requester_runtime_lease_id")))) {
            throw new IllegalStateException("retained cleanup requester binding rejected");
        }
        PackagedAndroidMediaOwnerRegistry current = registry;
        if (current == null) throw new IllegalStateException("platform registry absent");
        EmbeddedDuplexActivationGate gate = activationGate;
        EmbeddedDuplexActivationGate.MediaTicket activationTicket = activationTicket(ticket);
        if (gate != null) gate.beforeOwnerEffect(authority, activationTicket, compensate);
        requireProcessCallback();
        String readback = current.execute(ticketJson, compensate);
        String verified = current.verifyAndReadEvidence(ticketJson, readback);
        if (verified == null) throw new IllegalStateException("retained cleanup live evidence rejected");
        if (gate != null) gate.afterVerifiedOwnerEffect(authority, activationTicket,
                new JSONObject(readback), new JSONObject(verified), compensate);
        return new JSONObject().put("readback", new JSONObject(readback))
                .put("readback_json", readback).put("verified", new JSONObject(verified)).toString();
    }

    public synchronized void persistCleanupPreparations(String snapshotJson) throws Exception {
        requireProcessCallback();
        persistReplay(cleanupPreparationsFile, snapshotJson);
    }
    public synchronized String loadCleanupPreparations() throws Exception {
        requireProcessCallback();
        if (!cleanupPreparationsFile.getBaseFile().exists()
                && !new File(cleanupPreparationsFile.getBaseFile().getPath() + ".bak").exists()) {
            return "{\"originals\":{},\"prepared\":{}}";
        }
        return loadReplay(cleanupPreparationsFile);
    }

    public synchronized void persistRetainedCleanupReplay(String snapshotJson) throws Exception {
        requireProcessCallback();
        persistReplay(retainedCleanupReplayFile, snapshotJson);
    }

    public synchronized String loadRetainedCleanupReplay() throws Exception {
        requireProcessCallback();
        return loadReplay(retainedCleanupReplayFile);
    }

    public synchronized void persistDispatchReplay(String snapshotJson) throws Exception {
        requireProcessCallback();
        persistReplay(replayFile, snapshotJson);
    }

    public synchronized void persistActivationReplay(String snapshotJson) throws Exception {
        requireProcessCallback();
        persistReplay(activationReplayFile, snapshotJson);
    }

    private void persistReplay(AtomicFile file, String snapshotJson) throws Exception {
        byte[] encoded = snapshotJson.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > MAX_REPLAY_BYTES) {
            throw new IllegalArgumentException("replay bounds");
        }
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            Os.fchmod(output.getFD(), 0600);
            output.write(encoded);
            output.getFD().sync();
            file.finishWrite(output);
            output = null;
            syncReplayDirectory();
        } finally {
            if (output != null) file.failWrite(output);
        }
    }

    synchronized String loadDispatchReplay() throws Exception { return loadReplay(replayFile); }

    public synchronized String loadActivationReplay() throws Exception {
        return loadReplay(activationReplayFile);
    }

    private String loadReplay(AtomicFile file) throws Exception {
        if (!file.getBaseFile().exists()
                && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            return "{\"pending_request_sha256\":{},\"terminal\":{}}";
        }
        try (FileInputStream input = file.openRead()) {
            long length = input.getChannel().size();
            if (length <= 0 || length > MAX_REPLAY_BYTES) throw new IllegalStateException("replay bounds");
            byte[] encoded = new byte[(int) length];
            int offset = 0;
            while (offset < encoded.length) {
                int count = input.read(encoded, offset, encoded.length - offset);
                if (count < 0) throw new IllegalStateException("replay truncated");
                offset += count;
            }
            if (input.read() != -1) throw new IllegalStateException("replay grew during read");
            return new String(encoded, StandardCharsets.UTF_8);
        }
    }

    private static EmbeddedDuplexActivationGate.MediaTicket activationTicket(MediaOwnerAction ticket) {
        return new EmbeddedDuplexActivationGate.MediaTicket(ticket.executorGeneration(),
                ticket.expectedRuntimeRevision(), ticket.actionId(), ticket.authorityEpochId(),
                ticket.clientId(), ticket.leaseId(), ticket.operation(), ticket.ownerKind(),
                ticket.actionKind());
    }

    /** Only after both products' authenticated terminal cleanup, or before any effect was issued. */
    void closeControlAfterProductCleanup() {
        OwnerDispatchTcpEndpoint current = endpoint;
        if (current != null) {
            current.close();
            if (!current.terminal()) throw new IllegalStateException("owner control cleanup pending");
        }
        PackagedAndroidMediaOwnerRegistry owners = registry;
        if (owners != null) owners.close();
    }

    /** Drain authenticated ingress before proving the registry never entered a provider. */
    void closeControlForNoMedia() {
        OwnerDispatchTcpEndpoint current = endpoint;
        if (current != null) {
            current.close();
            if (!current.terminal()) throw new IllegalStateException("owner control cleanup pending");
        }
        PackagedAndroidMediaOwnerRegistry owners = registry;
        if (owners != null) owners.closeIfNeverAttempted();
    }

    private void syncReplayDirectory() throws Exception {
        FileDescriptor directory = Os.open(replayDirectory.getAbsolutePath(),
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
        try {
            if ((Os.fstat(directory).st_mode & OsConstants.S_IFMT) != OsConstants.S_IFDIR) {
                throw new IllegalStateException("replay directory changed");
            }
            Os.fsync(directory);
        } finally { Os.close(directory); }
    }

    private static void rejectLink(File file) {
        if (Files.isSymbolicLink(file.toPath()) || (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalStateException("replay path type");
        }
    }

    private static InetAddress numericIpv4(String address) throws Exception {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("numeric IPv4 required");
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("canonical IPv4 required");
            int value = Integer.parseInt(parts[i]);
            if (value > 255) throw new IllegalArgumentException("IPv4 bounds");
            bytes[i] = (byte) value;
        }
        InetAddress parsed = InetAddress.getByAddress(bytes);
        if (parsed.isAnyLocalAddress() || parsed.isLoopbackAddress() || parsed.isMulticastAddress()
                || "255.255.255.255".equals(address)) throw new IllegalArgumentException("unicast peer IPv4 required");
        return parsed;
    }
}
