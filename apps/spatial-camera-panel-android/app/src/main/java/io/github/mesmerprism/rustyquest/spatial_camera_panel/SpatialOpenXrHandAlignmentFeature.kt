package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.net.Uri
import android.os.SystemClock
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Sphere
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

internal class SpatialOpenXrHandAlignmentFeature(
    private val marker: (String) -> Unit,
    private val probeProvider: () -> SpatialNativeInteropProbe,
) : SpatialFeature {
  override fun lateSystemsToRegister(): List<SystemBase> =
      listOf(SpatialOpenXrHandAlignmentSystem(marker, probeProvider))
}

@OptIn(SpatialSDKExperimentalAPI::class)
private class SpatialOpenXrHandAlignmentSystem(
    private val marker: (String) -> Unit,
    private val probeProvider: () -> SpatialNativeInteropProbe,
) : SystemBase() {
  private val jointEntities = mutableListOf<Entity>()
  private val anchorEntities = mutableListOf<Entity>()
  private val viewerEntities = mutableListOf<Entity>()
  private var frameIndex = 0
  private var bridgeStartMask = 0L
  private var bridgeStartAttemptFrame = 0
  private var capabilityLogged = false
  private var disabledLogged = false
  private var lastStatusMs = 0L

  override fun execute() {
    frameIndex += 1
    val config = SpatialOpenXrHandAlignmentConfig.read()
    if (!config.enabled) {
      hideVisuals()
      if (!disabledLogged) {
        disabledLogged = true
        marker(
            "channel=spatial-openxr-hand-alignment status=disabled " +
                "enabledProperty=${SpatialOpenXrHandAlignmentConfig.PROPERTY_ENABLED} " +
                "schema=$ALIGNMENT_SCHEMA"
        )
      }
      return
    }
    disabledLogged = false
    if (!capabilityLogged) {
      capabilityLogged = true
      marker(
          "channel=spatial-openxr-hand-alignment status=capability " +
              "schema=$ALIGNMENT_SCHEMA provider=SpatialSDK-AvatarBody-plus-OpenXR-bridge " +
              "renderedOpenXrJointObjects=52 openXrJointsPerHand=26 " +
              "spatialAnchorSource=AvatarBody.leftHand-rightHand.Transform " +
              "openXrAnchorComparison=palm_ext+wrist_ext " +
              "viewerComparison=Scene.getViewerPose-vs-xrLocateViews-mapped-by-bridge " +
              "acceptedMappingProfile=${SpatialLiveHandJointBridge.VIEWER_WORLD_MAPPING_PROFILE_ACCEPTED} " +
              "experimentalMappingProfile=${SpatialLiveHandJointBridge.VIEWER_WORLD_MAPPING_PROFILE_MIRROR_X} " +
              "rollbackDefaultMappingProfile=${SpatialLiveHandJointBridge.VIEWER_WORLD_MAPPING_PROFILE_ACCEPTED} " +
              "publicSpatialSdkHandMeshTopologyAvailable=false " +
              "comparisonRule=move-head-and-hands-delta-should-stay-constant"
      )
    }

    val scene = getScene() ?: return
    val viewerPose = runCatching { scene.getViewerPose() }.getOrElse { Pose(Vector3(0.0f)) }
    val basisMask =
        SpatialLiveHandJointBridge.updateSpatialViewerWorldBasis(viewerPose, config.mappingProfile)
    maybeStartBridge()
    val rows = SpatialLiveHandJointBridge.pollRows()
    val viewDiagnostic = SpatialLiveHandJointBridge.pollViewDiagnostics()
    val avatarSnapshot = findAvatarBodySnapshot()

    if (config.render) {
      ensureVisuals(config)
      updateJointVisuals(rows, viewerPose, config)
      updateAnchorVisuals(avatarSnapshot, viewerPose, config)
      updateViewerVisuals(viewerPose, viewDiagnostic, config)
    } else {
      hideVisuals()
    }

    val nowMs = SystemClock.elapsedRealtime()
    if (frameIndex % config.samplePeriodFrames == 0 || nowMs - lastStatusMs >= STATUS_INTERVAL_MS) {
      lastStatusMs = nowMs
      logSample(
          config = config,
          basisMask = basisMask,
          rows = rows,
          viewDiagnostic = viewDiagnostic,
          avatarSnapshot = avatarSnapshot,
          viewerPose = viewerPose,
      )
    }
  }

  private fun maybeStartBridge() {
    if (bridgeStartMask != 0L && frameIndex - bridgeStartAttemptFrame < 120) {
      return
    }
    bridgeStartAttemptFrame = frameIndex
    val probe = runCatching { probeProvider() }.getOrNull() ?: return
    if (!probe.openXrInstanceHandleNonZero ||
        !probe.openXrSessionHandleNonZero ||
        !probe.openXrGetInstanceProcAddrHandleNonZero) {
      return
    }
    bridgeStartMask = SpatialLiveHandJointBridge.ensureStarted(probe)
  }

  private fun ensureVisuals(config: SpatialOpenXrHandAlignmentConfig) {
    if (jointEntities.size != OPENXR_HAND_COUNT * OPENXR_HAND_JOINT_COUNT) {
      jointEntities.forEach { entity -> runCatching { entity.destroy() } }
      jointEntities.clear()
      repeat(OPENXR_HAND_COUNT * OPENXR_HAND_JOINT_COUNT) { index ->
        val handIndex = index / OPENXR_HAND_JOINT_COUNT
        val jointIndex = index % OPENXR_HAND_JOINT_COUNT
        jointEntities.add(
            Entity.create(
                Sphere(),
                Mesh(Uri.parse("mesh://sphere"), MeshCollision.NoCollision),
                Material().apply {
                  baseColor = openXrJointColor(handIndex, jointIndex)
                  unlit = true
                },
                Transform(Pose(Vector3(0.0f))),
                Scale(Vector3(config.jointMarkerMeters)),
                Visible(false),
            )
        )
      }
    }
    if (anchorEntities.size != OPENXR_HAND_COUNT) {
      anchorEntities.forEach { entity -> runCatching { entity.destroy() } }
      anchorEntities.clear()
      repeat(OPENXR_HAND_COUNT) { index ->
        anchorEntities.add(
            Entity.create(
                Sphere(),
                Mesh(Uri.parse("mesh://sphere"), MeshCollision.NoCollision),
                Material().apply {
                  baseColor =
                      if (index == 0) {
                        Color4(1.0f, 1.0f, 1.0f, 0.95f)
                      } else {
                        Color4(0.02f, 0.02f, 0.02f, 0.95f)
                      }
                  unlit = true
                },
                Transform(Pose(Vector3(0.0f))),
                Scale(Vector3(config.anchorMarkerMeters)),
                Visible(false),
            )
        )
      }
    }
    if (viewerEntities.size != 2) {
      viewerEntities.forEach { entity -> runCatching { entity.destroy() } }
      viewerEntities.clear()
      val colors =
          listOf(Color4(1.0f, 0.85f, 0.18f, 0.90f), Color4(0.22f, 1.0f, 0.42f, 0.90f))
      repeat(2) { index ->
        viewerEntities.add(
            Entity.create(
                Sphere(),
                Mesh(Uri.parse("mesh://sphere"), MeshCollision.NoCollision),
                Material().apply {
                  baseColor = colors[index]
                  unlit = true
                },
                Transform(Pose(Vector3(0.0f))),
                Scale(Vector3(config.viewerMarkerMeters)),
                Visible(false),
            )
        )
      }
    }
  }

  private fun updateJointVisuals(
      rows: FloatArray?,
      viewerPose: Pose,
      config: SpatialOpenXrHandAlignmentConfig,
  ) {
    if (rows == null) {
      jointEntities.forEach { entity -> entity.setComponent(Visible(false)) }
      return
    }
    for (handIndex in 0 until OPENXR_HAND_COUNT) {
      for (jointIndex in 0 until OPENXR_HAND_JOINT_COUNT) {
        val entity = jointEntities[handIndex * OPENXR_HAND_JOINT_COUNT + jointIndex]
        val pose = openXrJointPose(rows, handIndex, jointIndex)
        if (pose == null) {
          entity.setComponent(Visible(false))
        } else {
          entity.setComponent(Transform(Pose(pose.t, viewerPose.q)))
          entity.setComponent(Scale(Vector3(config.jointMarkerMeters)))
          entity.setComponent(Visible(true))
        }
      }
    }
  }

  private fun updateAnchorVisuals(
      snapshot: AlignmentAvatarBodySnapshot,
      viewerPose: Pose,
      config: SpatialOpenXrHandAlignmentConfig,
  ) {
    val anchors = listOf(snapshot.left, snapshot.right)
    for (index in anchors.indices) {
      val pose = anchors[index]
      val entity = anchorEntities.getOrNull(index) ?: continue
      if (pose == null) {
        entity.setComponent(Visible(false))
      } else {
        entity.setComponent(Transform(Pose(pose.t, viewerPose.q)))
        entity.setComponent(Scale(Vector3(config.anchorMarkerMeters)))
        entity.setComponent(Visible(true))
      }
    }
  }

  private fun updateViewerVisuals(
      viewerPose: Pose,
      viewDiagnostic: FloatArray?,
      config: SpatialOpenXrHandAlignmentConfig,
  ) {
    viewerEntities.getOrNull(0)?.let { entity ->
      entity.setComponent(Transform(Pose(viewerPose.t, viewerPose.q)))
      entity.setComponent(Scale(Vector3(config.viewerMarkerMeters)))
      entity.setComponent(Visible(true))
    }
    val mapped = viewDiagnostic?.mappedViewerPose()
    viewerEntities.getOrNull(1)?.let { entity ->
      if (mapped == null) {
        entity.setComponent(Visible(false))
      } else {
        entity.setComponent(Transform(mapped))
        entity.setComponent(Scale(Vector3(config.viewerMarkerMeters)))
        entity.setComponent(Visible(true))
      }
    }
  }

  private fun hideVisuals() {
    jointEntities.forEach { entity -> entity.setComponent(Visible(false)) }
    anchorEntities.forEach { entity -> entity.setComponent(Visible(false)) }
    viewerEntities.forEach { entity -> entity.setComponent(Visible(false)) }
  }

  private fun findAvatarBodySnapshot(): AlignmentAvatarBodySnapshot {
    var left: Pose? = null
    var right: Pose? = null
    var head: Pose? = null
    var localBodyCount = 0
    runCatching {
          Query.where { has(AvatarBody.id) }
              .eval()
              .forEach { entity ->
                val avatarBody = entity.tryGetComponent<AvatarBody>() ?: return@forEach
                val localPlayer =
                    runCatching { entity.isLocal() }.getOrDefault(true) &&
                        avatarBody.isPlayerControlled
                if (!localPlayer) {
                  return@forEach
                }
                localBodyCount += 1
                if (left == null) {
                  left = avatarBody.leftHand.tryGetComponent<Transform>()?.transform
                }
                if (right == null) {
                  right = avatarBody.rightHand.tryGetComponent<Transform>()?.transform
                }
                if (head == null) {
                  head =
                      runCatching { avatarBody.head.tryGetComponent<Transform>()?.transform }
                          .getOrNull()
                }
              }
        }
        .onFailure { throwable ->
          marker(
              "channel=spatial-openxr-hand-alignment status=avatarbody-query-failed " +
                  "schema=$ALIGNMENT_SCHEMA error=${alignmentMarkerToken(throwable.javaClass.simpleName)}"
          )
        }
    return AlignmentAvatarBodySnapshot(localBodyCount, left, right, head)
  }

  private fun logSample(
      config: SpatialOpenXrHandAlignmentConfig,
      basisMask: Long,
      rows: FloatArray?,
      viewDiagnostic: FloatArray?,
      avatarSnapshot: AlignmentAvatarBodySnapshot,
      viewerPose: Pose,
  ) {
    val leftPalm = openXrJointPose(rows, 0, OPENXR_PALM)
    val leftWrist = openXrJointPose(rows, 0, OPENXR_WRIST)
    val rightPalm = openXrJointPose(rows, 1, OPENXR_PALM)
    val rightWrist = openXrJointPose(rows, 1, OPENXR_WRIST)
    val mappedViewerPose = viewDiagnostic?.mappedViewerPose()
    marker(
        "channel=spatial-openxr-hand-alignment status=sample " +
            "schema=$ALIGNMENT_SCHEMA frameIndex=$frameIndex render=${config.render} " +
            "requestedMappingProfile=${config.mappingProfile} " +
            "basisMask=$basisMask bridgeStartMask=$bridgeStartMask " +
            "${SpatialLiveHandJointBridge.loadedMarker()} " +
            "rowsAvailable=${rows != null} rowsFloatCount=${rows?.size ?: 0} " +
            "validJointCount=${rows?.validJointCount() ?: 0} " +
            "leftValidJointCount=${rows?.validJointCount(0) ?: 0} " +
            "rightValidJointCount=${rows?.validJointCount(1) ?: 0} " +
            "spatialAvatarBodyCount=${avatarSnapshot.localBodyCount} " +
            "spatialLeftAnchor=${poseMarker(avatarSnapshot.left)} " +
            "spatialRightAnchor=${poseMarker(avatarSnapshot.right)} " +
            "spatialAvatarHead=${poseMarker(avatarSnapshot.head)} " +
            "openXrLeftPalm=${poseMarker(leftPalm)} openXrLeftWrist=${poseMarker(leftWrist)} " +
            "openXrRightPalm=${poseMarker(rightPalm)} openXrRightWrist=${poseMarker(rightWrist)} " +
            "leftAnchorToPalmM=${distanceMarker(avatarSnapshot.left, leftPalm)} " +
            "leftAnchorToWristM=${distanceMarker(avatarSnapshot.left, leftWrist)} " +
            "rightAnchorToPalmM=${distanceMarker(avatarSnapshot.right, rightPalm)} " +
            "rightAnchorToWristM=${distanceMarker(avatarSnapshot.right, rightWrist)} " +
            "leftAnchorToPalmRotDeg=${rotationMarker(avatarSnapshot.left, leftPalm)} " +
            "leftAnchorToWristRotDeg=${rotationMarker(avatarSnapshot.left, leftWrist)} " +
            "rightAnchorToPalmRotDeg=${rotationMarker(avatarSnapshot.right, rightPalm)} " +
            "rightAnchorToWristRotDeg=${rotationMarker(avatarSnapshot.right, rightWrist)} " +
            "spatialViewerPose=${poseMarker(viewerPose)} " +
            "openXrRawViewerPose=${poseMarker(viewDiagnostic?.rawViewerPose())} " +
            "openXrMappedViewerPose=${poseMarker(mappedViewerPose)} " +
            "viewerDeltaM=${distanceMarker(viewerPose, mappedViewerPose)} " +
            "viewerRotationDeltaDeg=${rotationMarker(viewerPose, mappedViewerPose)} " +
            "viewDiagnosticAvailable=${viewDiagnostic != null} " +
            "viewMappingMode=${viewDiagnostic?.mappingModeToken() ?: "unavailable"} " +
            "viewCount=${viewDiagnostic?.getOrNull(16)?.toInt() ?: 0} " +
            "viewRegistrationReady=${(viewDiagnostic?.getOrNull(17) ?: 0.0f) > 0.5f} " +
            "openXrAnchorComparison=palm_ext+wrist_ext " +
            "comparisonRule=move-head-and-hands-delta-should-stay-constant"
    )
  }
}

