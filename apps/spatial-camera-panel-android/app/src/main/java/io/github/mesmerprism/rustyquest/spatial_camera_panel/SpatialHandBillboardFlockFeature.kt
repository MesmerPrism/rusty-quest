package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.SystemClock
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.DepthTest
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.MaterialSidedness
import com.meta.spatial.runtime.Scene
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.SortOrder
import com.meta.spatial.runtime.TriangleMesh
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class SpatialHandBillboardFlockFeature(
    private val context: Context,
    private val marker: (String) -> Unit,
    private val surfaceTargetProvider: () -> String = { "" },
    private val probeProvider: () -> SpatialNativeInteropProbe,
) : SpatialFeature {
  override fun lateSystemsToRegister(): List<SystemBase> =
      listOf(
          SpatialHandBillboardFlockSystem(
              context.applicationContext,
              marker,
              surfaceTargetProvider,
              probeProvider,
          )
      )
}

private class SpatialHandBillboardFlockSystem(
    private val context: Context,
    private val marker: (String) -> Unit,
    private val surfaceTargetProvider: () -> String,
    private val probeProvider: () -> SpatialNativeInteropProbe,
) : SystemBase() {
  private val entities = mutableListOf<Entity>()
  private var batchedCloud: SpatialBatchedBillboardCloud? = null
  private var seedOffsets = emptyArray<Vector3>()
  private var phases = FloatArray(0)
  private var lastConfig = SpatialHandBillboardFlockConfig.disabled()
  private var lastFrameNanos = 0L
  private var lastStatusMs = 0L
  private var disabledLogged = false
  private var lastDisabledReason = ""
  private var liveSurface: SpatialLiveSkinnedHandSurface? = null
  private var liveSurfaceLoadAttempted = false
  private var liveSurfaceLoadStatus = "not-attempted"
  private var bridgeStartMask = 0L
  private var bridgeStartAttemptFrame = 0
  private var frameIndex = 0

  override fun execute() {
    frameIndex += 1
    val config = SpatialHandBillboardFlockConfig.read()
    val surfaceTargetId = currentSurfaceTargetId()
    val suppressedForIcosphere = surfaceTargetId == "icosphere"
    if (!config.enabled || suppressedForIcosphere) {
      val disabledReason = if (suppressedForIcosphere) "icosphere-surface-target" else "property-disabled"
      destroyPool(disabledReason)
      if (!disabledLogged || disabledReason != lastDisabledReason) {
        disabledLogged = true
        lastDisabledReason = disabledReason
        marker(
            "channel=spatial-hand-billboard-flock status=disabled " +
                "reason=$disabledReason surfaceTargetId=${markerToken(surfaceTargetId)} " +
                "module=$MODULE_ID enabledProperty=${SpatialHandBillboardFlockConfig.PROPERTY_ENABLED} " +
                "propertyEnabled=${config.enabled} icosphereSuppressed=$suppressedForIcosphere " +
                "visualMode=${config.visualMode.id} wireframeEnabled=${config.visualMode.wireframeEnabled} " +
                "wireframeWidthMeters=${formatFloat(config.wireframeWidthMeters)} " +
                "${config.wireframeSourceMarkerFields()} " +
                "spatialAvatarHandMeshWireframeSupported=false " +
                "directWorldSpace=true projectionPlane=false customGpuSkinning=false " +
                "couplingDynamics=false highRateJsonPayload=false"
        )
      }
      return
    }
    disabledLogged = false
    lastDisabledReason = ""

    val scene = getScene() ?: return
    val carrierNeedsRecreate =
        when (config.carrierMode) {
          SpatialHandBillboardCarrierMode.EcsEntities -> entities.size != config.count
          SpatialHandBillboardCarrierMode.BatchedSceneMesh -> batchedCloud == null
        }
    if (carrierNeedsRecreate || lastConfig.poolIdentityKey() != config.poolIdentityKey()) {
      createPool(config, scene)
    }
    lastConfig = config

    val nowNanos = SystemClock.elapsedRealtimeNanos()
    val dtSeconds =
        if (lastFrameNanos == 0L) {
          0.0f
        } else {
          ((nowNanos - lastFrameNanos).coerceAtMost(80_000_000L).toFloat() / 1_000_000_000.0f)
        }
    lastFrameNanos = nowNanos

    val viewerPose = runCatching { scene.getViewerPose() }.getOrNull() ?: Pose(Vector3(0.0f))
    if (config.source == SpatialHandBillboardSource.OpenXrLiveCustomMesh) {
      val sourceFrame = updateLiveSurfaceInput(config, viewerPose)
      val visible = sourceFrame != null && sourceFrame.activeHandCount > 0
      val frameStats =
          when (config.carrierMode) {
            SpatialHandBillboardCarrierMode.BatchedSceneMesh ->
                updateBatchedLiveSurfaceCarrier(config, sourceFrame, viewerPose, visible)
            SpatialHandBillboardCarrierMode.EcsEntities ->
                updateEntityLiveSurfaceCarrier(config, sourceFrame, viewerPose, visible)
          }
      maybeLogStatus(
          config = config,
          source =
              if (sourceFrame == null) "openxr-live-custom-mesh-unavailable"
              else "openxr-live-custom-mesh",
          activeHandCount = sourceFrame?.activeHandCount ?: 0,
          visible = visible,
          frameStats = frameStats,
          sourceMarkerFields =
              listOfNotNull(
                      "provider=XR_EXT_hand_tracking coordinateMapping=${markerToken(config.mappingProfile)} " +
                          "rowOrder=openxr-left-right meshPairing=asset-handedness " +
                          "orientationCorrection=none worldAnchorCorrection=false " +
                          "customCpuSkinning=true customGpuSkinning=false " +
                          "surfaceAnchors=triangle-barycentric replayFallbackActive=false " +
                          "liveSurfaceLoadStatus=${markerToken(liveSurfaceLoadStatus)} " +
                          "handMeshRigPackaged=${BuildConfig.HAND_MESH_RIG_PACKAGED}",
                      liveSurface?.markerFields(),
                      sourceFrame?.markerFields(),
                  )
                  .joinToString(" "),
      )
    } else {
      val anchors = findHandAnchors(viewerPose)
      val visible = anchors.poses.isNotEmpty()
      val frameStats =
          when (config.carrierMode) {
            SpatialHandBillboardCarrierMode.BatchedSceneMesh ->
                updateBatchedCarrier(config, anchors, viewerPose, dtSeconds, visible)
            SpatialHandBillboardCarrierMode.EcsEntities ->
                updateEntityCarrier(config, anchors, viewerPose, dtSeconds, visible)
          }
      maybeLogStatus(
          config,
          anchors.source,
          anchors.activeHandCount,
          visible,
          frameStats,
          "customCpuSkinning=false surfaceAnchors=anchor-offsets",
      )
    }
  }

  private fun updateLiveSurfaceInput(
      config: SpatialHandBillboardFlockConfig,
      viewerPose: Pose,
  ): SpatialLiveHandSurfaceFrame? {
    SpatialLiveHandJointBridge.updateSpatialViewerWorldBasis(viewerPose, config.mappingProfile)
    maybeStartBridge()
    if (!liveSurfaceLoadAttempted) {
      liveSurfaceLoadAttempted = true
      runCatching { SpatialLiveSkinnedHandSurface.load(context) }
          .onSuccess { surface ->
            liveSurface = surface
            liveSurfaceLoadStatus = "loaded"
            marker(
                "channel=spatial-hand-billboard-flock status=live-skinning-assets-loaded " +
                    "module=$MODULE_ID source=openxr-live-custom-mesh " +
                    "handMeshRigPackaged=${BuildConfig.HAND_MESH_RIG_PACKAGED} " +
                    "handMeshRigAssetRoot=${SpatialLiveSkinnedHandSurface.ASSET_ROOT} " +
                    "${surface.markerFields()} couplingDynamics=false highRateJsonPayload=false"
            )
          }
          .onFailure { throwable ->
            liveSurfaceLoadStatus = "missing-${markerToken(throwable.javaClass.simpleName)}"
            marker(
                "channel=spatial-hand-billboard-flock status=live-skinning-assets-unavailable " +
                    "module=$MODULE_ID source=openxr-live-custom-mesh " +
                    "handMeshRigPackaged=${BuildConfig.HAND_MESH_RIG_PACKAGED} " +
                    "handMeshRigAssetRoot=${SpatialLiveSkinnedHandSurface.ASSET_ROOT} " +
                    "fallback=joint-visuals-only error=${markerToken(throwable.javaClass.simpleName)} " +
                    "couplingDynamics=false highRateJsonPayload=false"
            )
          }
    }
    val rows = SpatialLiveHandJointBridge.pollRows() ?: return null
    return liveSurface?.snapshot(rows, config.count, config.normalOffsetMeters)
  }

  private fun maybeStartBridge() {
    if (bridgeStartMask != 0L && frameIndex - bridgeStartAttemptFrame < 120) return
    bridgeStartAttemptFrame = frameIndex
    val probe = runCatching { probeProvider() }.getOrNull() ?: return
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      return
    }
    bridgeStartMask = SpatialLiveHandJointBridge.ensureStarted(probe)
  }

  private fun updateEntityLiveSurfaceCarrier(
      config: SpatialHandBillboardFlockConfig,
      frame: SpatialLiveHandSurfaceFrame?,
      viewerPose: Pose,
      visible: Boolean,
  ): SpatialHandBillboardFrameStats {
    var entityIndex = 0
    var transformWrites = 0
    var visibleWrites = 0
    for (handIndex in 0 until PUBLIC_BILLBOARD_CARRIER_COUNT) {
      val hand = frame?.hands?.getOrNull(handIndex) ?: continue
      for (position in hand.positions) {
        val entity = entities.getOrNull(entityIndex) ?: break
        entity.setComponent(Transform(Pose(position, viewerPose.q)))
        entity.setComponent(
            Scale(Vector3(config.billboardMeters, config.billboardMeters, config.billboardMeters))
        )
        entity.setComponent(Visible(visible))
        entityIndex += 1
        transformWrites += 1
        visibleWrites += 1
      }
    }
    while (entityIndex < entities.size) {
      entities[entityIndex].setComponent(Visible(false))
      entityIndex += 1
      visibleWrites += 1
    }
    return SpatialHandBillboardFrameStats(
        transformWrites = transformWrites,
        visibleWrites = visibleWrites,
        carrierEntityCount = entities.size,
    )
  }

  private fun updateBatchedLiveSurfaceCarrier(
      config: SpatialHandBillboardFlockConfig,
      frame: SpatialLiveHandSurfaceFrame?,
      viewerPose: Pose,
      visible: Boolean,
  ): SpatialHandBillboardFrameStats {
    val cloud = batchedCloud ?: return SpatialHandBillboardFrameStats.empty()
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = forward.cross(up).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val billboardNormal = forward * -1.0f
    val halfSize = config.billboardMeters * 0.5f
    val counts = IntArray(cloud.carrierEntityCount)
    var globalIndex = 0
    cloud.beginFrame()
    for (handIndex in 0 until cloud.carrierEntityCount) {
      val hand = frame?.hands?.getOrNull(handIndex) ?: continue
      counts[handIndex] = hand.positions.size
      for (particleIndex in hand.positions.indices) {
        cloud.setParticle(
            handIndex = handIndex,
            particleIndex = particleIndex,
            center = hand.positions[particleIndex],
            billboardNormal = billboardNormal,
            right = right,
            up = up,
            halfSize = halfSize,
            color = publicBillboardColor(globalIndex, config.count, phases[globalIndex]),
        )
        globalIndex += 1
      }
    }
    val submitStats = cloud.submit(counts, visible)
    return SpatialHandBillboardFrameStats(
        carrierEntityCount = cloud.carrierEntityCount,
        meshGeometryUpdates = submitStats.geometryUpdates,
        meshPrimitiveUpdates = submitStats.primitiveUpdates,
        sceneObjectVisibleWrites = submitStats.sceneObjectVisibleWrites,
    )
  }

  private fun updateEntityCarrier(
      config: SpatialHandBillboardFlockConfig,
      anchors: HandAnchorSnapshot,
      viewerPose: Pose,
      dtSeconds: Float,
      visible: Boolean,
  ): SpatialHandBillboardFrameStats {
    val billboardRotation = viewerPose.q
    var transformWrites = 0
    var visibleWrites = 0
    for (i in entities.indices) {
      phases[i] = wrapPhase(phases[i] + dtSeconds * config.driftHz * (1.0f + (i % 7) * 0.035f))
      val anchor = anchors.poses[i % anchors.poses.size]
      val phase = phases[i] * TWO_PI
      val wobble =
          Vector3(
              cos(phase.toDouble()).toFloat() * config.driftMeters,
              sin((phase * 0.73f).toDouble()).toFloat() * config.driftMeters,
              sin((phase * 1.19f).toDouble()).toFloat() * config.driftMeters * 0.65f,
      )
      val position = anchor.t + seedOffsets[i] + wobble
      entities[i].setComponent(Transform(Pose(position, billboardRotation)))
      transformWrites += 1
      entities[i].setComponent(Visible(visible))
      visibleWrites += 1
    }
    return SpatialHandBillboardFrameStats(
        transformWrites = transformWrites,
        visibleWrites = visibleWrites,
        carrierEntityCount = entities.size,
    )
  }

  private fun updateBatchedCarrier(
      config: SpatialHandBillboardFlockConfig,
      anchors: HandAnchorSnapshot,
      viewerPose: Pose,
      dtSeconds: Float,
      visible: Boolean,
  ): SpatialHandBillboardFrameStats {
    val cloud = batchedCloud ?: return SpatialHandBillboardFrameStats.empty()
    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val up = viewerPose.up().normalizedOr(Vector3(0.0f, 1.0f, 0.0f))
    val right = forward.cross(up).normalizedOr(Vector3(1.0f, 0.0f, 0.0f))
    val billboardNormal = forward * -1.0f
    val halfSize = config.billboardMeters * 0.5f
    cloud.beginFrame()
    for (i in 0 until config.count) {
      phases[i] = wrapPhase(phases[i] + dtSeconds * config.driftHz * (1.0f + (i % 7) * 0.035f))
      val anchor = anchors.poses[i % anchors.poses.size]
      val phase = phases[i] * TWO_PI
      val wobble =
          Vector3(
              cos(phase.toDouble()).toFloat() * config.driftMeters,
              sin((phase * 0.73f).toDouble()).toFloat() * config.driftMeters,
              sin((phase * 1.19f).toDouble()).toFloat() * config.driftMeters * 0.65f,
          )
      val position = anchor.t + seedOffsets[i] + wobble
      cloud.setParticle(
          handIndex = i % cloud.carrierEntityCount,
          particleIndex = i / cloud.carrierEntityCount,
          center = position,
          billboardNormal = billboardNormal,
          right = right,
          up = up,
          halfSize = halfSize,
          color = publicBillboardColor(i, config.count, phases[i]),
      )
    }
    val counts = IntArray(cloud.carrierEntityCount) { carrierIndex ->
      config.count / cloud.carrierEntityCount + if (carrierIndex < config.count % cloud.carrierEntityCount) 1 else 0
    }
    val submitStats = cloud.submit(counts, visible)
    return SpatialHandBillboardFrameStats(
        carrierEntityCount = cloud.carrierEntityCount,
        meshGeometryUpdates = submitStats.geometryUpdates,
        meshPrimitiveUpdates = submitStats.primitiveUpdates,
        sceneObjectVisibleWrites = submitStats.sceneObjectVisibleWrites,
    )
  }

  private fun createPool(config: SpatialHandBillboardFlockConfig, scene: Scene) {
    destroyPool("recreate")
    seedOffsets = Array(config.count) { index -> seededOffset(index, config.count, config.spreadMeters) }
    phases = FloatArray(config.count) { index -> ((index * 37) % 257) / 257.0f }

    when (config.carrierMode) {
      SpatialHandBillboardCarrierMode.BatchedSceneMesh -> {
        batchedCloud = SpatialBatchedBillboardCloud.create(scene, config)
      }
      SpatialHandBillboardCarrierMode.EcsEntities -> {
        repeat(config.count) {
          val entity =
              Entity.create(
                  Mesh(Uri.parse("mesh://box"), MeshCollision.NoCollision),
                  Box(Vector3(-0.5f, -0.5f, 0.0f), Vector3(0.5f, 0.5f, 0.001f)),
                  Material().apply {
                    baseColor = Color4(0.22f, 0.76f, 1.0f, 0.72f)
                    unlit = true
                  },
                  Transform(Pose(Vector3(0.0f))),
                  Scale(Vector3(config.billboardMeters, config.billboardMeters, config.billboardMeters)),
                  Visible(false),
              )
          entities.add(entity)
        }
      }
    }

    val carrierEntityCount =
        when (config.carrierMode) {
          SpatialHandBillboardCarrierMode.BatchedSceneMesh -> batchedCloud?.carrierEntityCount ?: 0
          SpatialHandBillboardCarrierMode.EcsEntities -> entities.size
        }
    marker(
        "channel=spatial-hand-billboard-flock status=pool-created " +
            "module=$MODULE_ID entityCount=${config.count} visualParticleCount=${config.count} " +
            "requestedSource=${config.source.id} " +
            "carrier=${config.carrierMode.id} carrierEntityCount=$carrierEntityCount " +
            "depthTest=${config.depthTestMode.id} depthWrite=false " +
            "mesh=${config.carrierMode.meshMarker} collision=none unlit=true " +
            "visualMode=${config.visualMode.id} wireframeEnabled=${config.visualMode.wireframeEnabled} " +
            "wireframeWidthMeters=${formatFloat(config.wireframeWidthMeters)} " +
            "${config.wireframeSourceMarkerFields()} " +
            "appOwnedTriangleMeshWireframe=${config.carrierMode == SpatialHandBillboardCarrierMode.BatchedSceneMesh && config.visualMode.wireframeEnabled} " +
            "spatialAvatarHandMeshWireframeSupported=false spatialBuiltInHandWireframeAuthority=sdk-owned-avatar-system-not-app-owned " +
            "sharedMaterialConfig=true persistentCarriers=true spawnDestroyPerFrame=false " +
            "simulationState=system-arrays publicParticleState=true " +
            "directWorldSpace=true projectionPlane=false customGpuSkinning=false " +
            "couplingDynamics=false highRateJsonPayload=false"
    )
  }

  private fun destroyPool(reason: String) {
    if (entities.isEmpty() && batchedCloud == null) {
      return
    }
    val destroyedBatchedCarriers = batchedCloud?.carrierEntityCount ?: 0
    runCatching { batchedCloud?.destroy() }
    batchedCloud = null
    for (entity in entities) {
      runCatching { entity.destroy() }
    }
    val destroyed = entities.size
    entities.clear()
    seedOffsets = emptyArray()
    phases = FloatArray(0)
    lastFrameNanos = 0L
    marker(
        "channel=spatial-hand-billboard-flock status=pool-destroyed " +
            "module=$MODULE_ID reason=${markerToken(reason)} destroyedEntityCount=$destroyed " +
            "destroyedBatchedCarrierEntityCount=$destroyedBatchedCarriers"
    )
  }

  private fun findHandAnchors(viewerPose: Pose): HandAnchorSnapshot {
    val handPoses = mutableListOf<Pose>()
    runCatching {
          Query.where { has(AvatarBody.id) }
              .eval()
              .forEach { entity ->
                val avatarBody = entity.tryGetComponent<AvatarBody>() ?: return@forEach
                val localPlayer =
                    runCatching { entity.isLocal() }.getOrDefault(true) && avatarBody.isPlayerControlled
                if (!localPlayer) {
                  return@forEach
                }
                avatarBody.leftHand.tryGetComponent<Transform>()?.transform?.let { handPoses.add(it) }
                avatarBody.rightHand.tryGetComponent<Transform>()?.transform?.let { handPoses.add(it) }
              }
        }
        .onFailure { throwable ->
          marker(
              "channel=spatial-hand-billboard-flock status=hand-anchor-query-failed " +
                  "module=$MODULE_ID source=spatial-sdk-avatar-body-hand-entities " +
                  "error=${markerToken(throwable.javaClass.simpleName)}"
          )
        }

    if (handPoses.isNotEmpty()) {
      return HandAnchorSnapshot(
          poses = handPoses,
          activeHandCount = handPoses.size,
          source = "spatial-sdk-avatar-body-hand-entities",
      )
    }

    val forward = viewerPose.forward().normalizedOr(Vector3(0.0f, 0.0f, -1.0f))
    val fallback = Pose(viewerPose.t + forward * 0.72f, viewerPose.q)
    return HandAnchorSnapshot(
        poses = listOf(fallback),
        activeHandCount = 0,
        source = "viewer-forward-fallback",
    )
  }

  private fun currentSurfaceTargetId(): String =
      runCatching { surfaceTargetProvider() }
          .getOrDefault("")
          .trim()
          .lowercase(Locale.US)

  private fun maybeLogStatus(
      config: SpatialHandBillboardFlockConfig,
      source: String,
      activeHandCount: Int,
      visible: Boolean,
      frameStats: SpatialHandBillboardFrameStats,
      sourceMarkerFields: String,
  ) {
    val nowMs = SystemClock.elapsedRealtime()
    if (nowMs - lastStatusMs < STATUS_INTERVAL_MS) {
      return
    }
    lastStatusMs = nowMs
    marker(
            "channel=spatial-hand-billboard-flock status=world-space-updated " +
            "module=$MODULE_ID source=${markerToken(source)} requestedSource=${config.source.id} " +
            "activeHandCount=$activeHandCount " +
            "entityCount=${config.count} visualParticleCount=${config.count} " +
            "carrier=${config.carrierMode.id} carrierEntityCount=${frameStats.carrierEntityCount} " +
            "transformWrites=${frameStats.transformWrites} visibleWrites=${frameStats.visibleWrites} " +
            "meshGeometryUpdates=${frameStats.meshGeometryUpdates} " +
            "meshPrimitiveUpdates=${frameStats.meshPrimitiveUpdates} " +
            "sceneObjectVisibleWrites=${frameStats.sceneObjectVisibleWrites} visible=$visible " +
            "depthTest=${config.depthTestMode.id} depthWrite=false " +
            "visualMode=${config.visualMode.id} wireframeEnabled=${config.visualMode.wireframeEnabled} " +
            "wireframeWidthMeters=${formatFloat(config.wireframeWidthMeters)} " +
            "${config.wireframeSourceMarkerFields()} " +
            "appOwnedTriangleMeshWireframe=${config.carrierMode == SpatialHandBillboardCarrierMode.BatchedSceneMesh && config.visualMode.wireframeEnabled} " +
            "spatialAvatarHandMeshWireframeSupported=false " +
            "sharedBillboardRotation=true perEntityLookAt=false directWorldSpace=true " +
            "projectionPlane=false customGpuSkinning=false couplingDynamics=false " +
            "highRateJsonPayload=false $sourceMarkerFields"
    )
  }

  private fun seededOffset(index: Int, count: Int, spreadMeters: Float): Vector3 {
    val goldenAngle = PI * (3.0 - sqrt(5.0))
    val normalizedCount = max(1, count)
    val y = 1.0 - (2.0 * (index + 0.5) / normalizedCount)
    val radius = sqrt(max(0.0, 1.0 - y * y))
    val theta = goldenAngle * index
    return Vector3(
        (cos(theta) * radius * spreadMeters).toFloat(),
        (y * spreadMeters).toFloat(),
        (sin(theta) * radius * spreadMeters).toFloat(),
    )
  }

  companion object {
    private const val MODULE_ID = "spatial-sdk-world-hand-billboard-flock"
    private const val STATUS_INTERVAL_MS = 1000L
    private const val TWO_PI = (PI * 2.0).toFloat()

    private fun wrapPhase(value: Float): Float {
      val wrapped = value % 1.0f
      return if (wrapped < 0.0f) wrapped + 1.0f else wrapped
    }
  }
}

