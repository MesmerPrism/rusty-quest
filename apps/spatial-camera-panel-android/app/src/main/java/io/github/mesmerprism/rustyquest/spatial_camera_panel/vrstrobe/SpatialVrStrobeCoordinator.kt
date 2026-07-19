package io.github.mesmerprism.rustyquest.spatial_camera_panel.vrstrobe

import android.os.SystemClock
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthTest
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.TriangleMesh
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

internal data class SpatialVrStrobeBindings(
    val scene: Scene,
    val poseFromViewer: (Float) -> Pose,
    val marker: (String) -> Unit,
    val setPerformanceBoost: (Boolean, String) -> Boolean = { _, _ -> false },
    val monotonicNowMs: () -> Long = SystemClock::elapsedRealtime,
    val readStoredProfilesPayload: () -> String? = { null },
    val writeStoredProfilesPayload: (String) -> Boolean = { true },
    val readImportBundlePayload: () -> String? = { null },
    val clearImportBundlePayload: () -> Boolean = { true },
    val writeExportBundlePayload: (String) -> Boolean = { true },
    val wallClockNowMs: () -> Long = System::currentTimeMillis,
)

private data class VrStrobeCarrierRenderSlot(
    val material: SceneMaterial,
    val triangleMesh: TriangleMesh,
    val sceneMesh: SceneMesh,
)

private data class VrStrobePendingRendererUpdate(
    val transactionId: Long,
    val rendererRevision: Long,
    val submittedSceneTick: Long,
)

