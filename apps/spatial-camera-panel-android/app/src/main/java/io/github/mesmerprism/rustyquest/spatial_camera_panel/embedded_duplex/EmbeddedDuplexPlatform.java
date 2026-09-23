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
    private volatile PackagedAndroidMediaOwnerRegistry registry;
    private volatile OwnerDispatchTcpEndpoint endpoint;
    private volatile EmbeddedDuplexResources resources;
    private volatile EmbeddedDuplexActivationGate activationGate;

    EmbeddedDuplexPlatform(Context context, EmbeddedDuplexDisplay display,
            EmbeddedDuplexIdentity.Identity identity, String localPeerId, String remotePeerId,
            String routeConfigurationSha256, String remoteControlIp, int remoteControlPort) throws Exception {
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
                EmbeddedDuplexNative::handleOwnerFrame);
        if (!endpoint.ready()) throw new IllegalStateException("control endpoint not ready");
    }

    boolean controlReady() { return endpoint != null && endpoint.ready(); }

    // Public visibility is solely for GetMethodID. This package-private object is
    // retained by native GlobalRef and is never exported through an Android component.
    public String executeAndVerify(String authorityJson, String ticketJson, boolean compensate) throws Exception {
        JSONObject authority = new JSONObject(authorityJson);
        MediaOwnerAction ticket = MediaOwnerAction.parse(ticketJson);
        if (!"rusty.quest.c1.owner_projection.v1".equals(authority.getString("$schema"))
                || !localPeerId.equals(authority.getString("executor_peer_id"))
                || !routeConfigurationSha256.equals(authority.getString("route_configuration_sha256"))
                || authority.getLong("expires_at_ms") <= System.currentTimeMillis()
                || !ticket.authorityEpochId().equals(authority.getString("authority_provider_epoch_id"))
                || !ticket.clientId().equals(authority.getString("authority_client_id"))
                || !ticket.leaseId().equals(authority.getString("authority_runtime_lease_id"))) {
            throw new IllegalStateException("platform projection binding rejected");
        }
        PackagedAndroidMediaOwnerRegistry current = registry;
        if (current == null) throw new IllegalStateException("platform registry absent");
        EmbeddedDuplexActivationGate gate = activationGate;
        EmbeddedDuplexActivationGate.MediaTicket activationTicket = activationTicket(ticket);
        if (gate != null) gate.beforeOwnerEffect(authority, activationTicket, compensate);
        if (!compensate && "source".equals(ticket.ownerKind()) && "start".equals(ticket.actionKind())) {
            display.ensureLocalCaptureStopped();
            if (!EmbeddedDuplexNative.localCameraQuiescent()) {
                throw new IllegalStateException("local Camera2 ownership remains live");
            }
        }
        String readback = current.execute(ticketJson, compensate);
        String verified = current.verifyAndReadEvidence(ticketJson, readback);
        if (verified == null) throw new IllegalStateException("live provider evidence rejected");
        if (gate != null) {
            gate.afterVerifiedOwnerEffect(authority, activationTicket,
                    new JSONObject(readback), new JSONObject(verified), compensate);
        }
        JSONObject result = new JSONObject();
        result.put("readback", new JSONObject(readback));
        result.put("readback_json", readback);
        result.put("verified", new JSONObject(verified));
        return result.toString();
    }

    // Public visibility is required by the native ProductActivationRegistry callback.
    public String activateProduct(String activationId, String authorityJson, String proofJson)
            throws Exception {
        EmbeddedDuplexActivationGate gate = activationGate;
        if (gate == null || resources == null) throw new IllegalStateException("resources absent");
        return gate.activate(activationId, authorityJson, proofJson);
    }

    public byte[] signAuthorityBytes(byte[] exactNativeBytes) throws Exception {
        return EmbeddedDuplexIdentity.signExactAuthorityBytes(identity, exactNativeBytes);
    }

    // Native calls this only after current route, enrollment, endpoint and time
    // validation. The generic dispatch signer cannot sign this domain.
    public byte[] signValidatedCommonLanBytes(byte[] validatedNativeBytes) throws Exception {
        return EmbeddedDuplexIdentity.signValidatedCommonLanBytes(identity, validatedNativeBytes);
    }

    public byte[] exchangeOwnerFrame(String targetPeerId, byte[] exactFrame) throws Exception {
        if (!remotePeerId.equals(targetPeerId) || !controlReady()) {
            throw new IllegalStateException("owner control target unavailable");
        }
        return OwnerDispatchTcpEndpoint.exchange(remoteControlAddress, remoteControlPort, exactFrame);
    }

    public synchronized void persistDispatchReplay(String snapshotJson) throws Exception {
        persistReplay(replayFile, snapshotJson);
    }

    public synchronized void persistActivationReplay(String snapshotJson) throws Exception {
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