private data class SpatialHandBillboardFrameStats(
    val transformWrites: Int = 0,
    val visibleWrites: Int = 0,
    val carrierEntityCount: Int = 0,
    val meshGeometryUpdates: Int = 0,
    val meshPrimitiveUpdates: Int = 0,
    val sceneObjectVisibleWrites: Int = 0,
) {
  companion object {
    fun empty(): SpatialHandBillboardFrameStats = SpatialHandBillboardFrameStats()
  }
}

private data class SpatialBatchedBillboardSubmitStats(
    val geometryUpdates: Int = 0,
    val primitiveUpdates: Int = 0,
    val sceneObjectVisibleWrites: Int = 0,
)

@OptIn(SpatialSDKExperimentalAPI::class)
private class SpatialBatchedBillboardCloud private constructor(
    private val hands: Array<SpatialBatchedBillboardHandMesh>,
    private val material: SceneMaterial,
    private val texture: SceneTexture,
) {
  val carrierEntityCount: Int
    get() = hands.size

  fun beginFrame() {
    hands.forEach { it.beginFrame() }
  }

  fun setParticle(
      handIndex: Int,
      particleIndex: Int,
      center: Vector3,
      billboardNormal: Vector3,
      right: Vector3,
      up: Vector3,
      halfSize: Float,
      color: Int,
  ) {
    hands.getOrNull(handIndex)?.setParticle(particleIndex, center, billboardNormal, right, up, halfSize, color)
  }

  fun submit(counts: IntArray, visible: Boolean): SpatialBatchedBillboardSubmitStats {
    var geometryUpdates = 0
    var primitiveUpdates = 0
    var visibleWrites = 0
    for (handIndex in hands.indices) {
      val stats = hands[handIndex].submit(counts.getOrElse(handIndex) { 0 }, visible)
      geometryUpdates += stats.geometryUpdates
      primitiveUpdates += stats.primitiveUpdates
      visibleWrites += stats.sceneObjectVisibleWrites
    }
    return SpatialBatchedBillboardSubmitStats(geometryUpdates, primitiveUpdates, visibleWrites)
  }

  fun destroy() {
    hands.forEach { it.destroy() }
    runCatching { material.destroy() }
    runCatching { texture.destroy() }
  }

  companion object {
    fun create(scene: Scene, config: SpatialHandBillboardFlockConfig): SpatialBatchedBillboardCloud {
      val texture = SceneTexture(AndroidColor.valueOf(1.0f, 1.0f, 1.0f, 1.0f))
      val material =
          SceneMaterial(texture, AlphaMode.TRANSLUCENT, SceneMaterial.UNLIT_SHADER).apply {
            setUnlit(true)
            setSidedness(MaterialSidedness.DOUBLE_SIDED)
            setBlendMode(BlendMode.TRANSLUCENT)
            setDepthWrite(DepthWrite.DISABLE)
            setDepthTest(config.depthTestMode.depthTest)
            setSortOrder(SortOrder.TRANSLUCENT)
            setRenderOrder(PUBLIC_BILLBOARD_RENDER_ORDER)
          }
      val capacityPerCarrier = ((config.count + PUBLIC_BILLBOARD_CARRIER_COUNT - 1) / PUBLIC_BILLBOARD_CARRIER_COUNT).coerceAtLeast(1)
      return SpatialBatchedBillboardCloud(
          hands =
              Array(PUBLIC_BILLBOARD_CARRIER_COUNT) { index ->
                SpatialBatchedBillboardHandMesh.create(
                    scene,
                    "carrier-$index",
                    capacityPerCarrier,
                    material,
                    config.visualMode,
                    config.wireframeWidthMeters,
                )
              },
          material = material,
          texture = texture,
      )
    }
  }
}