@OptIn(SpatialSDKExperimentalAPI::class)
internal class SpatialVrStrobeCoordinator(
    private val bindings: SpatialVrStrobeBindings,
    val featureDecision: VrStrobeFeatureDecision = VrStrobeFeatureRoute.resolve(),
) {
  private val safety = VrStrobeSafetyController(featureDecision.enabled)
  private var sceneTicks = 0L
  private var viewLockFailures = 0L
  private var carrierEntity: Entity? = null
  private var sceneObject: SceneObject? = null
  private var mesh: SceneMesh? = null
  private var triangleMesh: TriangleMesh? = null
  private var material: SceneMaterial? = null
  private var rendererRevision = 0L
  private var randomizeTransactionId = 0L
  private val pendingRendererUpdates = mutableListOf<VrStrobePendingRendererUpdate>()
  private var lastState = safety.snapshot(0L).state
  private val stimulusSelection = VrStrobeStimulusSelectionAuthority()
  private val storedProfileAuthority =
      VrStrobeStoredProfileAuthority(
          VrStrobeStoredProfileBindings(
              readPayload = bindings.readStoredProfilesPayload,
              writePayload = bindings.writeStoredProfilesPayload,
              readImportBundlePayload = bindings.readImportBundlePayload,
              clearImportBundlePayload = bindings.clearImportBundlePayload,
              writeExportBundlePayload = bindings.writeExportBundlePayload,
              wallClockNowMs = bindings.wallClockNowMs,
          )
      )
  private var carrierDistanceMeters = VrStrobeDistancePolicy.DEFAULT_METERS
  private var carrierShape = VrStrobeCarrierShapeState()
  private var lastDistanceMarkerMs = 0L
  private var lastConcavityMarkerMs = 0L
  private var panelVisible = true

  init {
    bindings.marker(VrStrobeFeatureRoute.activationMarker(featureDecision))
    bindings.marker(
        "channel=spatial-vr-strobe status=stored-profile-store-ready " +
            "loadStatus=${storedProfileAuthority.loadStatus} " +
            "storedProfileCount=${storedProfileAuthority.snapshot().size} storage=app-private " +
            "interchangeSchema=${VrStrobeProfileBundleCodec.SCHEMA} " +
            "exportStatus=${storedProfileAuthority.exportStatus}"
    )
  }

  fun snapshot(): VrStrobeSafetySnapshot = decorate(safety.snapshot(bindings.monotonicNowMs()))

  fun stimulusSelected(): Boolean =
      safety.snapshot(bindings.monotonicNowMs()).state in
          setOf(
              VrStrobeSafetyState.BLACK_LEAD_IN,
              VrStrobeSafetyState.RUNNING,
          )

  fun curvedMode(): Boolean = carrierShape.curvedMode

  fun setPanelVisible(visible: Boolean, source: String): Boolean {
    panelVisible = visible
    val carrierVisible = applyCarrierVisibility()
    bindings.marker(
        "channel=spatial-vr-strobe status=panel-foreground-guard " +
            "source=${source.replace(' ', '_')} panelVisible=$visible " +
            "carrierVisible=$carrierVisible stimulusSelected=${stimulusSelected()} " +
            "mode=depth-separated-panel-over-active-carrier outputLifecycleChanged=false"
    )
    return carrierVisible
  }

  fun acknowledgeWarning(acknowledged: Boolean): VrStrobeSafetySnapshot {
    val snapshot = safety.acknowledgeWarning(acknowledged)
    if (!acknowledged) {
      destroyCarrier("warning-withdrawn")
      bindings.setPerformanceBoost(false, "warning-withdrawn")
    }
    return report("warning-acknowledgement", snapshot)
  }

  fun begin(profile: VrStrobeInterferenceProfile): VrStrobeSafetySnapshot {
    val sanitized = profile.sanitized()
    return begin(
          kind = VrStrobeOutputKind.INTERFERENCE,
          profileId = sanitized.id,
          configure = { setInterferenceProfile(sanitized) },
          onConfigured = { stimulusSelection.adopt(sanitized) },
      )
  }

  fun begin(profile: VrStrobeTemporalProfile): VrStrobeSafetySnapshot {
    val sanitized = profile.sanitized()
    return begin(
          kind = VrStrobeOutputKind.TEMPORAL,
          profileId = sanitized.id,
          configure = { setTemporalProfile(sanitized) },
          onConfigured = { stimulusSelection.adopt(sanitized) },
      )
  }

  fun randomizeActive(source: String): VrStrobeSafetySnapshot {
    val nowMs = bindings.monotonicNowMs()
    val current = safety.snapshot(nowMs)
    val transactionId = ++randomizeTransactionId
    val beforeSelection = stimulusSelection.snapshot()
    bindings.marker(
        "channel=spatial-vr-strobe status=randomize-transaction-start " +
            "transactionId=$transactionId source=${source.replace(' ', '_')} " +
            "safetyState=${current.state.name.lowercase()} " +
            "stimulusRevision=${beforeSelection?.revision ?: 0L} " +
            "rendererRevision=$rendererRevision"
    )
    if (!stimulusSelected()) {
      return report(
          "randomize-rejected-$source",
          current.copy(rejectionReason = "randomize-requires-selected-stimulus"),
      )
    }
    return runCatching {
          val candidate =
              requireNotNull(stimulusSelection.randomizedCandidate()) {
                "active-stimulus-selection-missing"
              }
          val envelopeReceipt =
              when (candidate) {
                is VrStrobeStimulusProfile.Interference ->
                    VrStrobeQuestRandomizationEnvelope.receipt(candidate.profile)
                is VrStrobeStimulusProfile.Temporal ->
                    VrStrobeQuestRandomizationEnvelope.receipt(candidate.profile)
              }
          bindings.marker(
              "channel=spatial-vr-strobe status=randomize-candidate-accepted " +
                  "transactionId=$transactionId source=${source.replace(' ', '_')} " +
                  envelopeReceipt
          )
          publishRandomizedCandidate(candidate, current, source, transactionId)
          val adopted = stimulusSelection.adopt(candidate)
          bindings.marker(
              "channel=spatial-vr-strobe status=randomize-transaction-complete " +
                  "transactionId=$transactionId source=${source.replace(' ', '_')} " +
                  "profileId=${adopted.profile.id} presetIndex=${adopted.presetIndex} " +
                  "stimulusRevision=${adopted.revision} " +
                  "outputKind=${current.outputKind?.name?.lowercase()} selectionAuthority=unified " +
                  "rendererSubmit=bounded-active-slot-delta " +
                  "rendererRevision=$rendererRevision " +
                  "transactionDurationMs=${(bindings.monotonicNowMs() - nowMs).coerceAtLeast(0L)} " +
                  "randomizationEnvelope=${VrStrobeQuestRandomizationEnvelope.ID} " +
                  "safetyStateBefore=${current.state.name.lowercase()} " +
                  "safetyStateAfter=${current.state.name.lowercase()} " +
                  "outputLifecycleChanged=false pauseStateExists=false"
          )
          report("randomize-$source", current.copy(rejectionReason = "none"))
        }
        .getOrElse { error ->
          bindings.marker(
              "channel=spatial-vr-strobe status=randomize-transaction-failed " +
                  "transactionId=$transactionId source=${source.replace(' ', '_')} " +
                  "rendererRevision=$rendererRevision " +
                  "transactionDurationMs=${(bindings.monotonicNowMs() - nowMs).coerceAtLeast(0L)} " +
                  "error=${error.javaClass.simpleName} " +
                  "message=${error.message?.replace(' ', '_') ?: "none"}"
          )
          report(
              "randomize-failed-$source",
              current.copy(
                  rejectionReason =
                      "randomize-failed-${error.javaClass.simpleName.lowercase()}"
              ),
          )
        }
  }

  fun storedProfiles(): List<VrStrobeStoredProfile> = storedProfileAuthority.snapshot()

  fun storeActiveProfile(source: String): VrStrobeStoreResult {
    val active = stimulusSelection.snapshot()
    if (!stimulusSelected() || active == null) {
      bindings.marker(
          "channel=spatial-vr-strobe status=stored-profile-rejected " +
              "source=${source.replace(' ', '_')} reason=active-stimulus-required"
      )
      return VrStrobeStoreResult(rejectionReason = "active-stimulus-required")
    }
    val result =
        storedProfileAuthority.store(
            active = active.profile,
            distanceMeters = carrierDistanceMeters,
            carrierShape = carrierShape,
        )
    val stored = result.storedProfile
    if (stored == null) {
      bindings.marker(
          "channel=spatial-vr-strobe status=stored-profile-rejected " +
              "source=${source.replace(' ', '_')} reason=${result.rejectionReason}"
      )
      return result
    }
    bindings.marker(
        "channel=spatial-vr-strobe status=stored-profile-saved " +
            "source=${source.replace(' ', '_')} storedProfileId=${stored.id} " +
            "outputKind=${stored.kind.name.lowercase()} sourceStimulusRevision=${active.revision} " +
            "distanceMeters=${stored.distanceMeters} " +
            "curvedMode=${stored.carrierShape.curvedMode} " +
            "concavity=${stored.carrierShape.concavity} " +
            "storedProfileCount=${storedProfileAuthority.snapshot().size} storage=app-private " +
            "exportStatus=${result.exportStatus}"
    )
    return result
  }

  fun loadStoredProfile(id: String, source: String): VrStrobeSafetySnapshot {
    val stored = storedProfileAuthority.find(id)
        ?: return report(
            "stored-profile-load-rejected-$source",
            safety.snapshot(bindings.monotonicNowMs())
                .copy(rejectionReason = "stored-profile-not-found"),
        )
    val current = safety.snapshot(bindings.monotonicNowMs())
    if (current.state == VrStrobeSafetyState.READY ||
        current.state == VrStrobeSafetyState.FEATURE_DISABLED) {
      return report(
          "stored-profile-load-rejected-$source",
          current.copy(rejectionReason = "stored-profile-load-requires-armed-session"),
      )
    }
    if (stimulusSelected()) {
      stop("stored-profile-load-transition")
    }
    carrierDistanceMeters = stored.distanceMeters
    carrierShape = stored.carrierShape.sanitized()
    bindings.marker(
        "channel=spatial-vr-strobe status=stored-profile-load-requested " +
            "source=${source.replace(' ', '_')} storedProfileId=${stored.id} " +
            "outputKind=${stored.kind.name.lowercase()} distanceMeters=${stored.distanceMeters} " +
            "curvedMode=${carrierShape.curvedMode} concavity=${carrierShape.concavity}"
    )
    return when (val profile = stored.profile) {
      is VrStrobeStimulusProfile.Interference -> begin(profile.profile)
      is VrStrobeStimulusProfile.Temporal -> begin(profile.profile)
    }
  }

  fun cyclePreset(direction: Int, source: String): VrStrobeSafetySnapshot {
    val nowMs = bindings.monotonicNowMs()
    val current = safety.snapshot(nowMs)
    if (!stimulusSelected() || direction == 0) {
      return report(
          "preset-cycle-rejected-$source",
          current.copy(rejectionReason = "preset-cycle-requires-selected-stimulus"),
      )
    }
    val step = if (direction < 0) -1 else 1
    val previous = stimulusSelection.snapshot()
    val candidate =
        requireNotNull(stimulusSelection.cycleCandidate(step)) {
          "preset-cycle-candidate-required"
        }
    val nextIndex = VrStrobePresetCatalog.all.indexOfFirst { it.id == candidate.id }
    safety.stop("controller-preset-cycle", nowMs)
    destroyCarrier("controller-preset-cycle")
    bindings.marker(
        "channel=spatial-vr-strobe status=preset-cycle source=${source.replace(' ', '_')} " +
            "direction=$step previousIndex=${previous?.presetIndex ?: -1} nextIndex=$nextIndex " +
            "previousRevision=${previous?.revision ?: 0L} selectionAuthority=unified"
    )
    return when (candidate) {
      is VrStrobeStimulusProfile.Interference -> begin(candidate.profile)
      is VrStrobeStimulusProfile.Temporal -> begin(candidate.profile)
    }
  }

  fun adjustDistance(stickY: Float, deltaSeconds: Float, source: String): Float {
    if (!stimulusSelected()) return carrierDistanceMeters
    val previous = carrierDistanceMeters
    carrierDistanceMeters =
        VrStrobeDistancePolicy.apply(
            currentMeters = carrierDistanceMeters,
            stickY = stickY,
            deltaSeconds = deltaSeconds,
        )
    val nowMs = bindings.monotonicNowMs()
    if (carrierDistanceMeters != previous && nowMs - lastDistanceMarkerMs >= 250L) {
      lastDistanceMarkerMs = nowMs
      bindings.marker(
          "channel=spatial-vr-strobe status=distance-adjusted source=${source.replace(' ', '_')} " +
              "stickY=$stickY distanceMeters=$carrierDistanceMeters minMeters=${VrStrobeDistancePolicy.MIN_METERS} " +
              "maxMeters=${VrStrobeDistancePolicy.MAX_METERS} joystickUpMoves=farther"
      )
    }
    return carrierDistanceMeters
  }

  fun setCurvedMode(enabled: Boolean, source: String): VrStrobeSafetySnapshot {
    val nowMs = bindings.monotonicNowMs()
    val current = safety.snapshot(nowMs)
    if (!stimulusSelected()) {
      return report(
          "carrier-shape-rejected-$source",
          current.copy(rejectionReason = "carrier-shape-requires-selected-stimulus"),
      )
    }
    carrierShape = carrierShape.copy(curvedMode = enabled).sanitized()
    applyCarrierShape()
    bindings.marker(
        "channel=spatial-vr-strobe status=carrier-shape-mode-changed " +
            "source=${source.replace(' ', '_')} curvedMode=$enabled " +
            "concavity=${carrierShape.concavity} surfaceArcDegrees=${carrierArcDegrees()} " +
            "geometry=radial-spherical-cap orientation=concave-toward-viewer"
    )
    return report("carrier-shape-mode-$source", current.copy(rejectionReason = "none"))
  }

  fun toggleCurvedMode(source: String): VrStrobeSafetySnapshot =
      setCurvedMode(!carrierShape.curvedMode, source)

  fun adjustConcavity(stickY: Float, deltaSeconds: Float, source: String): Float {
    if (!stimulusSelected() || !carrierShape.curvedMode) return carrierShape.concavity
    val previous = carrierShape.concavity
    val next = VrStrobeConcavityPolicy.apply(previous, stickY, deltaSeconds)
    carrierShape = carrierShape.copy(concavity = next)
    if (next != previous) applyCarrierShape()
    val nowMs = bindings.monotonicNowMs()
    if (next != previous && nowMs - lastConcavityMarkerMs >= 250L) {
      lastConcavityMarkerMs = nowMs
      bindings.marker(
          "channel=spatial-vr-strobe status=carrier-concavity-adjusted " +
              "source=${source.replace(' ', '_')} stickY=$stickY concavity=$next " +
              "surfaceArcDegrees=${carrierArcDegrees()} leftJoystickUp=increases-concavity " +
              "geometry=radial-spherical-cap orientation=concave-toward-viewer"
      )
    }
    return next
  }

  fun stop(reason: String = "explicit-stop"): VrStrobeSafetySnapshot {
    val snapshot = safety.stop(reason, bindings.monotonicNowMs())
    destroyCarrier(reason)
    bindings.setPerformanceBoost(false, reason)
    return report(reason, snapshot)
  }

  fun onFocusLost(): VrStrobeSafetySnapshot {
    val snapshot = safety.focusLost(bindings.monotonicNowMs())
    destroyCarrier("focus-lost")
    bindings.setPerformanceBoost(false, "focus-lost")
    return report("focus-lost", snapshot)
  }

  fun onSceneTick() {
    val entity = carrierEntity ?: return
    sceneTicks += 1L
    runCatching { entity.setComponent(Transform(bindings.poseFromViewer(carrierDistanceMeters))) }
        .onFailure { viewLockFailures += 1L }
    val nowMs = bindings.monotonicNowMs()
    val snapshot = safety.tick(nowMs)
    applyOutput(snapshot)
    val frameObserved =
        pendingRendererUpdates.filter { update -> update.submittedSceneTick < sceneTicks }
    frameObserved.forEach { update ->
      bindings.marker(
          "channel=spatial-vr-strobe status=randomize-renderer-frame-boundary-observed " +
              "transactionId=${update.transactionId} rendererRevision=${update.rendererRevision} " +
              "submittedSceneTick=${update.submittedSceneTick} observedSceneTick=$sceneTicks " +
              "rendererVisibleProof=attended-required"
      )
    }
    pendingRendererUpdates.removeAll(frameObserved.toSet())
    if (snapshot.state != lastState) {
      report("scene-tick-transition", snapshot)
      lastState = snapshot.state
    }
  }

  fun destroy(reason: String) {
    safety.invalidateWarningAcknowledgement(reason, bindings.monotonicNowMs())
    destroyCarrier(reason)
    bindings.setPerformanceBoost(false, reason)
  }

  private fun begin(
      kind: VrStrobeOutputKind,
      profileId: String,
      configure: () -> Unit,
      onConfigured: () -> Unit,
  ): VrStrobeSafetySnapshot {
    val nowMs = bindings.monotonicNowMs()
    val snapshot = safety.begin(kind, profileId, nowMs)
    if (snapshot.state != VrStrobeSafetyState.BLACK_LEAD_IN) {
      return report("begin-rejected", snapshot)
    }
    return runCatching {
          bindings.setPerformanceBoost(true, "stimulus-begin")
          ensureCarrier()
          applyCarrierShape()
          configure()
          onConfigured()
          applyOutput(snapshot)
          lastState = snapshot.state
          report("begin", snapshot)
        }
        .getOrElse { error ->
          val failed = safety.stop("carrier-create-failed", nowMs)
          destroyCarrier("carrier-create-failed")
          bindings.setPerformanceBoost(false, "carrier-create-failed")
          bindings.marker(
            "channel=spatial-vr-strobe status=failed error=${error.javaClass.simpleName} " +
                  "message=${error.message?.replace(' ', '_') ?: "none"}"
          )
          failed
        }
  }

  private fun ensureCarrier() {
    if (material != null && sceneObject != null) return
    val meshData = VrStrobeCarrierGeometry.planarDisc()
    val activeSlot = createCarrierRenderSlot(meshData)
    val entity: Entity
    val ownedObject: SceneObject
    try {
      entity =
          Entity.create(
              Transform(bindings.poseFromViewer(carrierDistanceMeters)),
              Scale(Vector3(1f, 1f, 1f)),
              Visible(false),
          )
      ownedObject =
          SceneObject(
              bindings.scene,
              activeSlot.sceneMesh,
              "spatial_vr_strobe_full_field_carrier",
              entity,
          )
      bindings.scene.addObject(ownedObject)
    } catch (error: Throwable) {
      destroyCarrierRenderSlot(activeSlot)
      throw error
    }
    material = activeSlot.material
    mesh = activeSlot.sceneMesh
    triangleMesh = activeSlot.triangleMesh
    carrierEntity = entity
    sceneObject = ownedObject
    val carrierVisible = applyCarrierVisibility()
    bindings.marker(
        "channel=spatial-vr-strobe status=carrier-created referenceSpace=view-locked " +
            "depthTest=less-or-equal depthWrite=enabled blend=opaque initialOutput=black " +
            "panelForegroundMode=depth-separated-panel-over-active-carrier " +
            "panelVisible=$panelVisible carrierVisible=$carrierVisible " +
            "mesh=radial-spherical-cap-disc radialRings=${VrStrobeCarrierGeometry.RADIAL_RINGS} " +
            "angularSegments=${VrStrobeCarrierGeometry.ANGULAR_SEGMENTS} " +
            "triangleCount=${VrStrobeCarrierGeometry.TRIANGLE_COUNT} rasterSurfaceCount=1 " +
            "vertexShaderCurvature=true curvedMode=${carrierShape.curvedMode} " +
            "concavity=${carrierShape.concavity} gpuApi=spatial-sdk-vulkan " +
            "materialBuffers=1 visibleDrawCount=1 randomizeSubmit=bounded-active-slot-delta"
    )
  }

  private fun createCarrierRenderSlot(
      meshData: VrStrobeCarrierMeshData,
  ): VrStrobeCarrierRenderSlot {
    val ownedMaterial =
        SceneMaterial.custom(VrStrobeFeatureRoute.SHADER_NAME, materialAttributes()).apply {
          setSidedness(MaterialSidedness.DOUBLE_SIDED)
          setBlendMode(BlendMode.OPAQUE)
          setDepthTest(DepthTest.LESS_OR_EQUAL)
          setDepthWrite(DepthWrite.ENABLE)
          setAttribute("modeTime", Vector4(0f, 0f, 0f, 0f))
          clearProfileAttributes(this)
        }
    applyCarrierShape(ownedMaterial)
    var ownedTriangleMesh: TriangleMesh? = null
    var ownedMesh: SceneMesh? = null
    try {
      ownedTriangleMesh =
          TriangleMesh(
              VrStrobeCarrierGeometry.VERTEX_COUNT,
              meshData.indices.size,
              intArrayOf(0, meshData.indices.size),
              arrayOf(ownedMaterial),
          )
      ownedTriangleMesh.updateGeometry(
          0,
          meshData.positions,
          meshData.normals,
          meshData.uvs,
          meshData.colors,
      )
      ownedTriangleMesh.updatePrimitives(0, meshData.indices)
      ownedMesh = SceneMesh.fromTriangleMesh(ownedTriangleMesh, false)
      return VrStrobeCarrierRenderSlot(ownedMaterial, ownedTriangleMesh, ownedMesh)
    } catch (error: Throwable) {
      runCatching { ownedMesh?.destroy() }
      runCatching { ownedTriangleMesh?.destroy() }
      runCatching { ownedMaterial.destroy() }
      throw error
    }
  }

  private fun publishRandomizedCandidate(
      candidate: VrStrobeStimulusProfile,
      snapshot: VrStrobeSafetySnapshot,
      source: String,
      transactionId: Long,
  ) {
    val targetMaterial = requireNotNull(material) { "active-material-missing" }
    val updatePlan =
        when (candidate) {
          is VrStrobeStimulusProfile.Interference -> {
            setInterferenceProfile(candidate.profile, targetMaterial, "active-randomize")
            candidate.profile.materialUpdatePlan()
          }
          is VrStrobeStimulusProfile.Temporal -> {
            setTemporalProfile(candidate.profile, targetMaterial, "active-randomize")
            candidate.profile.materialUpdatePlan()
          }
        }
    applyOutput(snapshot, targetMaterial)
    rendererRevision += 1L
    pendingRendererUpdates +=
        VrStrobePendingRendererUpdate(
            transactionId = transactionId,
            rendererRevision = rendererRevision,
            submittedSceneTick = sceneTicks,
        )
    bindings.marker(
        "channel=spatial-vr-strobe status=randomize-renderer-update-submitted " +
            "transactionId=$transactionId source=${source.replace(' ', '_')} " +
            "sceneObjectMeshSwap=false materialTarget=active " +
            "updateMode=bounded-active-slot-delta uniformWrites=${updatePlan.uniformWriteCount} " +
            "activePatternCount=${updatePlan.activePatternCount} " +
            "fullProfileClear=${updatePlan.fullProfileClear} " +
            "inactivePatternSlotWrites=${updatePlan.inactivePatternSlotWrites} " +
            "rendererRevision=$rendererRevision visibleDrawCount=1"
    )
  }

  private fun applyCarrierShape(target: SceneMaterial? = material) {
    target?.setAttribute(
        "carrierShape",
        Vector4(
            carrierShape.curvedMode.floatValue(),
            carrierShape.concavity,
            VrStrobeConcavityPolicy.MAX_POLAR_ANGLE_RADIANS,
            VrStrobeCarrierGeometry.RADIUS_METERS,
        ),
    )
  }

  private fun setInterferenceProfile(
      profile: VrStrobeInterferenceProfile,
      target: SceneMaterial = requireNotNull(material),
      materialTarget: String = "active",
  ) {
    target.setAttribute("color1", profile.color1.vector())
    target.setAttribute("color2", profile.color2.vector())
    target.setAttribute("color3", profile.color3.vector())
    target.setAttribute(
        "colorAnim",
        Vector4(
            profile.oscillatorActive.floatValue(),
            profile.oscillatorFrequencyHz,
            profile.oscillatorShape,
            profile.colorCount.toFloat(),
        ),
    )
    target.setAttribute("global0", Vector4(profile.scale, profile.shearX, profile.shearY, profile.rotationSpeed))
    target.setAttribute(
        "global1",
        Vector4(profile.offsetX, profile.offsetY, profile.shakeAmplitude, profile.shakeFrequencyHz),
    )
    target.setAttribute("global2", Vector4(profile.stepFactor, 0f, 0f, 0f))
    target.setAttribute(
        "post0",
        Vector4(profile.trailAmount, profile.blurRadius, profile.glowStrength, profile.brightness),
    )
    target.setAttribute(
        "effects0",
        Vector4(profile.contrast, profile.noiseFrequency, profile.noiseStrength, profile.noiseBias),
    )
    target.setAttribute(
        "effects1",
        Vector4(profile.vignetteCenter, profile.vignetteEdge, profile.vignetteBias, 0f),
    )
    val gpuPlan = setPatterns(target, profile)
    bindings.marker(
        "channel=spatial-vr-strobe status=profile-applied kind=interference " +
            "profileId=${profile.id} stripes=${profile.patterns(VrStrobePatternKind.STRIPE).size} " +
            "ripples=${profile.patterns(VrStrobePatternKind.RIPPLE).size} " +
            "rays=${profile.patterns(VrStrobePatternKind.RAY).size} " +
            "perlins=${profile.patterns(VrStrobePatternKind.PERLIN).size} " +
            "activeGpuPatterns=${gpuPlan.activePatternCount} " +
            "maxSignalEvaluationsPerFragment=$VR_STROBE_MAX_SIGNAL_EVALUATIONS_PER_FRAGMENT " +
            "trailMapping=single-evaluation-palette-softening antiAlias=derivative-band-limited " +
            "sourceCommit=${VrStrobePresetCatalog.UPSTREAM_COMMIT} materialTarget=$materialTarget"
    )
  }

  private fun setTemporalProfile(
      profile: VrStrobeTemporalProfile,
      target: SceneMaterial = requireNotNull(material),
      materialTarget: String = "active",
  ) {
    target.setAttribute("color1", profile.color1.vector())
    target.setAttribute("color2", profile.color2.vector())
    target.setAttribute("color3", profile.fixationColor.vector())
    target.setAttribute(
        "strobe0",
        Vector4(
            profile.frequencyHz,
            profile.dutyPercent / 100f,
            if (profile.noiseType == VrStrobeNoiseType.PERLIN) 1f else 0f,
            profile.noiseResolution.toFloat(),
        ),
    )
    target.setAttribute(
        "strobe1",
        Vector4(
            profile.noisePhase1.floatValue(),
            profile.noiseAmplitude1,
            profile.noisePhase2.floatValue(),
            profile.noiseAmplitude2,
        ),
    )
    target.setAttribute(
        "strobe2",
        Vector4(
            profile.fixationEnabled.floatValue(),
            profile.fixationSize.toFloat(),
            0f,
            0f,
        ),
    )
    target.setAttribute("fixationColor", profile.fixationColor.vector())
    target.setAttribute("patternCounts", Vector4(0f))
    bindings.marker(
        "channel=spatial-vr-strobe status=profile-applied kind=temporal " +
            "profileId=${profile.id} frequencyHz=${profile.frequencyHz} dutyPercent=${profile.dutyPercent} " +
            "automaticTimeLimit=false sourceCommit=${VrStrobePresetCatalog.UPSTREAM_COMMIT} " +
            "materialTarget=$materialTarget"
    )
  }

  private fun setPatterns(
      target: SceneMaterial,
      profile: VrStrobeInterferenceProfile,
  ): VrStrobeGpuPlan {
    val gpuPlan = profile.gpuPlan()
    val prefixes =
        listOf(
            VrStrobePatternKind.STRIPE to "stripe",
            VrStrobePatternKind.RIPPLE to "ripple",
            VrStrobePatternKind.RAY to "ray",
            VrStrobePatternKind.PERLIN to "perlin",
        )
    prefixes.forEach { (kind, prefix) ->
      val patterns = profile.activeGpuPatterns(kind)
      patterns.forEachIndexed { index, pattern ->
        setPatternAttributes(target, prefix, index, pattern)
      }
    }
    target.setAttribute(
        "patternCounts",
        Vector4(
            gpuPlan.stripeCount.toFloat(),
            gpuPlan.rippleCount.toFloat(),
            gpuPlan.rayCount.toFloat(),
            gpuPlan.perlinCount.toFloat(),
        ),
    )
    return gpuPlan
  }

  private fun setPatternAttributes(
      target: SceneMaterial,
      prefix: String,
      index: Int,
      pattern: VrStrobePattern,
  ) {
    val value = pattern
    if (value.kind == VrStrobePatternKind.PERLIN) {
      target.setAttribute(
          "$prefix${index}A",
          Vector4(value.active.floatValue(), value.strength, value.perlinScale, value.perlinZSpeed),
      )
      target.setAttribute("$prefix${index}B", Vector4(value.pivotX, value.pivotY, value.perlinZOffset, 0f))
      target.setAttribute("$prefix${index}C", Vector4(0f))
      target.setAttribute("$prefix${index}D", Vector4(0f))
      target.setAttribute("$prefix${index}E", Vector4(0f))
      return
    }
    target.setAttribute(
        "$prefix${index}A",
        Vector4(value.active.floatValue(), value.strength, value.period, value.speed),
    )
    target.setAttribute(
        "$prefix${index}B",
        Vector4(value.pivotX, value.pivotY, value.distortFreq, value.distortAmp),
    )
    target.setAttribute(
        "$prefix${index}C",
        Vector4(value.distortSpeed, value.distMultPar, value.distMultOrth, value.waveFreq),
    )
    target.setAttribute(
        "$prefix${index}D",
        Vector4(value.waveAmp, value.waveShape, value.angle, value.rotationSpeed),
    )
    target.setAttribute(
        "$prefix${index}E",
        Vector4(value.extent, value.rotationPivotX, value.rotationPivotY, value.noiseMove),
    )
  }

  private fun applyOutput(
      snapshot: VrStrobeSafetySnapshot,
      target: SceneMaterial? = material,
  ) {
    target ?: return
    val mode =
        if (!snapshot.visualOutputActive) 0f
        else if (snapshot.outputKind == VrStrobeOutputKind.INTERFERENCE) 1f else 2f
    target.setAttribute(
        "modeTime",
        Vector4(mode, snapshot.elapsedSeconds, 0f, snapshot.visualOutputActive.floatValue()),
    )
  }

  private fun applyCarrierVisibility(): Boolean {
    val visible =
        VrStrobePanelForegroundPolicy.carrierVisible(stimulusSelected = stimulusSelected())
    sceneObject?.setIsVisible(visible)
    carrierEntity?.setComponent(Visible(visible))
    return visible
  }

  private fun destroyCarrierRenderSlot(slot: VrStrobeCarrierRenderSlot): Boolean {
    var cleanupComplete = true
    runCatching { slot.sceneMesh.destroy() }.onFailure { cleanupComplete = false }
    runCatching { slot.triangleMesh.destroy() }.onFailure { cleanupComplete = false }
    runCatching { slot.material.destroy() }.onFailure { cleanupComplete = false }
    return cleanupComplete
  }

  private fun destroyCarrier(reason: String) {
    val ownedObject = sceneObject
    val ownedMesh = mesh
    val ownedTriangleMesh = triangleMesh
    val ownedMaterial = material
    if (
        ownedObject == null &&
            ownedMesh == null &&
            ownedTriangleMesh == null &&
            ownedMaterial == null
    ) return
    var cleanupComplete = true
    ownedObject?.let { objectToDestroy ->
      cleanupComplete =
          runCatching {
                bindings.scene.destroyObject(objectToDestroy)
                true
              }
              .recoverCatching {
                objectToDestroy.destroy()
                true
              }
              .getOrDefault(false) && cleanupComplete
    }
    runCatching { ownedMesh?.destroy() }.onFailure { cleanupComplete = false }
    runCatching { ownedTriangleMesh?.destroy() }.onFailure { cleanupComplete = false }
    runCatching { ownedMaterial?.destroy() }.onFailure { cleanupComplete = false }
    sceneObject = null
    mesh = null
    triangleMesh = null
    material = null
    rendererRevision = 0L
    pendingRendererUpdates.clear()
    carrierEntity = null
    bindings.marker(
        "channel=spatial-vr-strobe status=cleanup reason=${reason.replace(' ', '_')} " +
            "cleanupComplete=$cleanupComplete output=absent"
    )
  }

  private fun report(source: String, snapshot: VrStrobeSafetySnapshot): VrStrobeSafetySnapshot {
    val decorated = decorate(snapshot)
    bindings.marker(VrStrobeFeatureRoute.safetyMarker(decorated, source))
    return decorated
  }

  private fun decorate(snapshot: VrStrobeSafetySnapshot): VrStrobeSafetySnapshot {
    val active = stimulusSelection.snapshot()
    return snapshot.copy(
          profileTitle =
              if (snapshot.profileId == "none") "Stimulus"
              else active?.profile?.title ?: snapshot.profileTitle,
          automaticTimeLimit = false,
          randomizeAvailable =
              snapshot.state in
                  setOf(
                      VrStrobeSafetyState.BLACK_LEAD_IN,
                      VrStrobeSafetyState.RUNNING,
                  ),
          distanceMeters = carrierDistanceMeters,
          curvedMode = carrierShape.curvedMode,
          concavity = carrierShape.concavity,
          carrierArcDegrees = carrierArcDegrees(),
          selectedPresetIndex = active?.presetIndex ?: -1,
          stimulusRevision = active?.revision ?: 0L,
      )
  }

  private fun materialAttributes(): Array<SceneMaterialAttribute> =
      buildList {
            listOf(
                    "carrierShape",
                    "modeTime",
                    "color1",
                    "color2",
                    "color3",
                    "colorAnim",
                    "global0",
                    "global1",
                    "global2",
                    "post0",
                    "effects0",
                    "effects1",
                    "strobe0",
                    "strobe1",
                    "strobe2",
                    "fixationColor",
                    "patternCounts",
                )
                .forEach { add(SceneMaterialAttribute(it, SceneMaterialDataType.Vector4)) }
            listOf("stripe", "ripple", "ray", "perlin").forEach { prefix ->
              repeat(VR_STROBE_MAX_PATTERN_INSTANCES) { index ->
                listOf("A", "B", "C", "D", "E").forEach { part ->
                  add(SceneMaterialAttribute("$prefix$index$part", SceneMaterialDataType.Vector4))
                }
              }
            }
          }
          .toTypedArray()

  private fun clearProfileAttributes(target: SceneMaterial) {
    listOf(
            "color1",
            "color2",
            "color3",
            "colorAnim",
            "global0",
            "global1",
            "global2",
            "post0",
            "effects0",
            "effects1",
            "strobe0",
            "strobe1",
            "strobe2",
            "fixationColor",
            "patternCounts",
        )
        .forEach { target.setAttribute(it, Vector4(0f)) }
    listOf("stripe", "ripple", "ray", "perlin").forEach { prefix ->
      repeat(VR_STROBE_MAX_PATTERN_INSTANCES) { index ->
        listOf("A", "B", "C", "D", "E").forEach { part ->
          target.setAttribute("$prefix$index$part", Vector4(0f))
        }
      }
    }
  }

  private fun VrStrobeColor.vector(): Vector4 = Vector4(red, green, blue, 1f)

  private fun Boolean.floatValue(): Float = if (this) 1f else 0f

  private fun carrierArcDegrees(): Float =
      if (carrierShape.curvedMode) {
        VrStrobeConcavityPolicy.polarAngleRadians(carrierShape.concavity) *
            360f /
            kotlin.math.PI.toFloat()
      } else {
        0f
      }

  companion object {
    const val MODULE_ID = "spatial-vr-strobe-coordinator"
  }
}
