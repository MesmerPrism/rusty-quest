package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Context
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Transform
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

internal class SpatialHandCaptureRecorderFeature(
    context: Context,
    private val marker: (String) -> Unit,
    private val probeProvider: () -> SpatialNativeInteropProbe,
) : SpatialFeature {
  private val appContext = context.applicationContext

  override fun lateSystemsToRegister(): List<SystemBase> =
      listOf(SpatialHandCaptureRecorderSystem(appContext, marker, probeProvider))
}

private class SpatialHandCaptureRecorderSystem(
    private val context: Context,
    private val marker: (String) -> Unit,
    private val probeProvider: () -> SpatialNativeInteropProbe,
) : SystemBase() {
  private var initialized = false
  private var controlFile: File? = null
  private var captureRoot: File? = null
  private var current: SpatialHandCaptureSession? = null
  private var lastControlError: String? = null
  private var frameIndex = 0
  private var bridgeStartMask = 0L
  private var bridgeStartAttemptFrame = 0
  private var adapterRejectedLogged = false

  override fun execute() {
    val adapterDecision = SpatialLiveHandJointBridge.currentHandAdapterActivationDecision()
    if (!adapterDecision.applied) {
      current?.finish("adapter-lock-rejected")
      current = null
      if (!adapterRejectedLogged) {
        adapterRejectedLogged = true
        marker(SpatialLiveHandJointBridge.handAdapterActivationMarker(adapterDecision))
      }
      return
    }
    adapterRejectedLogged = false
    ensureInitialized()
    val controlFile = controlFile ?: return
    val captureRoot = captureRoot ?: return
    val control =
        when (val result = readControl(controlFile)) {
          is ControlReadResult.Missing -> {
            current?.finish("control-file-missing")
            current = null
            return
          }
          is ControlReadResult.Malformed -> {
            if (lastControlError != result.message) {
              lastControlError = result.message
              marker(
                  "channel=spatial-hand-capture status=control-error " +
                      "reason=malformed-control-file error=${markerToken(result.message)}"
              )
            }
            return
          }
          is ControlReadResult.Ok -> result.control
        }
    lastControlError = null
    if (!control.enabled) {
      current?.finish("control-disabled")
      current = null
      return
    }

    if (current?.sessionId != control.sessionId) {
      current?.finish("session-replaced")
      current =
          runCatching { SpatialHandCaptureSession.start(captureRoot, control, marker) }
              .getOrElse { throwable ->
                marker(
                    "channel=spatial-hand-capture status=start-error " +
                        "reason=${markerToken(throwable.javaClass.simpleName)} " +
                        "message=${markerToken(throwable.message ?: "none")}"
                )
                null
              }
    }

    val session = current ?: return
    frameIndex++
    if (frameIndex % control.samplePeriodFrames != 0) {
      session.skippedFrames++
      return
    }
    maybeStartBridge()
    val rows = SpatialLiveHandJointBridge.pollRows()
    val snapshot = findAvatarBodySnapshot()
    session.recordFrame(
        frameIndex = frameIndex,
        bridgeStartMask = bridgeStartMask,
        bridgeRows = rows,
        avatarSnapshot = snapshot,
    )
    if (session.frameLimitReached()) {
      session.finish("max-frames-reached")
      current = null
    }
  }

  private fun ensureInitialized() {
    if (initialized) {
      return
    }
    initialized = true
    val root = context.getExternalFilesDir(null)
    if (root == null) {
      marker(
          "channel=spatial-hand-capture status=unavailable " +
              "reason=missing-external-app-data-path controlSchema=$CONTROL_SCHEMA"
      )
      return
    }
    val captureRoot = File(root, CAPTURE_ROOT_NAME)
    captureRoot.mkdirs()
    this.controlFile = File(root, CONTROL_FILE_NAME)
    this.captureRoot = captureRoot
    marker(
        "channel=spatial-hand-capture status=ready controlSchema=$CONTROL_SCHEMA " +
            "captureSchema=$MANIFEST_SCHEMA clipRowSchema=$CLIP_ROW_SCHEMA " +
            "poseRowSchema=$POSE_ROW_SCHEMA controlFile=${markerToken(this.controlFile!!.path)} " +
            "captureRoot=${markerToken(captureRoot.path)} " +
            "sourceKind=spatial-sdk-avatarbody-transform-plus-openxr-joint-bridge " +
            "spatialPublicMeshTopologyAvailable=false"
    )
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

  private fun findAvatarBodySnapshot(): AvatarBodySnapshot {
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
                localBodyCount++
                if (left == null) {
                  left = avatarBody.leftHand.tryGetComponent<Transform>()?.transform
                }
                if (right == null) {
                  right = avatarBody.rightHand.tryGetComponent<Transform>()?.transform
                }
                if (head == null) {
                  head = runCatching { avatarBody.head.tryGetComponent<Transform>()?.transform }
                      .getOrNull()
                }
              }
        }
        .onFailure { throwable ->
          marker(
              "channel=spatial-hand-capture status=avatarbody-query-failed " +
                  "source=spatial-sdk-avatar-body-hand-entities " +
                  "error=${markerToken(throwable.javaClass.simpleName)}"
          )
        }
    return AvatarBodySnapshot(localBodyCount, left, right, head)
  }
}