@OptIn(SpatialSDKExperimentalAPI::class)
private class SpatialBatchedBillboardHandMesh private constructor(
    private val sceneObject: SceneObject,
    private val sceneMesh: SceneMesh,
    private val triangleMesh: TriangleMesh,
    private val positions: FloatArray,
    private val normals: FloatArray,
    private val uvs: FloatArray,
    private val colors: IntArray,
    private val indices: IntArray,
    private val capacity: Int,
    private val visualMode: SpatialHandBillboardVisualMode,
    private val wireframeWidthMeters: Float,
) {
  private var activeIndices: IntArray = indices
  private var lastIndexCount = indices.size
  private var visible = false
  private val verticesPerParticle: Int =
      if (visualMode == SpatialHandBillboardVisualMode.WireframeEdges) 16 else 4
  private val indicesPerParticle: Int =
      if (visualMode == SpatialHandBillboardVisualMode.WireframeEdges) 24 else 6

  fun beginFrame() {}

  fun setParticle(
      particleIndex: Int,
      center: Vector3,
      billboardNormal: Vector3,
      right: Vector3,
      up: Vector3,
      halfSize: Float,
      color: Int,
  ) {
    if (particleIndex !in 0 until capacity) {
      return
    }
    val vertexBase = particleIndex * verticesPerParticle
    val positionBase = vertexBase * 3
    val rightX = right.x * halfSize
    val rightY = right.y * halfSize
    val rightZ = right.z * halfSize
    val upX = up.x * halfSize
    val upY = up.y * halfSize
    val upZ = up.z * halfSize

    val c0 = floatArrayOf(center.x - rightX - upX, center.y - rightY - upY, center.z - rightZ - upZ)
    val c1 = floatArrayOf(center.x + rightX - upX, center.y + rightY - upY, center.z + rightZ - upZ)
    val c2 = floatArrayOf(center.x + rightX + upX, center.y + rightY + upY, center.z + rightZ + upZ)
    val c3 = floatArrayOf(center.x - rightX + upX, center.y - rightY + upY, center.z - rightZ + upZ)

    if (visualMode == SpatialHandBillboardVisualMode.WireframeEdges) {
      val lineHalf = min(wireframeWidthMeters * 0.5f, halfSize * 0.35f).coerceAtLeast(0.00035f)
      val upOffset = floatArrayOf(up.x * lineHalf, up.y * lineHalf, up.z * lineHalf)
      val rightOffset = floatArrayOf(right.x * lineHalf, right.y * lineHalf, right.z * lineHalf)
      writeLineQuad(positionBase, 0, c0, c1, upOffset)
      writeLineQuad(positionBase, 1, c1, c2, rightOffset)
      writeLineQuad(positionBase, 2, c2, c3, upOffset)
      writeLineQuad(positionBase, 3, c3, c0, rightOffset)
    } else {
      writePosition(positionBase, c0[0], c0[1], c0[2])
      writePosition(positionBase + 3, c1[0], c1[1], c1[2])
      writePosition(positionBase + 6, c2[0], c2[1], c2[2])
      writePosition(positionBase + 9, c3[0], c3[1], c3[2])
    }

    for (vertexOffset in 0 until verticesPerParticle) {
      val normalBase = (vertexBase + vertexOffset) * 3
      normals[normalBase] = billboardNormal.x
      normals[normalBase + 1] = billboardNormal.y
      normals[normalBase + 2] = billboardNormal.z
      colors[vertexBase + vertexOffset] = color
    }
  }

  private fun writeLineQuad(
      positionBase: Int,
      edgeIndex: Int,
      a: FloatArray,
      b: FloatArray,
      offset: FloatArray,
  ) {
    val base = positionBase + edgeIndex * 12
    writePosition(base, a[0] - offset[0], a[1] - offset[1], a[2] - offset[2])
    writePosition(base + 3, b[0] - offset[0], b[1] - offset[1], b[2] - offset[2])
    writePosition(base + 6, b[0] + offset[0], b[1] + offset[1], b[2] + offset[2])
    writePosition(base + 9, a[0] + offset[0], a[1] + offset[1], a[2] + offset[2])
  }

  fun submit(requestedParticleCount: Int, shouldBeVisible: Boolean): SpatialBatchedBillboardSubmitStats {
    val activeCount = requestedParticleCount.coerceIn(0, capacity)
    val vertexCount = activeCount * verticesPerParticle
    val indexCount = activeCount * indicesPerParticle
    var geometryUpdates = 0
    var primitiveUpdates = 0
    var visibleWrites = 0
    if (vertexCount > 0) {
      triangleMesh.updateGeometry(0, positions, normals, uvs, colors)
      geometryUpdates += 1
    }
    if (indexCount != lastIndexCount) {
      activeIndices = indices.copyOf(indexCount)
      triangleMesh.updatePrimitives(0, activeIndices)
      primitiveUpdates += 1
      lastIndexCount = indexCount
    }
    if (vertexCount > 0 || primitiveUpdates > 0) {
      sceneMesh.updateWithTriangleMesh(triangleMesh, false)
    }
    if (visible != shouldBeVisible) {
      sceneObject.setIsVisible(shouldBeVisible)
      visible = shouldBeVisible
      visibleWrites += 1
    }
    return SpatialBatchedBillboardSubmitStats(geometryUpdates, primitiveUpdates, visibleWrites)
  }

  fun destroy() {
    runCatching { sceneObject.destroy() }
    runCatching { sceneMesh.destroy() }
    runCatching { triangleMesh.destroy() }
  }

  private fun writePosition(base: Int, x: Float, y: Float, z: Float) {
    positions[base] = x
    positions[base + 1] = y
    positions[base + 2] = z
  }

  companion object {
    fun create(
        scene: Scene,
        label: String,
        capacity: Int,
        material: SceneMaterial,
        visualMode: SpatialHandBillboardVisualMode,
        wireframeWidthMeters: Float,
    ): SpatialBatchedBillboardHandMesh {
      val verticesPerParticle = if (visualMode == SpatialHandBillboardVisualMode.WireframeEdges) 16 else 4
      val indicesPerParticle = if (visualMode == SpatialHandBillboardVisualMode.WireframeEdges) 24 else 6
      val vertexCapacity = capacity * verticesPerParticle
      val indexCapacity = capacity * indicesPerParticle
      val positions = FloatArray(vertexCapacity * 3)
      val normals = FloatArray(vertexCapacity * 3)
      val uvs = FloatArray(vertexCapacity * 2)
      val colors = IntArray(vertexCapacity) { AndroidColor.WHITE }
      val indices = IntArray(indexCapacity)
      for (particleIndex in 0 until capacity) {
        val vertexBase = particleIndex * verticesPerParticle
        for (vertexOffset in 0 until verticesPerParticle) {
          val uvBase = (vertexBase + vertexOffset) * 2
          val corner = vertexOffset % 4
          uvs[uvBase] = if (corner == 1 || corner == 2) 1.0f else 0.0f
          uvs[uvBase + 1] = if (corner == 0 || corner == 1) 1.0f else 0.0f
        }
        val indexBase = particleIndex * indicesPerParticle
        if (visualMode == SpatialHandBillboardVisualMode.WireframeEdges) {
          for (edgeIndex in 0 until 4) {
            val edgeVertexBase = vertexBase + edgeIndex * 4
            val edgeIndexBase = indexBase + edgeIndex * 6
            indices[edgeIndexBase] = edgeVertexBase
            indices[edgeIndexBase + 1] = edgeVertexBase + 1
            indices[edgeIndexBase + 2] = edgeVertexBase + 2
            indices[edgeIndexBase + 3] = edgeVertexBase
            indices[edgeIndexBase + 4] = edgeVertexBase + 2
            indices[edgeIndexBase + 5] = edgeVertexBase + 3
          }
        } else {
          indices[indexBase] = vertexBase
          indices[indexBase + 1] = vertexBase + 1
          indices[indexBase + 2] = vertexBase + 2
          indices[indexBase + 3] = vertexBase
          indices[indexBase + 4] = vertexBase + 2
          indices[indexBase + 5] = vertexBase + 3
        }
      }
      val triangleMesh =
          TriangleMesh(vertexCapacity, indexCapacity, intArrayOf(0, indexCapacity), arrayOf(material))
      triangleMesh.updateGeometry(0, positions, normals, uvs, colors)
      triangleMesh.updatePrimitives(0, indices)
      val sceneMesh = SceneMesh.fromTriangleMesh(triangleMesh, false)
      val entity = Entity.create(Transform(Pose(Vector3(0.0f, 0.0f, 0.0f))))
      val sceneObject = SceneObject(scene, sceneMesh, "spatial-hand-billboard-flock-$label", entity)
      scene.addObject(sceneObject)
      sceneObject.setIsVisible(false)
      return SpatialBatchedBillboardHandMesh(
          sceneObject = sceneObject,
          sceneMesh = sceneMesh,
          triangleMesh = triangleMesh,
          positions = positions,
          normals = normals,
          uvs = uvs,
          colors = colors,
          indices = indices,
          capacity = capacity,
          visualMode = visualMode,
          wireframeWidthMeters = wireframeWidthMeters,
      )
    }
  }
}