private data class AlignmentAvatarBodySnapshot(
    val localBodyCount: Int,
    val left: Pose?,
    val right: Pose?,
    val head: Pose?,
)

private data class SpatialOpenXrHandAlignmentConfig(
    val enabled: Boolean,
    val render: Boolean,
    val mappingProfile: String,
    val samplePeriodFrames: Int,
    val jointMarkerMeters: Float,
    val anchorMarkerMeters: Float,
    val viewerMarkerMeters: Float,
) {
  companion object {
    const val PROPERTY_ENABLED = "debug.rustyquest.spatial.hand_alignment.enabled"
    private const val PROPERTY_RENDER = "debug.rustyquest.spatial.hand_alignment.render"
    private const val PROPERTY_MAPPING_PROFILE =
        "debug.rustyquest.spatial.hand_alignment.mapping_profile"
    private const val PROPERTY_SAMPLE_PERIOD_FRAMES =
        "debug.rustyquest.spatial.hand_alignment.sample_period_frames"
    private const val PROPERTY_JOINT_MARKER_M =
        "debug.rustyquest.spatial.hand_alignment.joint_marker_m"
    private const val PROPERTY_PALM_WRIST_MARKER_M =
        "debug.rustyquest.spatial.hand_alignment.palm_wrist_marker_m"
    private const val PROPERTY_ANCHOR_MARKER_M =
        "debug.rustyquest.spatial.hand_alignment.anchor_marker_m"
    private const val PROPERTY_VIEWER_MARKER_M =
        "debug.rustyquest.spatial.hand_alignment.viewer_marker_m"

    fun read(): SpatialOpenXrHandAlignmentConfig =
        SpatialOpenXrHandAlignmentConfig(
            enabled = readBooleanSystemProperty(PROPERTY_ENABLED) ?: false,
            render = readBooleanSystemProperty(PROPERTY_RENDER) ?: true,
            mappingProfile =
                SpatialLiveHandJointBridge.normalizeViewerWorldMappingProfile(
                    readStringSystemProperty(PROPERTY_MAPPING_PROFILE)
                ),
            samplePeriodFrames =
                readIntSystemProperty(PROPERTY_SAMPLE_PERIOD_FRAMES, 15).coerceIn(1, 600),
            jointMarkerMeters =
                readFloatSystemProperty(PROPERTY_JOINT_MARKER_M, 0.014f)
                    .coerceIn(0.004f, 0.060f),
            anchorMarkerMeters =
                readFloatSystemProperty(
                        PROPERTY_ANCHOR_MARKER_M,
                        readFloatSystemProperty(PROPERTY_PALM_WRIST_MARKER_M, 0.038f),
                    )
                    .coerceIn(0.010f, 0.120f),
            viewerMarkerMeters =
                readFloatSystemProperty(PROPERTY_VIEWER_MARKER_M, 0.050f)
                    .coerceIn(0.010f, 0.200f),
        )
  }
}