private data class SpatialHandCaptureControl(
    val enabled: Boolean,
    val sessionId: String,
    val maxFrames: Int,
    val samplePeriodFrames: Int,
)

private sealed class ControlReadResult {
  object Missing : ControlReadResult()
  data class Malformed(val message: String) : ControlReadResult()
  data class Ok(val control: SpatialHandCaptureControl) : ControlReadResult()
}

private fun readControl(path: File): ControlReadResult {
  if (!path.exists()) {
    return ControlReadResult.Missing
  }
  val text =
      runCatching { path.readText(Charsets.UTF_8) }
          .getOrElse { return ControlReadResult.Malformed("read-control-failed-${it.javaClass.simpleName}") }
  val json =
      runCatching { JSONObject(text) }
          .getOrElse { return ControlReadResult.Malformed("parse-control-json-${it.javaClass.simpleName}") }
  val schema = json.optString("schema", "")
  if (schema != CONTROL_SCHEMA) {
    return ControlReadResult.Malformed("unsupported-control-schema-$schema")
  }
  return ControlReadResult.Ok(
      SpatialHandCaptureControl(
          enabled = json.optBoolean("enabled", false),
          sessionId = sanitizeSessionId(json.optString("session_id", "spatial-hand-capture")),
          maxFrames = json.optInt("max_frames", DEFAULT_MAX_FRAMES).coerceIn(1, 36000),
          samplePeriodFrames =
              json.optInt("sample_period_frames", DEFAULT_SAMPLE_PERIOD_FRAMES).coerceIn(1, 600),
      )
  )
}

private data class AvatarBodySnapshot(
    val localBodyCount: Int,
    val left: Pose?,
    val right: Pose?,
    val head: Pose?,
)

