package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.os.Handler
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** Actual hotloader, with only Android filesystem/event-loop leaves modeled by the host runner. */
fun main(args: Array<String>) {
  val directory = File(args.single(), "control-profiles").apply { mkdirs() }
  val active = File(directory, "active.profile.json")
  SpatialCameraControlProfileHotloader(Context(File(args.single())), { false },
      { _, _ -> error("unarmed_visual_callback") }, {}).close()
  check(!File(directory, "module-watch-receipt.json").exists()) // Disabled launches stay inert.
  val fixture = SpatialCameraControlProfileTest::class.java.getDeclaredMethod("validProfile").apply { isAccessible = true }
  fun profile() = fixture.invoke(SpatialCameraControlProfileTest()) as JSONObject
  fun directive(mode: String) = JSONObject().put("mode", mode).also {
    if (mode == "activate" || mode == "select_retained") it.put("generation", 1)
    if (mode == "activate") it.put("sha256", "a".repeat(64))
  }
  var clock = System.currentTimeMillis()
  fun publish(json: JSONObject) {
    val temporary = File(directory, "incoming.json")
    temporary.writeText(json.toString())
    check(temporary.setLastModified(++clock))
    Files.move(temporary.toPath(), active.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }
  var visualCalls = 0
  var refuseContext = false
  val pending = mutableListOf<CompletableFuture<String>>()
  val hotloader = SpatialCameraControlProfileHotloader(Context(File(args.single())), { true }, { p, _ ->
    visualCalls++
    SpatialCameraControlProfileEffective(p.layerOverride, p.projectionScale, p.zoneCompositor,
        p.rgbChannelTransform, p.projectionSurfaceDisplacement, p.projectionSurfaceTiling,
        p.projectionInnerAlpha, p.strengthCycleSpeedHz)
  }, {}, validateCandidate = { check(!refuseContext) },
      effectiveInputObservation = { "{\"phase\":\"frozen\",\"test_owner\":true}" },
      applyObservationModule = { _, _ -> CompletableFuture<String>().also { pending.add(it) } })
  publish(profile())
  hotloader.arm()
  hotloader.poll(true)
  check(visualCalls == 0) // Existing file is not replayed on arming.
  publish(profile().put("development_observation_module", directive("unknown")))
  hotloader.poll(true)
  check(visualCalls == 0 && pending.isEmpty())
  refuseContext = true
  publish(profile().put("development_observation_module", directive("activate")))
  hotloader.poll(true)
  check(visualCalls == 0 && pending.isEmpty())
  refuseContext = false
  val a = profile().put("development_observation_module", directive("activate"))
  publish(a); hotloader.poll(true)
  publish(profile().put("development_observation_module", directive("status"))); hotloader.poll(true)
  publish(a); hotloader.poll(true)
  check(visualCalls == 3 && pending.size == 3)
  val visual = JSONObject(File(directory, "last-apply-receipt.json").readText())
  check(visual.getString("applied_scope") == "visual_controls")
  check(visual.getJSONObject("effective_input_owner").getBoolean("test_owner"))
  pending[0].complete("{\"actual\":\"old-a\"}")
  pending[1].complete("{\"actual\":\"b\"}")
  pending[2].complete("{\"actual\":\"current-a\"}")
  Handler.drain()
  val receiptFile = File(directory, "last-development-observation-module-receipt.json")
  val adopted = JSONObject(receiptFile.readText())
  check(adopted.getString("status") == "observed")
  check(adopted.getJSONObject("consumer_receipt").getString("actual") == "current-a")
  publish(profile()); hotloader.poll(true)
  check(pending.size == 3 && visualCalls == 4) // Omission does not issue a disable.
  publish(profile().put("development_scissor_module", directive("status"))); hotloader.poll(true)
  check(JSONObject(File(directory, "last-development-scissor-module-receipt.json").readText()).getString("status") == "unavailable")
  publish(a); hotloader.poll(true)
  hotloader.close()
  val beforeClose = receiptFile.readBytes()
  check(JSONObject(String(beforeClose)).getString("status") == "retired")
  pending.last().complete("{\"actual\":\"after-close\"}")
  Handler.drain()
  check(beforeClose.contentEquals(receiptFile.readBytes()))
  // Module-only delivery must work without a native visual lifecycle or visual callbacks.
  active.delete()
  val modules = File(directory, "active.modules.json")
  var nativeReady = false
  val moduleCalls = mutableListOf<String>()
  val scissorCalls = mutableListOf<String>()
  val isolated = SpatialCameraControlProfileHotloader(Context(File(args.single())), { nativeReady },
      { _, _ -> error("module_only_must_not_apply_visuals") }, {},
      validateCandidate = { error("module_only_must_not_validate_visual_context") },
      applyObservationModule = { d, _ -> moduleCalls.add(d.mode); CompletableFuture.completedFuture("{\"outcome\":\"accepted\"}") },
      applyScissorModule = { d, _ -> scissorCalls.add(d.mode); CompletableFuture.completedFuture("{\"outcome\":\"accepted\",\"renderer_calls\":0}") })
  fun envelope(mode: String) = JSONObject().put("schema", "rusty.quest.spatial_camera_panel.development_modules.v1")
      .put("profile_id", "module-host").put("revision", 41)
      .put("development_observation_module", directive(mode))
  fun publishModules(raw: String) {
    modules.writeText(raw); check(modules.setLastModified(++clock)); isolated.poll(true); Handler.drain()
  }
  isolated.arm()
  publishModules(envelope("status").toString())
  check(moduleCalls == listOf("status")) { "module-only delivery remained behind visual readiness" }
  isolated.poll(true); Handler.drain()
  check(moduleCalls.size == 1) // Pending/unchanged publications are not replayed.
  publishModules(envelope("activate").toString())
  publishModules(envelope("static").toString())
  check(moduleCalls == listOf("status", "activate", "static"))
  for (bad in listOf(
      envelope("status").put("quest_controls", JSONObject()).toString(),
      envelope("status").put("peer_network_endpoints", org.json.JSONArray()).toString(),
      envelope("unknown").toString(),
      envelope("status").toString().replace("\"revision\":41", "\"revision\":41,\"revision\":42"),
      " ".repeat(65537))) {
    publishModules(bad)
  }
  check(moduleCalls.size == 3)
  for (mode in listOf("status", "static", "activate", "select_retained")) {
    val scissor = directive(mode).also { if (mode == "activate") it.put("policy", "product") }
    val json = envelope("status").apply { remove("development_observation_module") }
        .put("development_scissor_module", scissor)
    publishModules(json.toString())
  }
  check(scissorCalls == listOf("status", "static", "activate", "select_retained") && !nativeReady)
  isolated.close()
  modules.delete()
  var combinedVisualCalls = 0
  var publishConflictDuringVisual = false
  val conflicts = SpatialCameraControlProfileHotloader(Context(File(args.single())), { nativeReady },
      { p, _ -> combinedVisualCalls++
        if (publishConflictDuringVisual) { modules.writeText(envelope("static").toString()); modules.setLastModified(++clock) }
        SpatialCameraControlProfileEffective(p.layerOverride,
          p.projectionScale, p.zoneCompositor, p.rgbChannelTransform, p.projectionSurfaceDisplacement,
          p.projectionSurfaceTiling, p.projectionInnerAlpha, p.strengthCycleSpeedHz) }, {},
      applyObservationModule = { d, _ -> moduleCalls.add(d.mode); CompletableFuture.completedFuture("{\"outcome\":\"accepted\"}") })
  conflicts.arm()
  val conflictWatcher = JSONObject(File(directory, "module-watch-receipt.json").readText()).getString("watcher_id")
  publish(profile().put("development_observation_module", directive("activate")))
  modules.writeText(envelope("static").toString()); modules.setLastModified(++clock)
  conflicts.poll(true); Handler.drain()
  check(moduleCalls.size == 3 && combinedVisualCalls == 0)
  check(JSONObject(receiptFile.readText()).getString("error_code") == "competing_profile_directive")
  modules.delete(); nativeReady = true
  conflicts.poll(true); Handler.drain()
  check(moduleCalls.size == 3 && combinedVisualCalls == 1) // Pending visuals may apply; retired modules cannot replay.
  publish(profile().put("development_observation_module", directive("status")))
  conflicts.poll(true); Handler.drain()
  check(moduleCalls.size == 4)
  modules.writeText(envelope("static").toString()); modules.setLastModified(++clock)
  conflicts.poll(true); Handler.drain()
  check(moduleCalls.size == 4) // The accepted legacy source does not grant the other file precedence.
  active.delete(); conflicts.poll(true); Handler.drain()
  check(moduleCalls.size == 4) // Removing the legacy request does not replay the rejected module file.
  modules.setLastModified(++clock); conflicts.poll(true); Handler.drain()
  check(moduleCalls == listOf("status", "activate", "static", "status", "static"))
  val bound = JSONObject(receiptFile.readText())
  check(bound.getString("watcher_id") == conflictWatcher && bound.getInt("owner_process_pid") == 123)
  check(bound.getString("profile_id") == "module-host" && bound.getLong("revision") == 41L)
  check(bound.getString("source_file") == "active.modules.json" && bound.getString("phase") == "consumer_readback")
  modules.delete(); conflicts.poll(true)
  publishConflictDuringVisual = true
  publish(profile().put("development_observation_module", directive("activate")))
  conflicts.poll(true); Handler.drain()
  check(moduleCalls.size == 5) // Publication from application code cannot bypass arbitration.
  conflicts.close()
  check(JSONObject(File(directory, "module-watch-receipt.json").readText()).getString("phase") == "retired")
  println("Actual hotloader: validation-before-effects, atomic file A/B/A, cached owner observation, visual/module receipt separation, optional absence, omission, stale completion and close checks passed")
}