private data class HandAnchorSnapshot(
    val poses: List<Pose>,
    val activeHandCount: Int,
    val source: String,
)

private data class SpatialHandBillboardFlockConfig(
    val enabled: Boolean,
    val source: SpatialHandBillboardSource,
    val count: Int,
    val billboardMeters: Float,
    val wireframeWidthMeters: Float,
    val normalOffsetMeters: Float,
    val spreadMeters: Float,
    val driftMeters: Float,
    val driftHz: Float,
    val mappingProfile: String,
    val depthTestMode: SpatialHandBillboardDepthTestMode,
    val carrierMode: SpatialHandBillboardCarrierMode,
    val visualMode: SpatialHandBillboardVisualMode,
    val wireframeSource: SpatialHandBillboardWireframeSource,
) {
  fun poolIdentityKey(): String =
      "${source.id}|${count}|${formatFloat(billboardMeters)}|${formatFloat(wireframeWidthMeters)}|${formatFloat(normalOffsetMeters)}|${formatFloat(spreadMeters)}|${depthTestMode.id}|${carrierMode.id}|${visualMode.id}|${wireframeSource.id}"

  fun wireframeSourceMarkerFields(): String =
      "wireframeSourceProperty=$PROPERTY_WIREFRAME_SOURCE " +
          "wireframeRequestedSource=${wireframeSource.id} " +
          "wireframeResolvedSource=${wireframeSource.resolvedSource} " +
          "wireframeRequestedSourceAvailable=${wireframeSource.exactSourceAvailable} " +
          "wireframeHotload=true openXrFbMeshWireframeSupported=false " +
          "customHandMeshWireframeSupported=false avatarSystemPublicMeshWireframeSupported=false " +
          "spatialProxyWireframeSupported=true"

  companion object {
    const val PROPERTY_ENABLED = "debug.rustyquest.spatial.hand_billboard_flock.enabled"
    private const val PROPERTY_SOURCE = "debug.rustyquest.spatial.hand_billboard_flock.source"
    private const val PROPERTY_CARRIER = "debug.rustyquest.spatial.hand_billboard_flock.carrier"
    private const val PROPERTY_VISUAL_MODE = "debug.rustyquest.spatial.hand_billboard_flock.visual_mode"
    private const val PROPERTY_WIREFRAME_SOURCE =
        "debug.rustyquest.spatial.hand_billboard_flock.wireframe.source"
    private const val PROPERTY_COUNT = "debug.rustyquest.spatial.hand_billboard_flock.count"
    private const val PROPERTY_BILLBOARD_METERS =
        "debug.rustyquest.spatial.hand_billboard_flock.billboard_meters"
    private const val PROPERTY_WIREFRAME_WIDTH_METERS =
        "debug.rustyquest.spatial.hand_billboard_flock.wireframe.width_m"
    private const val PROPERTY_NORMAL_OFFSET_METERS =
        "debug.rustyquest.spatial.hand_billboard_flock.normal_offset_m"
    private const val PROPERTY_SPREAD_METERS =
        "debug.rustyquest.spatial.hand_billboard_flock.spread_meters"
    private const val PROPERTY_DRIFT_METERS =
        "debug.rustyquest.spatial.hand_billboard_flock.drift_meters"
    private const val PROPERTY_DRIFT_HZ = "debug.rustyquest.spatial.hand_billboard_flock.drift_hz"
    private const val PROPERTY_MAPPING_PROFILE =
        "debug.rustyquest.spatial.hand_alignment.mapping_profile"
    private const val PROPERTY_DEPTH_TEST =
        "debug.rustyquest.spatial.hand_billboard_flock.render.depth_test"

    fun disabled(): SpatialHandBillboardFlockConfig =
        SpatialHandBillboardFlockConfig(
            false,
            SpatialHandBillboardSource.SpatialSdkAnchorFlock,
            64,
            0.022f,
            0.0035f,
            0.0f,
            0.085f,
            0.008f,
            0.42f,
            SpatialLiveHandJointBridge.VIEWER_WORLD_MAPPING_PROFILE_ACCEPTED,
            SpatialHandBillboardDepthTestMode.LessOrEqual,
            SpatialHandBillboardCarrierMode.BatchedSceneMesh,
            SpatialHandBillboardVisualMode.FilledBillboards,
            SpatialHandBillboardWireframeSource.SpatialSdkJointProxy,
        )

    fun read(): SpatialHandBillboardFlockConfig {
      val source =
          SpatialHandBillboardSource.parse(
              readSystemProperty(PROPERTY_SOURCE).ifBlank {
                BuildConfig.HAND_BILLBOARD_SOURCE_DEFAULT
              }
          )
      return SpatialHandBillboardFlockConfig(
            enabled =
                readBooleanSystemProperty(
                    PROPERTY_ENABLED,
                    BuildConfig.HAND_BILLBOARD_FLOCK_ENABLED_DEFAULT,
                ),
            source = source,
            count =
                readIntSystemProperty(
                    PROPERTY_COUNT,
                    if (source == SpatialHandBillboardSource.OpenXrLiveCustomMesh) 2048 else 64,
                    1,
                    2048,
                ),
            billboardMeters =
                readFloatSystemProperty(
                    PROPERTY_BILLBOARD_METERS,
                    if (source == SpatialHandBillboardSource.OpenXrLiveCustomMesh) 0.008f else 0.022f,
                    0.003f,
                    0.08f,
                ),
            wireframeWidthMeters = readFloatSystemProperty(PROPERTY_WIREFRAME_WIDTH_METERS, 0.0035f, 0.00075f, 0.020f),
            normalOffsetMeters =
                readFloatSystemProperty(PROPERTY_NORMAL_OFFSET_METERS, 0.0f, -0.03f, 0.03f),
            spreadMeters = readFloatSystemProperty(PROPERTY_SPREAD_METERS, 0.085f, 0.0f, 0.35f),
            driftMeters = readFloatSystemProperty(PROPERTY_DRIFT_METERS, 0.008f, 0.0f, 0.08f),
            driftHz = readFloatSystemProperty(PROPERTY_DRIFT_HZ, 0.42f, 0.0f, 8.0f),
            mappingProfile =
                SpatialLiveHandJointBridge.normalizeViewerWorldMappingProfile(
                    readSystemProperty(PROPERTY_MAPPING_PROFILE).ifBlank {
                      BuildConfig.HAND_ALIGNMENT_MAPPING_PROFILE_DEFAULT
                    }
                ),
            depthTestMode =
                SpatialHandBillboardDepthTestMode.parse(
                    readSystemProperty(PROPERTY_DEPTH_TEST),
                    source,
                ),
            carrierMode = SpatialHandBillboardCarrierMode.parse(readSystemProperty(PROPERTY_CARRIER)),
            visualMode = SpatialHandBillboardVisualMode.parse(readSystemProperty(PROPERTY_VISUAL_MODE)),
            wireframeSource =
                SpatialHandBillboardWireframeSource.parse(readSystemProperty(PROPERTY_WIREFRAME_SOURCE)),
        )
    }
  }
}

