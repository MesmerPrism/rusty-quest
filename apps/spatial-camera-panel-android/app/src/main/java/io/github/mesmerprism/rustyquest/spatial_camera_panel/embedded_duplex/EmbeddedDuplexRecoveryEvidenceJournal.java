package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** App-private exact source snapshots for cleanup-only restoration. This file
 * never authorizes Start and is not a media acceptance receipt. */
final class EmbeddedDuplexRecoveryEvidenceJournal {
    static final String SCHEMA = "rusty.quest.embedded_duplex.recovery_evidence.v1";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FIELD_BYTES = 2 * 1024 * 1024;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "$schema", "revision", "provider_epoch_id", "lineage_sha256", "snapshots");
    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            "broker_adapter", "broker_runtime", "peer_runtime", "media_product",
            "owner_progress", "owner_dispatch_replay", "product_activation_replay",
            "request_receipt_ledger");
    private static final Set<String> EXACT_FIELD = Set.of("json", "sha256");

    private final File directory;
    private final AtomicFile file;

    EmbeddedDuplexRecoveryEvidenceJournal(Context context) throws Exception {
        directory = new File(context.getNoBackupFilesDir(), "embedded-duplex-replay");
        rejectLink(directory);
        if (!directory.isDirectory() && !directory.mkdir()) {
            throw new IllegalStateException("recovery evidence directory unavailable");
        }
        Os.chmod(directory.getAbsolutePath(), 0700);
        File base = new File(directory, "recovery-evidence.v1.json");
        rejectLink(base);
        rejectLink(new File(base.getPath() + ".new"));
        rejectLink(new File(base.getPath() + ".bak"));
        file = new AtomicFile(base);
    }

    synchronized String readValidated() throws Exception {
        File base = file.getBaseFile();
        if (!base.exists() && !new File(base.getPath() + ".bak").exists()) return null;
        try (FileInputStream input = file.openRead()) {
            long size = input.getChannel().size();
            if (size <= 0 || size > MAX_BYTES) throw new IllegalStateException("recovery evidence bounds");
            byte[] bytes = new byte[(int) size];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IllegalStateException("recovery evidence truncated");
                offset += count;
            }
            if (input.read() != -1) throw new IllegalStateException("recovery evidence grew");
            String exact = decodeExact(bytes);
            validate(exact);
            return exact;
        }
    }

    synchronized void persist(String exactJson) throws Exception {
        validate(exactJson);
        byte[] bytes = exactJson.getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            Os.fchmod(output.getFD(), 0600);
            output.write(bytes);
            output.getFD().sync();
            file.finishWrite(output);
            output = null;
            FileDescriptor descriptor = Os.open(directory.getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
            try { Os.fsync(descriptor); } finally { Os.close(descriptor); }
        } finally {
            if (output != null) file.failWrite(output);
        }
    }

    static void validate(String exactJson) throws Exception {
        if (exactJson == null || exactJson.isEmpty()
                || exactJson.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalStateException("recovery evidence bounds");
        }
        JSONObject root = new JSONObject(exactJson);
        if (!keys(root).equals(ROOT_FIELDS) || !SCHEMA.equals(root.getString("$schema"))
                || !(root.get("revision") instanceof Number) || root.getLong("revision") <= 0
                || root.getString("provider_epoch_id").isEmpty()
                || !hexDigest(root.getString("lineage_sha256"))) {
            throw new IllegalStateException("recovery evidence root invalid");
        }
        JSONObject snapshots = root.getJSONObject("snapshots");
        if (!keys(snapshots).equals(SNAPSHOT_FIELDS)) {
            throw new IllegalStateException("recovery evidence snapshot set invalid");
        }
        for (String field : SNAPSHOT_FIELDS) {
            JSONObject item = snapshots.getJSONObject(field);
            if (!keys(item).equals(EXACT_FIELD)) {
                throw new IllegalStateException("recovery evidence field shape invalid");
            }
            String value = item.getString("json");
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_FIELD_BYTES
                    || !hexDigest(item.getString("sha256"))
                    || !sha256(bytes).equals(item.getString("sha256"))) {
                throw new IllegalStateException("recovery evidence field digest invalid");
            }
            new JSONObject(value);
        }
    }

    static String decodeExact(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** Checks the source bundle against the independently retained phase marker
     * before native restore is attempted. The native owner must still verify
     * every Broker, peer, media and native-effect join. */
    static void validateAgainstCheckpoint(String exactCheckpointJson, String exactEvidenceJson,
            String expectedProviderEpochId) throws Exception {
        String phase = EmbeddedDuplexStartJournal.phase(exactCheckpointJson);
        validate(exactEvidenceJson);
        JSONObject checkpoint = new JSONObject(exactCheckpointJson);
        JSONObject evidence = new JSONObject(exactEvidenceJson);
        if ("terminal".equals(phase) || expectedProviderEpochId == null
                || expectedProviderEpochId.isEmpty()
                || !expectedProviderEpochId.equals(evidence.getString("provider_epoch_id"))
                || !checkpoint.getString("lineage_sha256")
                        .equals(evidence.getString("lineage_sha256"))
                || evidence.getLong("revision") != checkpoint.getLong("revision")) {
            throw new IllegalStateException("recovery evidence checkpoint join differs");
        }
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> keys = new HashSet<>();
        for (java.util.Iterator<String> iterator = object.keys(); iterator.hasNext();) {
            keys.add(iterator.next());
        }
        return keys;
    }

    private static boolean hexDigest(String value) { return value.matches("[0-9a-f]{64}"); }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder(64);
        for (byte item : digest) {
            text.append(Character.forDigit((item >>> 4) & 15, 16));
            text.append(Character.forDigit(item & 15, 16));
        }
        return text.toString();
    }

    private static void rejectLink(File path) {
        if (Files.isSymbolicLink(path.toPath())
                || (Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalStateException("recovery evidence path type");
        }
    }
}
