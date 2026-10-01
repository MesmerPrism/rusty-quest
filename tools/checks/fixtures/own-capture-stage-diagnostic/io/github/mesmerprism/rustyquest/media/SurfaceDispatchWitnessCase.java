package io.github.mesmerprism.rustyquest.media;

import org.json.JSONObject;

/** Exercises the exact production SurfaceTexture and texture-update counters. */
public final class SurfaceDispatchWitnessCase {
    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        PackedStereoGlCompositor.SurfaceCallbackCadence surface =
                new PackedStereoGlCompositor.SurfaceCallbackCadence();
        JSONObject empty = surface.snapshot(100L);
        require(empty.getLong("count") == 0L && empty.getLong("max_gap_ns") == 0L
                && !empty.getJSONObject("worst_gap").getBoolean("present"),
                "empty SurfaceTexture callback stream has no invented witness");
        surface.observeCallbackAt(100L, 120L, 11L, true);
        surface.observeCallbackAt(1_000L, 1_040L, 12L, false);
        JSONObject first = surface.snapshot(1_050L);
        JSONObject bracket = first.getJSONObject("worst_gap");
        require(first.getLong("count") == 2L && first.getLong("max_gap_ns") == 920L
                && first.getLong("last_elapsed_ns") == 1_040L
                && first.getLong("max_callback_gap_ns") == 900L
                && first.getLong("last_callback_elapsed_ns") == 1_000L
                && bracket.getBoolean("present")
                && bracket.getLong("from_callback_elapsed_ns") == 100L
                && bracket.getLong("to_callback_elapsed_ns") == 1_000L
                && bracket.getLong("from_thread_id") == 11L
                && bracket.getLong("to_thread_id") == 12L
                && bracket.getBoolean("from_on_main_looper")
                && !bracket.getBoolean("to_on_main_looper"),
                "callback bracket must retain both actual dispatch threads");
        surface.observeCallbackAt(500L, 1_100L, 99L, true);
        surface.observeCallbackAt(1_900L, 1_940L, 13L, true);
        JSONObject tied = surface.snapshot(2_000L);
        require(tied.getLong("count") == 4L
                && tied.getLong("last_elapsed_ns") == 1_940L
                && tied.getLong("max_gap_ns") == 920L
                && tied.getLong("max_callback_gap_ns") == 900L
                && tied.getJSONObject("worst_gap").getLong("from_thread_id") == 11L,
                "legacy in-lock cadence and regressed/tied arrival bracket stay distinct");
        surface.observeCallbackAt(3_000L, 3_050L, 14L, false);
        JSONObject replaced = surface.snapshot(3_100L);
        require(replaced.getLong("max_gap_ns") == 1_110L
                && replaced.getLong("max_callback_gap_ns") == 1_100L
                && replaced.getJSONObject("worst_gap").getLong("from_thread_id") == 13L
                && replaced.getJSONObject("worst_gap").getLong("to_thread_id") == 14L,
                "strictly larger callback gap replaces exact bracket");

        PackedStereoGlCompositor.UpdateDurationPeak update =
                new PackedStereoGlCompositor.UpdateDurationPeak();
        update.observeAt(0L, 100L);
        update.observeAt(200L, 100L);
        require(update.snapshot().getLong("count") == 0L,
                "invalid clock samples must not invent a texture update");
        update.observeAt(100L, 300L);
        update.observeAt(400L, 600L);
        JSONObject tiedDuration = update.snapshot();
        require(tiedDuration.getLong("count") == 2L
                && tiedDuration.getLong("max_duration_ns") == 200L
                && tiedDuration.getLong("max_entry_elapsed_ns") == 100L,
                "equal successful duration retains first exact operation");
        update.observeAt(700L, 1_100L);
        JSONObject peak = update.snapshot();
        require(peak.getLong("count") == 3L && peak.getLong("max_duration_ns") == 400L
                && peak.getLong("max_entry_elapsed_ns") == 700L
                && peak.getLong("max_exit_elapsed_ns") == 1_100L,
                "texture-update peak uses actual entry and exit only");
        System.out.println("surface-dispatch-witness PASS");
    }
}
