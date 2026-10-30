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
  val fixture = SpatialCameraControlProfileTest::class.java.getDeclaredMethod("validProfile").apply { isAccessible = true }
  fun profile() = fixture.invoke(SpatialCameraControlProfileTest()) as JSONObject
  fun directive(mode: String) = JSONObject().put("mode", mode).also {
    if (mode == "activate") it.put("generation", 1).put("sha256", "a".repeat(64))
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
  val beforeClose = receiptFile.readBytes()
  hotloader.close()
  pending.last().complete("{\"actual\":\"after-close\"}")
  Handler.drain()
  check(beforeClose.contentEquals(receiptFile.readBytes()))
  println("Actual hotloader: validation-before-effects, atomic file A/B/A, cached owner observation, visual/module receipt separation, optional absence, omission, stale completion and close checks passed")
}
