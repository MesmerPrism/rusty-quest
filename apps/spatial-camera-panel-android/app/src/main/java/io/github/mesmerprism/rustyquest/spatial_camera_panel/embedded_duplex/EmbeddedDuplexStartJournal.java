package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Private write-ahead transaction marker. It is never a media acceptance receipt. */
final class EmbeddedDuplexStartJournal {
    static final String SCHEMA = "rusty.quest.embedded_duplex.start_checkpoint.v1";
    private static final int MAX_BYTES = 256 * 1024;
    private static final Set<String> FIELDS = Set.of("$schema", "phase", "revision",
            "lineage_sha256", "last_verified_receipt_sha256");
    private static final Set<String> PHASES = Set.of("prepared", "admission_attempted",
            "admitted", "decision_attempted", "pending_start", "route_attempted",
            "route_current", "owner_attempted", "active", "abort_attempted",
            "stop_attempted", "cleanup_pending", "terminal");

    private final File directory;
    private final AtomicFile file;

    EmbeddedDuplexStartJournal(Context context) throws Exception {
        directory = new File(context.getNoBackupFilesDir(), "embedded-duplex-replay");
        rejectLink(directory);
        if (!directory.isDirectory() && !directory.mkdir()) {
            throw new IllegalStateException("duplex journal directory unavailable");
        }
        Os.chmod(directory.getAbsolutePath(), 0700);
        File base = new File(directory, "start-checkpoint.v1.json");
        rejectLink(base);
        rejectLink(new File(base.getPath() + ".new"));
        rejectLink(new File(base.getPath() + ".bak"));
        file = new AtomicFile(base);
    }

    synchronized boolean unresolved() throws Exception {
        String checkpoint = read();
        return checkpoint != null && !"terminal".equals(phase(checkpoint));
    }

    synchronized String read() throws Exception {
        File base = file.getBaseFile();
        if (!base.exists() && !new File(base.getPath() + ".bak").exists()) return null;
        try (FileInputStream input = file.openRead()) {
            long size = input.getChannel().size();
            if (size <= 0 || size > MAX_BYTES) throw new IllegalStateException("duplex journal bounds");
            byte[] bytes = new byte[(int) size];
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IllegalStateException("duplex journal truncated");
                offset += count;
            }
            if (input.read() != -1) throw new IllegalStateException("duplex journal grew");
            String result = new String(bytes, StandardCharsets.UTF_8);
            phase(result);
            return result;
        }
    }

    synchronized void persist(String exactCheckpointJson) throws Exception {
        phase(exactCheckpointJson);
        byte[] bytes = exactCheckpointJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("duplex journal bounds");
        }
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

    static String phase(String exactCheckpointJson) throws Exception {
        if (exactCheckpointJson == null) throw new IllegalArgumentException("duplex journal absent");
        JSONObject object = new JSONObject(exactCheckpointJson);
        Set<String> keys = new HashSet<>();
        for (java.util.Iterator<String> iterator = object.keys(); iterator.hasNext();) {
            keys.add(iterator.next());
        }
        Object revision = object.opt("revision");
        if (!keys.equals(FIELDS) || !SCHEMA.equals(object.getString("$schema"))
                || !PHASES.contains(object.getString("phase"))
                || !(revision instanceof Number) || object.getLong("revision") <= 0
                || !sha256(object.getString("lineage_sha256"))) {
            throw new IllegalStateException("duplex journal shape invalid");
        }
        Object receipt = object.get("last_verified_receipt_sha256");
        if (receipt != JSONObject.NULL && (!(receipt instanceof String)
                || !sha256((String) receipt))) {
            throw new IllegalStateException("duplex journal receipt invalid");
        }
        if ("terminal".equals(object.getString("phase")) && receipt == JSONObject.NULL) {
            throw new IllegalStateException("duplex terminal receipt absent");
        }
        return object.getString("phase");
    }

    private static boolean sha256(String value) { return value.matches("[0-9a-f]{64}"); }

    private static void rejectLink(File path) {
        if (Files.isSymbolicLink(path.toPath())
                || (Files.exists(path.toPath(), LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalStateException("duplex journal path type");
        }
    }
}
