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
        delivery.observeAt(1_000_000_000L, 11L, 10L,
                4_000_000_000L, 10_000_000L, 20_000_000L);
        JSONObject first = snapshot(delivery, 1_050_000_000L);
        checkSameLegacy(first, 1L, 1_000_000_000L, 0L, 50_000_000L, 11L);
        require(!worst(first).getBoolean("present"), "first callback is not a gap");
        delivery.observeAt(1_980_000_000L, 12L, 11L,
                4_020_000_000L, 11_000_000L, 21_000_000L);
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
                && bracket.getLong("to_sensor_timestamp_ns") == 4_020_000_000L
                && !bracket.getBoolean("successor_present")
                && bracket.getLong("successor_callback_elapsed_ns") == 0L
                && bracket.getBoolean("from_exposure_time_present")
                && bracket.getLong("from_exposure_time_ns") == 10_000_000L
                && bracket.getLong("to_frame_duration_ns") == 21_000_000L,
                "callback backlog must preserve actual adjacent sensor evidence");

        delivery.observeAt(1_500_000_000L, 13L, 12L, 4_040_000_000L);
        checkSameLegacy(snapshot(delivery, 2_000_000_000L), 3L,
                1_980_000_000L, 980_000_000L, 20_000_000L, 12L);
        delivery.observeAt(2_960_000_000L, 14L, 13L,
                4_060_000_000L, -1L, null);
        JSONObject tied = worst(snapshot(delivery, 3_000_000_000L));
        require(tied.getLong("from_frame_number") == 10L
                && tied.getLong("to_frame_number") == 11L
                && tied.getBoolean("successor_present")
                && tied.getLong("successor_frame_number") == 13L
                && !tied.getBoolean("successor_exposure_time_present")
                && tied.getLong("successor_exposure_time_ns") == 0L
                && !tied.getBoolean("successor_frame_duration_present"),
                "equal worst gap keeps its first exact bracket");
        delivery.observeAt(-1L, 15L, 14L, 4_080_000_000L);
        delivery.observeAt(3_100_000_000L, -1L, 15L, 4_100_000_000L);
        checkSameLegacy(snapshot(delivery, 2_000_000_000L), 4L,
                2_960_000_000L, 980_000_000L, -1L, 14L);
        JSONObject sensorGap = snapshot(delivery, 3_000_000_000L)
                .getJSONObject("max_adjacent_sensor_gap");
        require(sensorGap.getBoolean("present") && sensorGap.getLong("gap_ns") == 40_000_000L
                && sensorGap.getLong("from_frame_number") == 11L
                && sensorGap.getLong("to_frame_number") == 13L
                && sensorGap.getBoolean("from_exposure_time_present")
                && sensorGap.getLong("from_exposure_time_ns") == 11_000_000L,
                "discarded backward host sample leaves an explicit frame jump in the sensor bracket");

        delivery.observeAt(4_000_000_000L, 16L, 15L,
                5_000_000_000L, null, 0L);
        JSONObject replaced = worst(snapshot(delivery, 4_000_000_010L));
        require(replaced.getLong("from_frame_number") == 13L
                && replaced.getLong("to_frame_number") == 15L
                && !replaced.getBoolean("successor_present")
                && replaced.getLong("successor_frame_number") == 0L
                && !replaced.getBoolean("to_exposure_time_present")
                && !replaced.getBoolean("to_frame_duration_present"),
                "strictly larger callback gap replaces and clears old successor");
        delivery.observeAt(4_020_000_000L, 17L, 16L,
                5_020_000_000L, 12_000_000L, 25_000_000L);
        JSONObject successor = worst(snapshot(delivery, 4_020_000_001L));
        require(successor.getBoolean("successor_present")
                && successor.getLong("successor_frame_number") == 16L
                && successor.getLong("successor_sensor_timestamp_ns") == 5_020_000_000L
                && successor.getLong("successor_exposure_time_ns") == 12_000_000L
                && successor.getLong("successor_frame_duration_ns") == 25_000_000L,
                "first valid successor must be exact and retained");

        PackedStereoCaptureOwner.CameraResultCadence sensorPause =
                new PackedStereoCaptureOwner.CameraResultCadence();
        sensorPause.observeAt(100L, 51L, 50L, 10_000L);
        sensorPause.observeAt(1_000_000_100L, 52L, 51L, 1_000_010_000L);
        JSONObject sensorBracket = worst(snapshot(sensorPause, 1_000_000_101L));
        require(sensorBracket.getLong("to_frame_number") - sensorBracket.getLong("from_frame_number") == 1L
                && sensorBracket.getLong("to_sensor_timestamp_ns")
                        - sensorBracket.getLong("from_sensor_timestamp_ns") == 1_000_000_000L,
                "sensor pause must remain distinguishable from callback backlog");
        require(snapshot(sensorPause, 1_000_000_101L)
                        .getJSONObject("max_adjacent_sensor_gap").getLong("gap_ns") == 1_000_000_000L,
                "independent sensor peak must catch a true adjacent sensor hiatus");

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
        require(!snapshot(missingSensor, 300L)
                        .getJSONObject("max_adjacent_sensor_gap").getBoolean("present"),
                "a missing predecessor sensor cannot create a sensor interval");
        missingSensor.observeAt(400L, 3L, 2L, 800L);
        require(!snapshot(missingSensor, 400L)
                        .getJSONObject("max_adjacent_sensor_gap").getBoolean("present"),
                "a regressed sensor timestamp cannot create a positive interval");

        PackedStereoCaptureOwner.CameraResultCadence started =
                new PackedStereoCaptureOwner.CameraResultCadence();
        started.observeAt(100L, 101L, 100L, 1_000L);
        started.observeAt(1_000_000_100L, 102L, 101L, 1_000_001_000L);
        require(worst(snapshot(started, 1_000_000_101L)).getLong("to_frame_number") == 101L,
                "primitive onCaptureStarted timestamp uses production cadence without boxing");
        PackedStereoCaptureOwner.CameraFailureCadence failures =
                new PackedStereoCaptureOwner.CameraFailureCadence();
        failures.observeAt(500L, 88L, 1);
        failures.observeAt(400L, 89L, 0);
        JSONObject failure = failures.snapshot();
        require(failure.getLong("count") == 2L
                && failure.getLong("last_frame_number") == 88L
                && failure.getLong("last_reason") == 1L,
                "regressed failure callback cannot overwrite last fixed reason");

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