private fun openXrJointPose(rows: FloatArray?, handIndex: Int, jointIndex: Int): Pose? {
  if (rows == null ||
      handIndex !in 0 until OPENXR_HAND_COUNT ||
      jointIndex !in 0 until OPENXR_HAND_JOINT_COUNT) {
    return null
  }
  val offset =
      (handIndex * OPENXR_HAND_JOINT_COUNT + jointIndex) *
          SpatialLiveHandJointBridge.FLOATS_PER_ROW
  if (offset + 11 >= rows.size || rows[offset + 4] <= 0.5f) {
    return null
  }
  return Pose(
      Vector3(rows[offset], rows[offset + 1], rows[offset + 2]),
      spatialQuaternionFromOpenXrXyzw(
          rows[offset + 8],
          rows[offset + 9],
          rows[offset + 10],
          rows[offset + 11],
      ),
  )
}

private fun FloatArray.rawViewerPose(): Pose? {
  if (size != SpatialLiveHandJointBridge.VIEW_DIAGNOSTIC_FLOAT_COUNT || this[0] <= 0.5f) {
    return null
  }
  return Pose(
      Vector3(this[2], this[3], this[4]),
      spatialQuaternionFromOpenXrXyzw(this[5], this[6], this[7], this[8]),
  )
}