private enum class SpatialHandBillboardDepthTestMode(val id: String, val depthTest: DepthTest) {
  LessOrEqual("less-or-equal", DepthTest.LESS_OR_EQUAL),
  Always("always", DepthTest.ALWAYS);

  companion object {
    fun parse(value: String, source: SpatialHandBillboardSource): SpatialHandBillboardDepthTestMode =
        when (value.trim().lowercase(Locale.US)) {
          "less", "less-or-equal", "less_or_equal", "depth" -> LessOrEqual
          "always", "off", "disabled", "none" -> Always
          else ->
              if (source == SpatialHandBillboardSource.OpenXrLiveCustomMesh) Always else LessOrEqual
        }
  }
}

private enum class SpatialHandBillboardSource(val id: String) {
  SpatialSdkAnchorFlock("spatial-sdk-anchor-flock"),
  OpenXrLiveCustomMesh("openxr-live-custom-mesh");

  companion object {
    fun parse(value: String): SpatialHandBillboardSource =
        when (value.trim().lowercase(Locale.US)) {
          "openxr-live-custom-mesh", "openxr-custom-mesh", "live-custom-mesh", "mesh", "live" ->
              OpenXrLiveCustomMesh
          else -> SpatialSdkAnchorFlock
        }
  }
}

private enum class SpatialHandBillboardCarrierMode(val id: String, val meshMarker: String) {
  BatchedSceneMesh("batched-scene-mesh", "two-trianglemesh-billboard-clouds"),
  EcsEntities("ecs-entities", "mesh-box-thin-card");

