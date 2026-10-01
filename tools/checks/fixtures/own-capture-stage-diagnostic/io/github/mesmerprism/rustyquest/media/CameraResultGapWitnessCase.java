package io.github.mesmerprism.rustyquest.media;

import org.json.JSONObject;

/** Runs the production diagnostic primitive; no camera, device, or source publisher is faked. */
public final class CameraResultGapWitnessCase {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JSONObject snapshot(PackedStereoCaptureOwner.CameraResultCadence stage,
            long sampleNs) throws Exception {
        return stage.snapshot(sampleNs);
    }

    private static JSONObject worst(JSONObject stage) {
        return stage.getJSONObject("worst_gap");
    }

    private static void checkSameLegacy(JSONObject stage, long count, long lastNs,
            long gapNs, long ageNs, long identity) {
        require(stage.getLong("count") == count
                && stage.getLong("last_elapsed_ns") == lastNs
                && stage.getLong("max_gap_ns") == gapNs
                && stage.getLong("age_ns") == ageNs
                && stage.getLong("last_identity") == identity,
                "legacy camera cadence fields changed");
    }

    public static void main(String[] args) throws Exception {
        PackedStereoCaptureOwner.CameraResultCadence delivery =
                new PackedStereoCaptureOwner.CameraResultCadence();
        JSONObject empty = snapshot(delivery, 1_000L);
        checkSameLegacy(empty, 0L, 0L, 0L, -1L, 0L);
        require(!worst(empty).getBoolean("present")
                && worst(empty).getLong("from_sensor_timestamp_ns") == 0L,
                "no callback may invent a bracket");
        delivery.observeAt(1_000_000_000L, 11L, 10L, 4_000_000_000L);
        JSONObject first = snapshot(delivery, 1_050_000_000L);
        checkSameLegacy(first, 1L, 1_000_000_000L, 0L, 50_000_000L, 11L);
        require(!worst(first).getBoolean("present"), "first callback is not a gap");
        delivery.observeAt(1_980_000_000L, 12L, 11L, 4_020_000_000L);
        JSONObject backlog = snapshot(delivery, 2_000_000_000L);
        checkSameLegacy(backlog, 2L, 1_980_000_000L, 980_000_000L, 20_000_000L, 12L);
        JSONObject bracket = worst(backlog);
        require(bracket.getBoolean("present")
                && bracket.getLong("from_callback_elapsed_ns") == 1_000_000_000L
                && bracket.getLong("to_callback_elapsed_ns") == 1_980_000_000L
                && bracket.getLong("from_frame_number") == 10L
                && bracket.getLong("to_frame_number") == 11L
                && bracket.getBoolean("from_sensor_timestamp_present")
                && bracket.getBoolean("to_sensor_timestamp_present")
                && bracket.getLong("from_sensor_timestamp_ns") == 4_000_000_000L
                && bracket.getLong("to_sensor_timestamp_ns") == 4_020_000_000L,
                "callback backlog must preserve actual adjacent sensor evidence");

        delivery.observeAt(1_500_000_000L, 13L, 12L, 4_040_000_000L);
        checkSameLegacy(snapshot(delivery, 2_000_000_000L), 3L,
                1_980_000_000L, 980_000_000L, 20_000_000L, 12L);
        delivery.observeAt(2_960_000_000L, 14L, 13L, 4_060_000_000L);
        JSONObject tied = worst(snapshot(delivery, 3_000_000_000L));
        require(tied.getLong("from_frame_number") == 10L
                && tied.getLong("to_frame_number") == 11L,
                "equal worst gap keeps its first exact bracket");
        delivery.observeAt(-1L, 15L, 14L, 4_080_000_000L);
        delivery.observeAt(3_100_000_000L, -1L, 15L, 4_100_000_000L);
        checkSameLegacy(snapshot(delivery, 2_000_000_000L), 4L,
                2_960_000_000L, 980_000_000L, -1L, 14L);

        PackedStereoCaptureOwner.CameraResultCadence sensorPause =
                new PackedStereoCaptureOwner.CameraResultCadence();
        sensorPause.observeAt(100L, 51L, 50L, 10_000L);
        sensorPause.observeAt(1_000_000_100L, 52L, 51L, 1_000_010_000L);
        JSONObject sensorBracket = worst(snapshot(sensorPause, 1_000_000_101L));
        require(sensorBracket.getLong("to_frame_number") - sensorBracket.getLong("from_frame_number") == 1L
                && sensorBracket.getLong("to_sensor_timestamp_ns")
                        - sensorBracket.getLong("from_sensor_timestamp_ns") == 1_000_000_000L,
                "sensor pause must remain distinguishable from callback backlog");

        PackedStereoCaptureOwner.CameraResultCadence skipped =
                new PackedStereoCaptureOwner.CameraResultCadence();
        skipped.observeAt(100L, 51L, 50L, 10_000L);
        skipped.observeAt(1_000_000_100L, 101L, 100L, 1_000_010_000L);
        JSONObject skippedBracket = worst(snapshot(skipped, 1_000_000_101L));
        require(skippedBracket.getLong("to_frame_number")
                - skippedBracket.getLong("from_frame_number") == 50L,
                "frame-number jump must not be inferred from callback count");

        PackedStereoCaptureOwner.CameraResultCadence missingSensor =
                new PackedStereoCaptureOwner.CameraResultCadence();
        missingSensor.observeAt(100L, 1L, 0L, null);
        missingSensor.observeAt(300L, 2L, 1L, 900L);
        JSONObject missingBracket = worst(snapshot(missingSensor, 300L));
        require(!missingBracket.getBoolean("from_sensor_timestamp_present")
                && missingBracket.getLong("from_sensor_timestamp_ns") == 0L
                && missingBracket.getBoolean("to_sensor_timestamp_present")
                && missingBracket.getLong("to_sensor_timestamp_ns") == 900L,
                "missing sensor timestamp requires explicit absence and zero value");

        final PackedStereoCaptureOwner.CameraResultCadence concurrent =
                new PackedStereoCaptureOwner.CameraResultCadence();
        Thread writer = new Thread(() -> {
            for (int i = 1; i <= 10_000; i++)
                concurrent.observeAt(i * 20_000_000L, i, i - 1L, i * 20_000_000L);
        });
        writer.start();
        while (writer.isAlive()) {
            JSONObject observed = snapshot(concurrent, 200_000_000_000L);
            JSONObject witness = worst(observed);
            if (witness.getBoolean("present")) {
                require(observed.getLong("max_gap_ns")
                        == witness.getLong("to_callback_elapsed_ns")
                                - witness.getLong("from_callback_elapsed_ns"),
                        "snapshot must not expose a partial worst-gap bracket");
            }
        }
        writer.join();
        require(snapshot(concurrent, 200_000_000_000L).getLong("count") == 10_000L,
                "concurrent writer must complete");
        System.out.println("camera-result-gap-witness PASS");
    }
}