private fun FloatArray.mappedViewerPose(): Pose? {
  if (size != SpatialLiveHandJointBridge.VIEW_DIAGNOSTIC_FLOAT_COUNT || this[0] <= 0.5f) {
    return null
  }
  return Pose(
      Vector3(this[9], this[10], this[11]),
      spatialQuaternionFromOpenXrXyzw(this[12], this[13], this[14], this[15]),
  )
}

private fun spatialQuaternionFromOpenXrXyzw(
    x: Float,
    y: Float,
    z: Float,
    w: Float,
): Quaternion = Quaternion(w, x, y, z)

private fun FloatArray.mappingModeToken(): String =
    when (getOrNull(1)?.toInt()) {
      2 -> "openxr-local-floor-to-spatial-sdk-viewer-world-registration"
      3 -> "openxr-local-floor-to-spatial-sdk-mirror-x-origin-registration"
      1 -> "viewer-relative-openxr-to-spatial-sdk-panel-basis"
      0 -> "raw-openxr-local-floor-to-spatial-sdk-scene-fallback"
      else -> "unavailable"
    }

private fun FloatArray.validJointCount(): Int =
    (0 until SpatialLiveHandJointBridge.ROW_COUNT).count { row ->
      this[row * SpatialLiveHandJointBridge.FLOATS_PER_ROW + 4] > 0.5f
    }