  companion object {
    fun parse(value: String): SpatialHandBillboardCarrierMode =
        when (value.trim().lowercase(Locale.US)) {
          "ecs", "entity", "entities", "ecs-entities" -> EcsEntities
          "batched", "scene-mesh", "batched-scene-mesh", "trianglemesh", "triangle-mesh" ->
              BatchedSceneMesh
          else -> BatchedSceneMesh
        }
  }
}

private enum class SpatialHandBillboardVisualMode(val id: String, val wireframeEnabled: Boolean) {
  FilledBillboards("filled-billboards", false),
  WireframeEdges("wireframe-edges", true);

  companion object {
    fun parse(value: String): SpatialHandBillboardVisualMode =
        when (value.trim().lowercase(Locale.US)) {
          "wire", "wireframe", "wireframe-edges", "edges", "mesh-wireframe" -> WireframeEdges
          "filled", "fill", "filled-billboards", "billboards" -> FilledBillboards
          else -> FilledBillboards
        }
  }
}

private enum class SpatialHandBillboardWireframeSource(
    val id: String,
    val resolvedSource: String,
    val exactSourceAvailable: Boolean,
) {
  SpatialSdkJointProxy(
      "spatial-sdk-joint-proxy",
      "spatial-sdk-joint-proxy",
      true,
  ),
  OpenXrFbMesh(
      "openxr-fb-mesh",
      "spatial-sdk-joint-proxy",
      false,
  ),
  CustomMesh(
      "custom-mesh",
      "spatial-sdk-joint-proxy",
      false,
  ),
  AvatarSystemPublicMeshProbe(
      "avatar-system-public-mesh-probe",
      "spatial-sdk-joint-proxy",
      false,
  );

  companion object {
    fun parse(value: String): SpatialHandBillboardWireframeSource =
        when (value.trim().lowercase(Locale.US)) {
          "openxr-fb-mesh", "openxr-fb", "xr-fb-mesh", "xr-fb", "fb" -> OpenXrFbMesh
          "custom-mesh", "custom", "recorded-custom-mesh", "embedded-custom-mesh" -> CustomMesh
          "avatar-system-public-mesh-probe", "avatar-system", "spatial-avatar-hand-mesh",
          "spatial-sdk-avatar-hand-mesh", "avatar" -> AvatarSystemPublicMeshProbe
          "spatial-sdk-joint-proxy", "spatial-proxy", "spatial", "proxy", "auto", "" ->
              SpatialSdkJointProxy
          else -> SpatialSdkJointProxy
        }
  }
}

