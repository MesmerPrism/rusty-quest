package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun activityMarkerToken(value: String): String =
    value
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .ifBlank { "none" }
        .take(96)

internal fun activityReadFloatSystemProperty(
    propertyName: String,
    fallback: Float,
    min: Float,
    max: Float,
): Float {
  val text = activityReadSystemProperty(propertyName)
  val parsed = text.toFloatOrNull()
  return if (parsed != null && parsed.isFinite()) parsed.coerceIn(min, max) else fallback
}

internal fun activityReadIntSystemProperty(
    propertyName: String,
    fallback: Int,
    min: Int,
    max: Int,
): Int {
  val parsed = activityReadSystemProperty(propertyName).toIntOrNull()
  return parsed?.coerceIn(min, max) ?: fallback
}

internal fun activityReadLongSystemProperty(
    propertyName: String,
    fallback: Long,
    min: Long,
    max: Long,
): Long {
  val parsed = activityReadSystemProperty(propertyName).toLongOrNull()
  return parsed?.coerceIn(min, max) ?: fallback
}

internal fun activityReadOptionalFloatSystemProperty(
    propertyName: String,
    min: Float,
    max: Float,
): Float? {
  val parsed = activityReadSystemProperty(propertyName).toFloatOrNull()
  return if (parsed != null && parsed.isFinite()) parsed.coerceIn(min, max) else null
}

internal fun activityReadOptionalBooleanSystemProperty(propertyName: String): Boolean? {
  return when (activityReadSystemProperty(propertyName).lowercase(Locale.US)) {
    "1", "true", "yes", "on", "enabled" -> true
    "0", "false", "no", "off", "disabled" -> false
    else -> null
  }
}

internal fun activityReadOptionalStringIntentExtra(intent: Intent?, extraName: String): String? =
    if (intent?.hasExtra(extraName) == true) {
      intent.getStringExtra(extraName)?.trim()
    } else {
      null
    }

internal fun activityReadOptionalBooleanIntentExtra(intent: Intent?, extraName: String): Boolean? =
    if (intent?.hasExtra(extraName) == true) {
      intent.getBooleanExtra(extraName, false)
    } else {
      null
    }

internal fun activityReadOptionalIntIntentExtra(
    intent: Intent?,
    extraName: String,
    min: Int,
    max: Int,
): Int? =
    if (intent?.hasExtra(extraName) == true) {
      intent.getIntExtra(extraName, min).coerceIn(min, max)
    } else {
      null
    }

internal fun activityReadOptionalFloatIntentExtra(
    intent: Intent?,
    extraName: String,
    min: Float,
    max: Float,
): Float? =
    if (intent?.hasExtra(extraName) == true) {
      val value = intent.getFloatExtra(extraName, min)
      if (value.isFinite()) value.coerceIn(min, max) else null
    } else {
      null
    }

internal fun activityParseBuildConfigBoolean(value: String, fallback: Boolean): Boolean =
    when (value.trim().lowercase(Locale.US)) {
      "1", "true", "yes", "on", "enabled" -> true
      "0", "false", "no", "off", "disabled" -> false
      else -> fallback
    }

internal fun activityReadSystemProperty(propertyName: String): String =
    runCatching {
          Class.forName("android.os.SystemProperties")
              .getMethod("get", String::class.java, String::class.java)
              .invoke(null, propertyName, "") as String
        }
        .getOrDefault("")
        .trim()

internal fun activityVectorMarker(vector: Vector3): String =
    "${activityMarkerFloat(vector.x)};${activityMarkerFloat(vector.y)};${activityMarkerFloat(vector.z)}"

internal fun activityQuaternionMarker(quaternion: Quaternion): String =
    "${activityMarkerFloat(quaternion.w)};${activityMarkerFloat(quaternion.x)};" +
        "${activityMarkerFloat(quaternion.y)};${activityMarkerFloat(quaternion.z)}"

internal fun activityMarkerFloat(value: Float): String = String.format(Locale.US, "%.4f", value)

internal fun activityMarkerFloat6(value: Float): String = String.format(Locale.US, "%.6f", value)

internal fun activityCross(left: Vector3, right: Vector3): Vector3 =
    Vector3(
        left.y * right.z - left.z * right.y,
        left.z * right.x - left.x * right.z,
        left.x * right.y - left.y * right.x,
    )

internal fun activityDot(left: Vector3, right: Vector3): Float =
    left.x * right.x + left.y * right.y + left.z * right.z

internal fun activityEyeOffsetRightMeters(offset: Vector3?): Float {
  val value = offset?.x ?: 0.0f
  return if (value.isNaN() || value.isInfinite()) {
    0.0f
  } else {
    value.coerceIn(-0.12f, 0.12f)
  }
}

internal fun activityVectorSubtract(left: Vector3, right: Vector3): Vector3 =
    Vector3(left.x - right.x, left.y - right.y, left.z - right.z)

internal fun activityVectorLength(vector: Vector3): Float =
    sqrt((vector.x * vector.x + vector.y * vector.y + vector.z * vector.z).toDouble()).toFloat()

internal fun activityRollStableParticleProjectionBasis(
    rawForward: Vector3,
    yawDegrees: Float,
): Triple<Vector3, Vector3, Vector3> {
  val worldUp = Vector3(0.0f, 1.0f, 0.0f)
  val baseForward = rawForward.activityNormalizedOr(Vector3(0.0f, 0.0f, -1.0f))
  val baseRight = activityRollStableRightForForward(baseForward)
  val yawRadians = Math.toRadians(yawDegrees.toDouble())
  val yawCos = cos(yawRadians).toFloat()
  val yawSin = sin(yawRadians).toFloat()
  val forward = (baseForward * yawCos + baseRight * yawSin).activityNormalizedOr(baseForward)
  val right = activityRollStableRightForForward(forward)
  val up = activityCross(right, forward).activityNormalizedOr(worldUp)
  return Triple(forward, right, up)
}

internal fun activityRollStableRightForForward(forward: Vector3): Vector3 {
  val worldUp = Vector3(0.0f, 1.0f, 0.0f)
  val depthForward = Vector3(0.0f, 0.0f, -1.0f)
  val worldUpRight = activityCross(forward, worldUp)
  if (activityVectorLength(worldUpRight) > 0.0001f) {
    return worldUpRight.activityNormalizedOr(Vector3(1.0f, 0.0f, 0.0f))
  }
  val depthRight = activityCross(forward, depthForward)
  if (activityVectorLength(depthRight) > 0.0001f) {
    return depthRight.activityNormalizedOr(Vector3(1.0f, 0.0f, 0.0f))
  }
  return Vector3(1.0f, 0.0f, 0.0f)
}

internal fun Vector3.activityNormalizedOr(fallback: Vector3): Vector3 {
  val length = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
  return if (length > 0.000001f) {
    Vector3(x / length, y / length, z / length)
  } else {
    fallback
  }
}