private fun FloatArray.validJointCount(handIndex: Int): Int {
  val start = handIndex * OPENXR_HAND_JOINT_COUNT
  return (start until start + OPENXR_HAND_JOINT_COUNT).count { row ->
    this[row * SpatialLiveHandJointBridge.FLOATS_PER_ROW + 4] > 0.5f
  }
}

private fun poseMarker(pose: Pose?): String =
    if (pose == null) {
      "none"
    } else {
      "p=${vectorMarker(pose.t)};q=${quaternionMarker(pose.q)}"
    }

private fun vectorMarker(value: Vector3): String =
    String.format(Locale.US, "%.4f,%.4f,%.4f", value.x, value.y, value.z)

private fun quaternionMarker(value: Quaternion): String =
    String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", value.x, value.y, value.z, value.w)

private fun distanceMarker(a: Pose?, b: Pose?): String =
    if (a == null || b == null) {
      "none"
    } else {
      alignmentFormatFloat((a.t - b.t).length())
    }

private fun rotationMarker(a: Pose?, b: Pose?): String =
    if (a == null || b == null) {
      "none"
    } else {
      alignmentFormatFloat(quaternionDeltaDegrees(a.q, b.q))
    }

private fun quaternionDeltaDegrees(a: Quaternion, b: Quaternion): Float {
  val dot = normalizeQuatDot(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w)
  return Math.toDegrees((2.0 * acos(dot.toDouble())).coerceAtMost(Math.PI)).toFloat()
}