private fun publicBillboardColor(index: Int, count: Int, phase01: Float): Int {
  val t = if (count <= 1) 0.0f else index.toFloat() / (count - 1).toFloat()
  val r = (0.32f + 0.62f * (0.5f + 0.5f * sin(((t + phase01 * 0.12f) * TWO_PI_PUBLIC).toDouble()).toFloat()))
      .coerceIn(0.0f, 1.0f)
  val g = (0.42f + 0.52f * (0.5f + 0.5f * sin(((t * 1.7f + 0.33f) * TWO_PI_PUBLIC).toDouble()).toFloat()))
      .coerceIn(0.0f, 1.0f)
  val b = (0.55f + 0.40f * (0.5f + 0.5f * cos(((t * 1.3f + phase01 * 0.19f) * TWO_PI_PUBLIC).toDouble()).toFloat()))
      .coerceIn(0.0f, 1.0f)
  return AndroidColor.argb(196, (r * 255.0f).toInt(), (g * 255.0f).toInt(), (b * 255.0f).toInt())
}

private fun readBooleanSystemProperty(propertyName: String, defaultValue: Boolean): Boolean =
    when (readSystemProperty(propertyName).trim().lowercase(Locale.US)) {
      "1", "true", "yes", "on" -> true
      "0", "false", "no", "off" -> false
      else -> defaultValue
    }