private class SpatialHandCaptureSession private constructor(
    val sessionId: String,
    private val dir: File,
    private val maxFrames: Int,
    private val samplePeriodFrames: Int,
    private val marker: (String) -> Unit,
    private val leftClip: BufferedWriter,
    private val rightClip: BufferedWriter,
    private val spatialPoses: BufferedWriter,
    private val status: BufferedWriter,
) {
  private val startedUnixMs = System.currentTimeMillis()
  var skippedFrames: Int = 0
  private var leftClipFrames = 0
  private var rightClipFrames = 0
  private var poseFrames = 0
  private var lastFrameIndex = 0
  private var finished = false

  fun recordFrame(
      frameIndex: Int,
      bridgeStartMask: Long,
      bridgeRows: FloatArray?,
      avatarSnapshot: AvatarBodySnapshot,
  ) {
    if (finished) {
      return
    }
    lastFrameIndex = frameIndex
    val timestampNs = System.nanoTime()
    var wroteAny = false
    if (bridgeRows != null) {
      compactClipRow("left", 0, frameIndex, timestampNs, bridgeRows)?.let {
        leftClip.writeJsonLine(it)
        leftClipFrames++
        wroteAny = true
      }
      compactClipRow("right", 1, frameIndex, timestampNs, bridgeRows)?.let {
        rightClip.writeJsonLine(it)
        rightClipFrames++
        wroteAny = true
      }
    }
    if (avatarSnapshot.left != null || avatarSnapshot.right != null || avatarSnapshot.head != null) {
      spatialPoses.writeJsonLine(spatialPoseRow(frameIndex, timestampNs, bridgeStartMask, avatarSnapshot))
      poseFrames++
      wroteAny = true
    }
    if (!wroteAny) {
      skippedFrames++
    }
    if (leftClipFrames + rightClipFrames + poseFrames == 1 || frameIndex % 60 == 0) {
      writeManifest("active")
      writeStatus("recording", "frame-sampled")
      marker(
          "channel=spatial-hand-capture status=recording captureId=${markerToken(sessionId)} " +
              "leftClipFrames=$leftClipFrames rightClipFrames=$rightClipFrames " +
              "spatialPoseFrames=$poseFrames skippedFrames=$skippedFrames " +
              "bridgeRowsAvailable=${bridgeRows != null} bridgeStartMask=$bridgeStartMask " +
              "avatarBodyLocalCount=${avatarSnapshot.localBodyCount} captureDir=${markerToken(dir.path)}"
      )
    }
  }

  fun frameLimitReached(): Boolean = poseFrames >= maxFrames

  fun finish(reason: String) {
    if (finished) {
      return
    }
    leftClip.flush()
    rightClip.flush()
    spatialPoses.flush()
    writeManifest(reason)
    writeStatus("stopped", reason)
    status.flush()
    finished = true
    marker(
        "channel=spatial-hand-capture status=stopped reason=${markerToken(reason)} " +
            "captureId=${markerToken(sessionId)} leftClipFrames=$leftClipFrames " +
            "rightClipFrames=$rightClipFrames spatialPoseFrames=$poseFrames " +
            "skippedFrames=$skippedFrames captureDir=${markerToken(dir.path)}"
    )
  }

  private fun writeManifest(finishedReason: String) {
    val manifest =
        JSONObject()
            .put("schema", MANIFEST_SCHEMA)
            .put("capture_id", sessionId)
            .put("provider", "rusty-quest-spatial-sdk-hand-capture")
            .put("source_kind", "spatial-sdk-avatarbody-transform-plus-openxr-joint-bridge")
            .put("recorded_input_equivalent", true)
            .put("runtime_provider", "SpatialSDK AvatarBody Transform plus OpenXR joint bridge")
            .put("reference_space", "spatial-sdk-scene-world-and-bridge-mapped-openxr")
            .put("coordinate_system", "spatial-sdk-scene-world-meters")
            .put("spatial_public_mesh_topology_available", false)
            .put("mesh_provider", "none-public-spatial-sdk-avatar-system-sdk-owned")
            .put("built_in_visual_provider", "AvatarSystem")
            .put("built_in_visual_material_policy", "sdk-owned-no-public-material-surface")
            .put("clip_row_schema", CLIP_ROW_SCHEMA)
            .put("pose_row_schema", POSE_ROW_SCHEMA)
            .put("control_schema", CONTROL_SCHEMA)
            .put(
                "artifact_files",
                JSONObject()
                    .put("left_clip", "left.clip.jsonl")
                    .put("right_clip", "right.clip.jsonl")
                    .put("spatial_poses", "spatial_poses.jsonl")
                    .put("status", "status.jsonl"),
            )
            .put("runtime_joint_count", RUNTIME_OPENXR_JOINTS.size)
            .put("tip_length_count", TIP_OPENXR_PAIRS.size)
            .put("left_clip_frame_count", leftClipFrames)
            .put("right_clip_frame_count", rightClipFrames)
            .put("spatial_pose_frame_count", poseFrames)
            .put("skipped_frame_count", skippedFrames)
            .put("max_frames", maxFrames)
            .put("sample_period_frames", samplePeriodFrames)
            .put("started_unix_ms", startedUnixMs)
            .put("finished_unix_ms", if (finishedReason == "active") JSONObject.NULL else System.currentTimeMillis())
            .put("finished_reason", finishedReason)
            .put("last_frame_index", lastFrameIndex)
    File(dir, "capture.manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
  }

  private fun writeStatus(statusValue: String, reason: String) {
    status.writeJsonLine(
        JSONObject()
            .put("schema", STATUS_ROW_SCHEMA)
            .put("capture_id", sessionId)
            .put("status", statusValue)
            .put("reason", reason)
            .put("unix_ms", System.currentTimeMillis())
            .put("left_clip_frames", leftClipFrames)
            .put("right_clip_frames", rightClipFrames)
            .put("spatial_pose_frames", poseFrames)
            .put("skipped_frames", skippedFrames)
            .put("latest_frame_index", lastFrameIndex)
    )
  }

  companion object {
    fun start(
        captureRoot: File,
        control: SpatialHandCaptureControl,
        marker: (String) -> Unit,
    ): SpatialHandCaptureSession {
      val dir = File(captureRoot, control.sessionId)
      dir.mkdirs()
      val session =
          SpatialHandCaptureSession(
              sessionId = control.sessionId,
              dir = dir,
              maxFrames = control.maxFrames,
              samplePeriodFrames = control.samplePeriodFrames,
              marker = marker,
              leftClip = File(dir, "left.clip.jsonl").truncateWriter(),
              rightClip = File(dir, "right.clip.jsonl").truncateWriter(),
              spatialPoses = File(dir, "spatial_poses.jsonl").truncateWriter(),
              status = File(dir, "status.jsonl").truncateWriter(),
          )
      session.writeManifest("active")
      session.writeStatus("started", "session-started")
      marker(
          "channel=spatial-hand-capture status=started captureId=${markerToken(control.sessionId)} " +
              "captureDir=${markerToken(dir.path)} sourceKind=spatial-sdk-avatarbody-transform-plus-openxr-joint-bridge " +
              "spatialPublicMeshTopologyAvailable=false clipFiles=left.clip.jsonl,right.clip.jsonl " +
              "poseFile=spatial_poses.jsonl maxFrames=${control.maxFrames}"
      )
      return session
    }
  }
}

private fun compactClipRow(
    handedness: String,
    handIndex: Int,
    frameIndex: Int,
    timestampNs: Long,
    rows: FloatArray,
): JSONObject? {
  val rowBase = handIndex * OPENXR_HAND_JOINT_COUNT
  val joints = JSONArray()
  for ((runtimeIndex, openXrIndex) in RUNTIME_OPENXR_JOINTS.withIndex()) {
    val row = rowBase + openXrIndex
    if (!rowPoseValid(rows, row)) {
      return null
    }
    val offset = row * SpatialLiveHandJointBridge.FLOATS_PER_ROW
    joints.put(
        JSONObject()
            .put("joint_index", runtimeIndex)
            .put("openxr_joint_index", openXrIndex)
            .put("openxr_joint_name", OPENXR_JOINT_NAMES[openXrIndex])
            .put(
                "pose",
                JSONObject()
                    .put("translation", floatArrayJson(rows[offset], rows[offset + 1], rows[offset + 2]))
                    .put(
                        "rotation",
                        floatArrayJson(
                            rows[offset + 8],
                            rows[offset + 9],
                            rows[offset + 10],
                            rows[offset + 11],
                        ),
                    ),
            )
            .put("radius_m", rows[offset + 3])
            .put("position_tracked", rows[offset + 7] >= 0.5f)
    )
  }
  val tipLengths = JSONArray()
  for ((distal, tip) in TIP_OPENXR_PAIRS) {
    val distalRow = rowBase + distal
    val tipRow = rowBase + tip
    if (!rowPoseValid(rows, distalRow) || !rowPoseValid(rows, tipRow)) {
      return null
    }
    tipLengths.put(distance(rowPosition(rows, distalRow), rowPosition(rows, tipRow)))
  }
  return JSONObject()
      .put("schema", CLIP_ROW_SCHEMA)
      .put("handedness", handedness)
      .put("frame_index", frameIndex)
      .put("timestamp_ns", timestampNs)
      .put("runtime_provider", "spatial-app-openxr-joint-bridge")
      .put("reference_space", "spatial-sdk-scene-from-openxr-bridge")
      .put("joints", joints)
      .put("tip_lengths_m", tipLengths)
}

private fun spatialPoseRow(
    frameIndex: Int,
    timestampNs: Long,
    bridgeStartMask: Long,
    snapshot: AvatarBodySnapshot,
): JSONObject =
    JSONObject()
        .put("schema", POSE_ROW_SCHEMA)
        .put("frame_index", frameIndex)
        .put("timestamp_ns", timestampNs)
        .put("source", "spatial-sdk-avatar-body")
        .put("bridge_start_mask", bridgeStartMask)
        .put("local_avatar_body_count", snapshot.localBodyCount)
        .put("left_hand", poseJsonOrNull(snapshot.left))
        .put("right_hand", poseJsonOrNull(snapshot.right))
        .put("head", poseJsonOrNull(snapshot.head))

private fun rowPoseValid(rows: FloatArray, row: Int): Boolean {
  val offset = row * SpatialLiveHandJointBridge.FLOATS_PER_ROW
  if (offset + 11 >= rows.size) {
    return false
  }
  return rows[offset + 4] >= 0.5f && rows[offset + 5] >= 0.5f
}

private fun rowPosition(rows: FloatArray, row: Int): Vector3 {
  val offset = row * SpatialLiveHandJointBridge.FLOATS_PER_ROW
  return Vector3(rows[offset], rows[offset + 1], rows[offset + 2])
}

private fun poseJsonOrNull(pose: Pose?): Any =
    if (pose == null) {
      JSONObject.NULL
    } else {
      JSONObject()
          .put("translation", floatArrayJson(pose.t.x, pose.t.y, pose.t.z))
          .put("rotation_xyzw", floatArrayJson(pose.q.x, pose.q.y, pose.q.z, pose.q.w))
    }

private fun distance(left: Vector3, right: Vector3): Float {
  val dx = left.x - right.x
  val dy = left.y - right.y
  val dz = left.z - right.z
  return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun floatArrayJson(vararg values: Float): JSONArray {
  val array = JSONArray()
  values.forEach { array.put(String.format(Locale.US, "%.7f", it).toFloat()) }
  return array
}

private fun File.truncateWriter(): BufferedWriter = BufferedWriter(FileWriter(this, false))

private fun BufferedWriter.writeJsonLine(row: JSONObject) {
  write(row.toString())
  newLine()
  flush()
}

private fun markerToken(value: String): String =
    value.replace('\u0000', '_').replace(Regex("[\\r\\n\\t ]+"), "-")

private fun sanitizeSessionId(value: String): String {
  val sanitized =
      value
          .mapNotNull { ch ->
            when {
              ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' -> ch
              ch.isWhitespace() -> '-'
              else -> null
            }
          }
          .joinToString("")
          .take(96)
  return sanitized.ifBlank { "spatial-hand-capture" }
}

private const val CONTROL_FILE_NAME = "spatial-hand-capture-control.json"
private const val CAPTURE_ROOT_NAME = "spatial-hand-captures"
private const val CONTROL_SCHEMA = "rusty.quest.spatial.hand_capture_control.v1"
private const val MANIFEST_SCHEMA = "rusty.quest.spatial.hand_capture_manifest.v1"
private const val CLIP_ROW_SCHEMA = "rusty.matter.hand_joint_frame.v1"
private const val POSE_ROW_SCHEMA = "rusty.quest.spatial.hand_pose_frame.v1"
private const val STATUS_ROW_SCHEMA = "rusty.quest.spatial.hand_capture_status.v1"
private const val DEFAULT_MAX_FRAMES = 900
private const val DEFAULT_SAMPLE_PERIOD_FRAMES = 1
private const val OPENXR_HAND_JOINT_COUNT = 26

private val RUNTIME_OPENXR_JOINTS =
    intArrayOf(0, 1, 2, 3, 4, 6, 7, 8, 9, 11, 12, 13, 14, 16, 17, 18, 19, 21, 22, 23, 24)

private val TIP_OPENXR_PAIRS =
    arrayOf(4 to 5, 9 to 10, 14 to 15, 19 to 20, 24 to 25)

private val OPENXR_JOINT_NAMES =
    arrayOf(
        "palm_ext",
        "wrist_ext",
        "thumb_metacarpal_ext",
        "thumb_proximal_ext",
        "thumb_distal_ext",
        "thumb_tip_ext",
        "index_metacarpal_ext",
        "index_proximal_ext",
        "index_intermediate_ext",
        "index_distal_ext",
        "index_tip_ext",
        "middle_metacarpal_ext",
        "middle_proximal_ext",
        "middle_intermediate_ext",
        "middle_distal_ext",
        "middle_tip_ext",
        "ring_metacarpal_ext",
        "ring_proximal_ext",
        "ring_intermediate_ext",
        "ring_distal_ext",
        "ring_tip_ext",
        "little_metacarpal_ext",
        "little_proximal_ext",
        "little_intermediate_ext",
        "little_distal_ext",
        "little_tip_ext",
    )
