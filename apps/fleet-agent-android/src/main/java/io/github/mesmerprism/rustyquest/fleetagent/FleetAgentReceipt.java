package io.github.mesmerprism.rustyquest.fleetagent;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class FleetAgentReceipt {
    private FleetAgentReceipt() {
    }

    static synchronized void write(
            Context context,
            String status,
            long sourceRevision,
            String sourceEpoch,
            long attemptedAtMs,
            Integer httpStatus,
            String detail) {
        try {
            File directory = new File(context.getFilesDir(), "fleet-agent");
            if (!directory.exists() && !directory.mkdirs()) {
                return;
            }
            JSONObject receipt = new JSONObject();
            receipt.put("schema", "rusty.quest.fleet_agent_receipt.v1");
            receipt.put("status", status);
            receipt.put("source_revision", sourceRevision);
            receipt.put("source_epoch", sourceEpoch);
            receipt.put("attempted_at_ms", attemptedAtMs);
            receipt.put("http_status", httpStatus == null ? JSONObject.NULL : httpStatus);
            receipt.put("detail", detail);
            receipt.put("offline_queue_depth", 0);
            File temporary = new File(directory, "last-receipt.json.tmp");
            File target = new File(directory, "last-receipt.json");
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(receipt.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            try {
                Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | JSONException ignored) {
            // Receipt failure is reflected by the absence of an acceptance artifact.
        }
    }
}