private fun readIntSystemProperty(
    propertyName: String,
    defaultValue: Int,
    minValue: Int,
    maxValue: Int,
): Int = readSystemProperty(propertyName).toIntOrNull()?.coerceIn(minValue, maxValue) ?: defaultValue

private fun readFloatSystemProperty(
    propertyName: String,
    defaultValue: Float,
    minValue: Float,
    maxValue: Float,
): Float = readSystemProperty(propertyName).toFloatOrNull()?.coerceIn(minValue, maxValue) ?: defaultValue

private fun readSystemProperty(propertyName: String): String =
    runCatching {
          val systemProperties = Class.forName("android.os.SystemProperties")
          val get = systemProperties.getMethod("get", String::class.java, String::class.java)
          get.invoke(null, propertyName, "") as String
        }
        .getOrDefault("")

private fun markerToken(value: String): String =
    value.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_.:-]+"), "-").ifBlank { "none" }

private fun formatFloat(value: Float): String =
    String.format(Locale.US, "%.3f", value.toDouble()).trimEnd('0').trimEnd('.')

private const val PUBLIC_BILLBOARD_CARRIER_COUNT = 2
private const val PUBLIC_BILLBOARD_RENDER_ORDER = 30
private const val TWO_PI_PUBLIC = (PI * 2.0).toFloat()

private fun Vector3.normalizedOr(fallback: Vector3): Vector3 {
  val lengthSquared = x * x + y * y + z * z
  if (lengthSquared <= 1.0e-8f) {
    return fallback
  }
  val invLength = 1.0f / sqrt(lengthSquared)
  return Vector3(x * invLength, y * invLength, z * invLength)
}
