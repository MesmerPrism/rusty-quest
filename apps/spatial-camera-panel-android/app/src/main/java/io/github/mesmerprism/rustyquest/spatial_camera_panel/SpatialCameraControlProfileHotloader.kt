package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import android.util.AtomicFile

internal data class SpatialCameraControlProfileEffective(
    val layerOverride: Float,
    val projectionScale: Float,
    val zoneCompositor: PrivateLayerZoneCompositor,
    val rgbChannelTransform: RgbChannelTransform,
    val projectionSurfaceDisplacement: ProjectionSurfaceDisplacement,
    val projectionSurfaceTiling: ProjectionSurfaceTiling,
    val projectionInnerAlpha: ProjectionInnerAlpha,
    val strengthCycleSpeedHz: Float,
)

internal class SpatialCameraControlProfileHotloader(
    context: Context,
    private val routeActive: () -> Boolean,
    private val applyProfile:
        (SpatialCameraControlProfile, String) -> SpatialCameraControlProfileEffective,
    private val marker: (String) -> Unit,
    private val validateCandidate: (ByteArray) -> Unit = {},
    private val effectiveInputObservation: () -> String? = { null },
    private val applyObservationModule: (SpatialDevelopmentModuleDirective, String) -> CompletableFuture<String>? = { _, _ -> null },
    private val applyScissorModule: (SpatialDevelopmentModuleDirective, String) -> CompletableFuture<String>? = { _, _ -> null },
) {
  private data class FileSignature(val length: Long, val modifiedMs: Long)

  private val directory =
      File(
          context.getExternalFilesDir(null) ?: context.filesDir,
          SpatialCameraControlProfileContract.PROFILE_DIRECTORY,
      )
  private val activeProfile =
      File(directory, SpatialCameraControlProfileContract.ACTIVE_PROFILE_FILE)
  private val applyReceipt =
      File(directory, SpatialCameraControlProfileContract.APPLY_RECEIPT_FILE)
  private val moduleProfile = File(directory, SpatialDevelopmentModuleProfileContract.ACTIVE_FILE)
  private var observedModuleSignature: FileSignature? = null
  private val retiredLegacyModules = mutableMapOf<String, FileSignature>()
  private val watcherId by lazy { java.util.UUID.randomUUID().toString() }
  private val ownerPid by lazy { android.os.Process.myPid() }
  private val ownerStartTicks by lazy {
    runCatching { File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19] }
        .getOrDefault("unavailable")
  }
  private var armed = false
  private var observedSignature: FileSignature? = null
  private var pendingSignature: FileSignature? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var fileObserver: FileObserver? = null
  private val directiveTickets = mutableMapOf<String, Any>()
  private val pendingModuleReceipts = mutableMapOf<String, JSONObject>()

  fun arm() {
    if (armed) return
    directory.mkdirs()
    observedSignature = signature()
    observedModuleSignature = signature(moduleProfile)
    pendingSignature = null
    armed = true
    fileObserver =
        object :
            FileObserver(
                directory,
                CLOSE_WRITE or CREATE or DELETE or MOVED_FROM or MOVED_TO,
            ) {
          override fun onEvent(event: Int, path: String?) {
            if (path != SpatialCameraControlProfileContract.ACTIVE_PROFILE_FILE &&
                path != SpatialDevelopmentModuleProfileContract.ACTIVE_FILE) return
            mainHandler.post { poll(force = true) }
          }
        }
    fileObserver?.startWatching()
    writeReceiptAt(File(directory, "module-watch-receipt.json"), ownerFields(JSONObject())
        .put("schema", "rusty.quest.spatial_camera_panel.development_module_watch.v1")
        .put("phase", "armed").put("armed_unix_ms", System.currentTimeMillis()))
    marker(
        "channel=control-profile-hotload status=armed " +
            "schema=${SpatialCameraControlProfileContract.SCHEMA} " +
            "profilePath=external-files/${SpatialCameraControlProfileContract.PROFILE_DIRECTORY}/${SpatialCameraControlProfileContract.ACTIVE_PROFILE_FILE} " +
            "watcherId=$watcherId ownerPid=$ownerPid ownerStartTicks=$ownerStartTicks " +
            "moduleProfilePath=external-files/control-profiles/${SpatialDevelopmentModuleProfileContract.ACTIVE_FILE} " +
            "staleProfileApplied=false changeDetection=file-observer sceneTickPolling=false"
    )
  }

  fun poll(force: Boolean = false) {
    if (!armed) return
    if (!force) return
    pollModules()
    val current = signature()
    if (current == observedSignature) {
      pendingSignature = null
      return
    }
    if (current == null) {
      observedSignature = null
      pendingSignature = null
      marker(
          "channel=control-profile-hotload status=profile-removed " +
              "effectiveControlsRetained=true runtimeCrash=false"
      )
      return
    }
    if (!routeActive()) {
      if (pendingSignature != current) {
        pendingSignature = current
        marker(
            "channel=control-profile-hotload status=pending-route " +
                "profileChanged=true routeActive=false runtimeCrash=false"
        )
      }
      return
    }

    val bytes =
        runCatching { activeProfile.readBytes() }
            .getOrElse { throwable ->
              reject(current, null, null, "profile_read_failed", throwable)
              return
            }
    val digest = sha256(bytes)
    val profile =
        runCatching { SpatialCameraControlProfileContract.parse(bytes) }
            .getOrElse { throwable ->
              reject(current, digest, null, "profile_validation_failed", throwable)
              return
            }
    val effective =
        runCatching { validateCandidate(bytes) }
            .getOrElse { throwable ->
              reject(current, digest, profile, "profile_context_validation_failed", throwable)
              return
            }.let {
        runCatching { applyProfile(profile, "control-profile-hotload") }
            .getOrElse { throwable ->
              reject(current, digest, profile, "profile_apply_failed", throwable)
              return
            }
        }
    val receipt =
        JSONObject()
            .put(
                "schema",
                SpatialCameraControlProfileContract.APPLY_RECEIPT_SCHEMA,
            )
            .put("status", "applied")
            .put("applied_scope", "visual_controls")
            .put("profile_id", profile.profileId)
            .put("revision", profile.revision)
            .put("profile_sha256", digest)
            .put("applied_unix_ms", System.currentTimeMillis())
            .put("route_active", true)
            .put("effective", effectiveJson(effective))
    val inputObservation = runCatching {
      effectiveInputObservation()?.let(::JSONObject)
    }.getOrNull() ?: JSONObject().put("phase", "not_selected")
    receipt.put("effective_input_owner", inputObservation)
    if (profile.peerNetworkEndpoints != null) {
      receipt.put("requested_peer_network_endpoints", JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("peer_network_endpoints"))
          .put("endpoint_reload_boundary", "next_eligible_runtime_generation")
    }
    writeReceipt(receipt)
    observedSignature = current
    pendingSignature = null
    dispatchLegacyModule("observation", profile.developmentObservationModule, digest, profile, current, applyObservationModule)
    dispatchLegacyModule("scissor", profile.developmentScissorModule, digest, profile, current, applyScissorModule)
    marker(
        "channel=control-profile-hotload status=applied " +
            "profileId=${activityMarkerToken(profile.profileId)} revision=${profile.revision} " +
            "profileSha256=$digest transport=file-poll-atomic-replace " +
            "layerOverride=${effective.layerOverride} projectionScale=${effective.projectionScale} " +
            "strengthCycleSpeedHz=${effective.strengthCycleSpeedHz} " +
            "${PrivateLayerZoneCompositorModule.markerFields(effective.zoneCompositor)} " +
            "${RgbChannelTransformModule.markerFields(effective.rgbChannelTransform)} " +
            "${ProjectionSurfaceDisplacementModule.markerFields(effective.projectionSurfaceDisplacement)} " +
            "projectionSurfaceTilingRequested=${ProjectionSurfaceTilingModule.requested(effective.projectionSurfaceTiling)} " +
            "projectionInnerAlphaRequested=${ProjectionInnerAlphaModule.requested(effective.projectionInnerAlpha)} " +
            "runtimeCrash=false"
    )
  }

  fun close() {
    if (!armed) return
    armed = false
    fileObserver?.stopWatching()
    fileObserver = null
    for ((consumer, receipt) in pendingModuleReceipts) {
      writeReceiptAt(moduleReceiptFile(consumer), receipt.put("status", "retired")
          .put("phase", "retired").put("observed_unix_ms", System.currentTimeMillis()))
    }
    pendingModuleReceipts.clear()
    directiveTickets.clear()
    writeReceiptAt(File(directory, "module-watch-receipt.json"), ownerFields(JSONObject())
        .put("schema", "rusty.quest.spatial_camera_panel.development_module_watch.v1")
        .put("phase", "retired").put("retired_unix_ms", System.currentTimeMillis()))
  }

  private fun ownerFields(json: JSONObject): JSONObject = json.put("watcher_id", watcherId)
      .put("owner_process_pid", ownerPid).put("owner_process_start_ticks", ownerStartTicks)

  private fun moduleReceiptFile(consumer: String) = File(directory, "last-development-$consumer-module-receipt.json")

  private fun moduleReceipt(consumer: String, directive: SpatialDevelopmentModuleDirective,
      digest: String, id: String, revision: Long, source: String, status: String,
      publication: FileSignature): JSONObject =
      ownerFields(JSONObject())
          .put("schema", "rusty.quest.spatial_camera_panel.development_module_receipt.v1")
          .put("consumer", consumer).put("profile_sha256", digest).put("profile_id", id)
          .put("revision", revision).put("source_file", source)
          .put("source_length", publication.length).put("source_modified_unix_ms", publication.modifiedMs)
          .put("mode", directive.mode).put("status", status)
          .put("phase", if (status == "watched") "watched" else "transport_rejected")
          .put("observed_unix_ms", System.currentTimeMillis())

  private fun readBounded(file: File): ByteArray = file.inputStream().use {
    val bytes = ByteArray(SpatialCameraControlProfileContract.MAX_PROFILE_BYTES + 1)
    var count = 0
    while (count < bytes.size) {
      val read = it.read(bytes, count, bytes.size - count)
      if (read < 0) break
      count += read
    }
    require(count <= SpatialCameraControlProfileContract.MAX_PROFILE_BYTES) { "profile_too_large" }
    bytes.copyOf(count)
  }

  /** Both files share one owner. Presence of a competing directive never establishes precedence. */
  private fun pollModules() {
    val current = signature(moduleProfile)
    val legacySignature = signature()
    val moduleResult = if (current != null) runCatching {
      val bytes = readBounded(moduleProfile)
      val profile = SpatialDevelopmentModuleProfileContract.parse(bytes)
      check(signature(moduleProfile) == current) { "module_profile_changed_during_read" }
      profile to sha256(bytes)
    } else null
    val legacyResult = if (legacySignature != null) runCatching {
      val profile = SpatialCameraControlProfileContract.parse(readBounded(activeProfile))
      check(signature() == legacySignature) { "legacy_profile_changed_during_read" }
      profile
    } else null
    val modules = moduleResult?.getOrNull()?.first
    fun moduleDirective(consumer: String) = if (consumer == "observation") modules?.observation else modules?.scissor
    fun legacyDirective(consumer: String) = legacyResult?.getOrNull()?.let {
      if (consumer == "observation") it.developmentObservationModule else it.developmentScissorModule
    }
    // Retire a pending legacy directive even while its visual route is unavailable. Later removal
    // of the competing file must not resurrect it when the visual route eventually becomes ready.
    if (legacySignature != null && legacySignature != observedSignature && current != null) {
      for (consumer in listOf("observation", "scissor")) {
        if (moduleResult?.isFailure == true || moduleDirective(consumer) != null) {
          retiredLegacyModules[consumer] = legacySignature
        }
      }
    }
    if (current == observedModuleSignature) return
    observedModuleSignature = current // Every new publication is consumed once, including rejection.
    if (current == null) return
    val summary = ownerFields(JSONObject()).put("schema", SpatialDevelopmentModuleProfileContract.RECEIPT_SCHEMA)
        .put("source_file", SpatialDevelopmentModuleProfileContract.ACTIVE_FILE)
        .put("observed_unix_ms", System.currentTimeMillis())
    val parsed = moduleResult?.getOrNull()
    if (parsed == null) {
      writeReceiptAt(File(directory, SpatialDevelopmentModuleProfileContract.RECEIPT_FILE),
          summary.put("status", "rejected").put("error_code", "module_profile_validation_failed"))
      return
    }
    val (profile, digest) = parsed
    summary.put("profile_sha256", digest).put("profile_id", profile.profileId).put("revision", profile.revision)
    var conflict = false
    for (consumer in listOf("observation", "scissor")) {
      val directive = moduleDirective(consumer) ?: continue
      if (signature() != legacySignature || signature(moduleProfile) != current ||
          legacyResult?.isFailure == true || legacyDirective(consumer) != null) {
        conflict = true
        writeReceiptAt(moduleReceiptFile(consumer), moduleReceipt(consumer, directive, digest,
            profile.profileId, profile.revision, SpatialDevelopmentModuleProfileContract.ACTIVE_FILE, "rejected", current)
            .put("error_code", "competing_profile_directive"))
      } else {
        dispatchModule(consumer, directive, digest, profile.profileId, profile.revision,
            SpatialDevelopmentModuleProfileContract.ACTIVE_FILE, current,
            if (consumer == "observation") applyObservationModule else applyScissorModule)
      }
    }
    writeReceiptAt(File(directory, SpatialDevelopmentModuleProfileContract.RECEIPT_FILE),
        summary.put("status", if (conflict) "conflict" else "watched")
            .put("applied_scope", "development_modules_only").put("visual_controls_applied", false))
  }

  private fun dispatchLegacyModule(consumer: String, directive: SpatialDevelopmentModuleDirective?,
      digest: String, profile: SpatialCameraControlProfile, signature: FileSignature,
      apply: (SpatialDevelopmentModuleDirective, String) -> CompletableFuture<String>?) {
    if (directive == null) return
    // The visual callback may run application code. Recheck the competing file after that
    // callback so publication during visual application cannot bypass the shared arbitration.
    val competingSignature = signature(moduleProfile)
    val competitorBlocks = competingSignature != null && runCatching {
      val module = SpatialDevelopmentModuleProfileContract.parse(readBounded(moduleProfile))
      check(signature(moduleProfile) == competingSignature)
      if (consumer == "observation") module.observation != null else module.scissor != null
    }.getOrDefault(true)
    if (retiredLegacyModules[consumer] == signature || competitorBlocks || signature() != signature) {
      retiredLegacyModules[consumer] = signature
      writeReceiptAt(moduleReceiptFile(consumer), moduleReceipt(consumer, directive, digest,
          profile.profileId, profile.revision, SpatialCameraControlProfileContract.ACTIVE_PROFILE_FILE, "rejected", signature)
          .put("error_code", "competing_profile_directive"))
      return
    }
    dispatchModule(consumer, directive, digest, profile.profileId, profile.revision,
        SpatialCameraControlProfileContract.ACTIVE_PROFILE_FILE, signature, apply)
  }

  private fun dispatchModule(consumer: String, directive: SpatialDevelopmentModuleDirective?,
      profileSha256: String, id: String, revision: Long, source: String, publication: FileSignature,
      apply: (SpatialDevelopmentModuleDirective, String) -> CompletableFuture<String>?) {
    if (directive == null) return
    val ticket = Any()
    directiveTickets[consumer] = ticket
    val target = moduleReceiptFile(consumer)
    val baseReceipt = moduleReceipt(consumer, directive, profileSha256, id, revision, source, "watched", publication)
    fun receipt(status: String) = JSONObject(baseReceipt.toString()).put("status", status)
        .put("observed_unix_ms", System.currentTimeMillis())
    pendingModuleReceipts.remove(consumer)
    val future = runCatching { apply(directive, profileSha256) }.getOrElse {
      writeReceiptAt(target, receipt("failed").put("error_code", "consumer_dispatch_failed"))
      return
    }
    if (future == null) {
      writeReceiptAt(target, receipt("unavailable").put("error_code", "optional_consumer_unavailable"))
      return
    }
    val watched = receipt("watched")
    pendingModuleReceipts[consumer] = watched
    writeReceiptAt(target, watched)
    future.whenComplete { value, failure ->
      mainHandler.post {
        // A fresh ticket is required even for A->B->the same raw A bytes.
        if (!armed || directiveTickets[consumer] !== ticket) return@post
        pendingModuleReceipts.remove(consumer)
        val output = if (failure != null || value == null) {
          receipt("failed").put("error_code", "consumer_completion_failed")
        } else runCatching {
          val raw = value.toByteArray(Charsets.UTF_8)
          require(raw.size <= SpatialCameraControlProfileContract.MAX_PROFILE_BYTES) { "module_receipt_bounds" }
          receipt("observed").put("phase", "consumer_readback").put("consumer_receipt", StrictJsonByteIngress.parseObject(raw))
        }.getOrElse { receipt("failed").put("error_code", "consumer_receipt_invalid") }
        writeReceiptAt(target, output)
      }
    }
  }

  private fun reject(
      signature: FileSignature,
      digest: String?,
      profile: SpatialCameraControlProfile?,
      code: String,
      throwable: Throwable,
  ) {
    val receipt =
        JSONObject()
            .put(
                "schema",
                SpatialCameraControlProfileContract.APPLY_RECEIPT_SCHEMA,
            )
            .put("status", "rejected")
            .put("profile_id", profile?.profileId ?: JSONObject.NULL)
            .put("revision", profile?.revision ?: JSONObject.NULL)
            .put("profile_sha256", digest ?: JSONObject.NULL)
            .put("rejected_unix_ms", System.currentTimeMillis())
            .put("route_active", routeActive())
            .put("error_code", code)
            .put(
                "error",
                activityMarkerToken(throwable.message ?: throwable.javaClass.simpleName),
            )
    writeReceipt(receipt)
    observedSignature = signature
    pendingSignature = null
    marker(
        "channel=control-profile-hotload status=rejected errorCode=$code " +
            "error=${activityMarkerToken(throwable.message ?: throwable.javaClass.simpleName)} " +
            "previousEffectiveControlsRetained=true runtimeCrash=false"
    )
  }

  private fun signature(file: File = activeProfile): FileSignature? =
      if (file.isFile) {
        FileSignature(file.length(), file.lastModified())
      } else {
        null
      }

  private fun writeReceipt(json: JSONObject) {
    writeReceiptAt(applyReceipt, json)
  }

  private fun writeReceiptAt(target: File, json: JSONObject) {
    runCatching {
          directory.mkdirs()
          val atomic = AtomicFile(target)
          val stream = atomic.startWrite()
          try { stream.write(json.toString(2).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
          catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        }
        .onFailure { throwable ->
          marker(
              "channel=control-profile-hotload status=receipt-write-failed " +
                  "error=${activityMarkerToken(throwable.message ?: throwable.javaClass.simpleName)} " +
                  "runtimeCrash=false"
          )
        }
  }

  private fun effectiveJson(effective: SpatialCameraControlProfileEffective): JSONObject =
      JSONObject()
          .put("layer_override", effective.layerOverride)
          .put("projection_scale", effective.projectionScale)
          .put("strength_cycle_hz", effective.strengthCycleSpeedHz)
          .put("zone_compositor", zoneJson(effective.zoneCompositor))
          .put("rgb_channel_transform", rgbJson(effective.rgbChannelTransform))
          .put(
              "projection_surface_displacement",
              surfaceDisplacementJson(effective.projectionSurfaceDisplacement),
          )
          .put(
              "projection_surface_tiling",
              surfaceTilingJson(effective.projectionSurfaceTiling),
          )
          .put(
              "projection_inner_alpha",
              innerAlphaJson(effective.projectionInnerAlpha),
          )

  private fun zoneJson(value: PrivateLayerZoneCompositor): JSONObject =
      JSONObject()
          .put(
              "coverage_mode",
              when (value.coverageMode) {
                PrivateLayerZoneCompositorControls.coverageDynamicBuffer -> "buffer"
                PrivateLayerZoneCompositorControls.coverageReplaceVideo -> "full"
                else -> "off"
              },
          )
          .put("region_contract", "v4")
          .put(
              "center_content",
              PrivateLayerZoneCompositorControls.centerContentToken(value.centerContentMode),
          )
          .put("center_projection_mix", value.centerProjectionMix)
          .put("center_corner_radius_uv", value.centerCornerRadiusUv)
          .put(
              "buffer_geometry",
              PrivateLayerZoneCompositorControls.bufferGeometryToken(value.bufferGeometryMode),
          )
          .put("buffer_static_width_uv", value.bufferStaticWidthUv)
          .put("buffer_minimum_width_uv", value.bufferMinimumWidthUv)
          .put("buffer_maximum_width_uv", value.bufferMaximumWidthUv)
          .put(
              "buffer_maximum_speed_meters_per_second",
              value.bufferMaximumSpeedMetersPerSecond,
          )
          .put(
              "buffer_fill",
              PrivateLayerZoneCompositorControls.bufferFillToken(value.bufferFillMode),
          )
          .put(
              "stretch_extent",
              PrivateLayerZoneCompositorControls.stretchExtentToken(value.stretchExtentMode),
          )
          .put(
              "stretch_source",
              when (value.stretchSource) {
                PrivateLayerZoneCompositorControls.sourceProcessed -> "processed"
                PrivateLayerZoneCompositorControls.sourceMixed -> "mix"
                else -> "raw"
              },
          )
          .put(
              "debug_mode",
              when (value.debugMode) {
                PrivateLayerZoneCompositorControls.debugRegions -> "regions"
                PrivateLayerZoneCompositorControls.debugSampleUv -> "sample-uv"
                else -> "normal"
              },
          )
          .put(
              "outer_target_mode",
              if (
                  value.outerTargetMode ==
                      PrivateLayerZoneCompositorControls.outerTargetTransparentSpatialVideo
              ) {
                "transparent-spatial-video"
              } else {
                "readable-color"
              },
          )
          .put("stretch_mapping", "graded-edge-trail-native")
          .put(
              "projection_effect_edge_guard_enabled",
              value.projectionEffectEdgeGuardEnabled,
          )
          .put("stretch_option_flags", value.stretchOptionFlags)
          .put("edge_inset_uv", value.edgeInsetUv)
          .put("max_inset_uv", value.maxInsetUv)
          .put("stretch_curve", value.stretchCurve)
          .put("processed_mix", value.processedMix)
          .put(
              "outer_content",
              PrivateLayerZoneCompositorControls.outerContentToken(value.outerContentMode),
          )
          .put(
              "outer_stretch_source",
              when (value.outerStretchSource) {
                PrivateLayerZoneCompositorControls.sourceProcessed -> "processed"
                PrivateLayerZoneCompositorControls.sourceMixed -> "mix"
                else -> "raw"
              },
          )
          .put("outer_stretch_option_flags", value.outerStretchOptionFlags)
          .put("outer_edge_inset_uv", value.outerEdgeInsetUv)
          .put("outer_max_inset_uv", value.outerMaxInsetUv)
          .put("outer_stretch_curve", value.outerStretchCurve)
          .put("outer_processed_mix", value.outerProcessedMix)
          .put("inner", zoneBandJson(value, true))
          .put("outer", zoneBandJson(value, false))

  private fun zoneBandJson(
      value: PrivateLayerZoneCompositor,
      inner: Boolean,
  ): JSONObject {
    val signal = if (inner) value.innerSignal else value.outerSignal
    val dynamics = if (inner) value.innerChannelDynamics else value.outerChannelDynamics
    return JSONObject()
        .put("signal", zoneSignalToken(signal))
        .put("width_uv", if (inner) value.innerWidthUv else value.outerWidthUv)
        .put("curve", if (inner) value.innerCurve else value.outerCurve)
        .put(
            "threshold_rgb",
            float3(
                if (inner) value.innerThresholdR else value.outerThresholdR,
                if (inner) value.innerThresholdG else value.outerThresholdG,
                if (inner) value.innerThresholdB else value.outerThresholdB,
            ),
        )
        .put("softness", if (inner) value.innerSoftness else value.outerSoftness)
        .put("strength", if (inner) value.innerStrength else value.outerStrength)
        .put(
            "cycle_amplitude",
            if (inner) value.innerCycleAmplitude else value.outerCycleAmplitude,
        )
        .put("cycle_hz", if (inner) value.innerCycleHz else value.outerCycleHz)
        .put("motion_gain", if (inner) value.innerMotionGain else value.outerMotionGain)
        .put("channel_dynamics", zoneChannelDynamicsJson(dynamics))
  }

  private fun zoneChannelDynamicsJson(value: PrivateLayerZoneChannelDynamics): JSONObject =
      JSONObject()
          .put(
              "application_mode",
              when (value.applicationMode) {
                PrivateLayerZoneCompositorControls.applicationComponent -> "component"
                PrivateLayerZoneCompositorControls.applicationRegion -> "region"
                else -> "legacy"
              },
          )
          .put(
              "source_choice",
              when (value.sourceChoice) {
                PrivateLayerZoneCompositorControls.blendSourceOutgoing -> "outgoing"
                PrivateLayerZoneCompositorControls.blendSourceIncoming -> "incoming"
                else -> "midpoint"
              },
          )
          .put(
              "region_driver",
              when (value.regionDriver) {
                PrivateLayerZoneCompositorControls.regionDriverRed -> "red"
                PrivateLayerZoneCompositorControls.regionDriverGreen -> "green"
                PrivateLayerZoneCompositorControls.regionDriverBlue -> "blue"
                PrivateLayerZoneCompositorControls.regionDriverMax -> "max"
                else -> "luma"
              },
          )
          .put("strength_rgb", float3(value.strengthR, value.strengthG, value.strengthB))
          .put(
              "cycle_amplitude_rgb",
              float3(value.cycleAmplitudeR, value.cycleAmplitudeG, value.cycleAmplitudeB),
          )
          .put("cycle_hz_rgb", float3(value.cycleHzR, value.cycleHzG, value.cycleHzB))
          .put(
              "cycle_phase_turns_rgb",
              float3(value.cyclePhaseR, value.cyclePhaseG, value.cyclePhaseB),
          )

  private fun rgbJson(value: RgbChannelTransform): JSONObject =
      JSONObject()
          .put(
              "mode",
              when (value.mode) {
                RgbChannelTransformControls.modeIndependent -> "independent"
                RgbChannelTransformControls.modeLinked -> "linked"
                else -> "off"
              },
          )
          .put("edge_mode", RgbChannelTransformControls.edgeToken(value.edgeMode))
          .put("direction_noise_amount_turns", value.directionNoiseAmountTurns)
          .put("direction_noise_rate_hz", value.directionNoiseRateHz)
          .put("red", rgbChannelJson(value.red))
          .put("green", rgbChannelJson(value.green))
          .put("blue", rgbChannelJson(value.blue))

  private fun rgbChannelJson(value: RgbChannelParameters): JSONObject =
      JSONObject()
          .put("direction_turns", value.directionTurns)
          .put("direction_rate_hz", value.directionRateHz)
          .put("displacement_strength_uv", value.displacementStrengthUv)
          .put("image_scale", value.imageScale)
          .put("coverage_scale", value.coverageScale)

  private fun surfaceDisplacementJson(value: ProjectionSurfaceDisplacement): JSONObject =
      JSONObject()
          .put("enabled", value.enabled)
          .put("max_displacement_meters", value.maxDisplacementMeters)
          .put(
              "reference_surface_distance_meters",
              value.referenceSurfaceDistanceMeters,
          )
          .put("polarity", value.polarity)
          .put("edge_taper", value.edgeTaper)

  private fun surfaceTilingJson(value: ProjectionSurfaceTiling): JSONObject =
      JSONObject()
          .put("enabled", value.enabled)
          .put("topology", ProjectionSurfaceTilingControls.topologyToken(value.topology))
          .put("gap_normalized", value.gapNormalized)
          .put("depth_flexibility", value.depthFlexibility)
          .put("scope", ProjectionSurfaceTilingControls.scopeToken(value.scope))

  private fun innerAlphaJson(value: ProjectionInnerAlpha): JSONObject =
      JSONObject()
          .put("enabled", value.enabled)
          .put("driver", ProjectionInnerAlphaControls.driverToken(value.driver))
          .put("threshold", value.threshold)
          .put("softness", value.softness)
          .put("amount", value.amount)
          .put("invert", value.invert)
          .put(
              "stretch_policy",
              ProjectionInnerAlphaControls.stretchPolicyToken(value.stretchPolicy),
          )
          .put(
              "stretch_obeys_exact_projection_mask",
              value.stretchObeysExactProjectionMask,
          )

  private fun zoneSignalToken(value: Int): String =
      when (value) {
        PrivateLayerZoneCompositorControls.signalRgb -> "rgb"
        PrivateLayerZoneCompositorControls.signalLuma -> "luma"
        PrivateLayerZoneCompositorControls.signalChroma -> "chroma"
        PrivateLayerZoneCompositorControls.signalDifference -> "difference"
        else -> "flat"
      }

  private fun float3(red: Float, green: Float, blue: Float): JSONArray =
      JSONArray().put(red).put(green).put(blue)

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256")
          .digest(bytes)
          .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