private fun normalizeQuatDot(value: Float): Float = max(-1.0f, min(1.0f, abs(value)))

private fun alignmentFormatFloat(value: Float): String = String.format(Locale.US, "%.4f", value)

private fun alignmentMarkerToken(value: String): String =
    value.replace(Regex("[^A-Za-z0-9_.:+\\-/]"), "_").ifBlank { "none" }

private fun openXrJointColor(handIndex: Int, jointIndex: Int): Color4 {
  val base = jointIndex.toFloat() / (OPENXR_HAND_JOINT_COUNT - 1).toFloat()
  return if (handIndex == 0) {
    Color4(0.16f + base * 0.32f, 0.72f, 1.0f - base * 0.22f, 0.90f)
  } else {
    Color4(1.0f, 0.35f + base * 0.44f, 0.22f, 0.90f)
  }
}

private val androidSystemPropertyGetMethodForAlignment by lazy(LazyThreadSafetyMode.PUBLICATION) {
  runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
      }
      .getOrNull()
}

private fun readBooleanSystemProperty(name: String): Boolean? {
  val raw = readStringSystemProperty(name)?.trim()?.lowercase(Locale.US)
  return when (raw) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> null
  }
}

private fun readIntSystemProperty(name: String, fallback: Int): Int =
    readStringSystemProperty(name)?.toIntOrNull() ?: fallback

private fun readFloatSystemProperty(name: String, fallback: Float): Float {
  val value = readStringSystemProperty(name)?.toFloatOrNull() ?: return fallback
  return if (value.isFinite()) value else fallback
}

private fun readStringSystemProperty(name: String): String? =
    runCatching { androidSystemPropertyGetMethodForAlignment?.invoke(null, name, "") as? String }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

private const val ALIGNMENT_SCHEMA = "rusty.quest.spatial.openxr_hand_alignment.v1"
private const val OPENXR_HAND_COUNT = 2
private const val OPENXR_HAND_JOINT_COUNT = 26
private const val OPENXR_PALM = 0
private const val OPENXR_WRIST = 1
private const val STATUS_INTERVAL_MS = 1000L

@Suppress("unused")
private val OPENXR_JOINT_NAMES =
    arrayOf(
        "PALM_EXT",
        "WRIST_EXT",
        "THUMB_METACARPAL_EXT",
        "THUMB_PROXIMAL_EXT",
        "THUMB_DISTAL_EXT",
        "THUMB_TIP_EXT",
        "INDEX_METACARPAL_EXT",
        "INDEX_PROXIMAL_EXT",
        "INDEX_INTERMEDIATE_EXT",
        "INDEX_DISTAL_EXT",
        "INDEX_TIP_EXT",
        "MIDDLE_METACARPAL_EXT",
        "MIDDLE_PROXIMAL_EXT",
        "MIDDLE_INTERMEDIATE_EXT",
        "MIDDLE_DISTAL_EXT",
        "MIDDLE_TIP_EXT",
        "RING_METACARPAL_EXT",
        "RING_PROXIMAL_EXT",
        "RING_INTERMEDIATE_EXT",
        "RING_DISTAL_EXT",
        "RING_TIP_EXT",
        "LITTLE_METACARPAL_EXT",
        "LITTLE_PROXIMAL_EXT",
        "LITTLE_INTERMEDIATE_EXT",
        "LITTLE_DISTAL_EXT",
        "LITTLE_TIP_EXT",
    )
