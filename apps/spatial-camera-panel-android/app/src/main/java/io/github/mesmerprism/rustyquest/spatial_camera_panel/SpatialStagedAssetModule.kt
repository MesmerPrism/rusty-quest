package io.github.mesmerprism.rustyquest.spatial_camera_panel

import android.content.Intent
import android.net.Uri
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal class SpatialStagedAssetModule(private val marker: (String) -> Unit) {
  private var activeEntity: Entity? = null
  private var activeKey = ""

  fun startIfRequested(intent: Intent?, reason: String): Boolean {
    val config = SpatialStagedAssetConfig.from(intent)
    val activationDecision = SpatialAdapterNativeAuthority.resolveAsset(config.activationInput())
    if (!activationDecision.applied) {
      destroy("activation-rejected-$reason")
      marker(
          "channel=spatial-sdk-asset-model status=activation-rejected module=${MODULE_ID} " +
              "assetModelEnabled=false requestReason=${markerToken(reason)} " +
              "activationReceiptSchema=$ACTIVATION_RECEIPT_SCHEMA " +
              "${activationDecision.markerFields()} highRateJsonPayload=false"
      )
      return false
    }
    if (config.meshUri.isBlank()) {
      marker(
          "channel=spatial-sdk-asset-model status=skipped reason=missing-mesh-uri " +
              "module=${MODULE_ID} requestReason=${markerToken(reason)} " +
              "${activationDecision.markerFields()} highRateJsonPayload=false"
      )
      return false
    }
    if (config.isRawFbxUri()) {
      marker(
          "channel=spatial-sdk-asset-model status=rejected reason=raw-fbx-uri " +
              "module=${MODULE_ID} sourceFormat=${config.sourceFormatToken()} " +
              "fbxConversionRequired=true sdkLoadableMeshUri=false " +
              "meshUriScheme=${config.meshUriSchemeToken()} " +
              "${activationDecision.markerFields()} highRateJsonPayload=false"
      )
      return false
    }

    val key = "${activationDecision.lockRevision}|${activationDecision.lockSha256}|${config.identityKey()}"
    if (activeEntity != null && activeKey == key) {
      marker(
          "channel=spatial-sdk-asset-model status=already-active module=${MODULE_ID} " +
              "label=${config.labelToken()} meshUriScheme=${config.meshUriSchemeToken()} " +
              "sourceFormat=${config.sourceFormatToken()} " +
              "activationEffectiveMarker=$ACTIVATION_EFFECTIVE_MARKER " +
              "${activationDecision.markerFields()} highRateJsonPayload=false"
      )
      return true
    }

    destroy("replace-request")

    return runCatching {
          val mesh =
              Mesh(
                  Uri.parse(config.meshUri),
                  MeshCollision.NoCollision,
                  SceneMaterial.UNLIT_SHADER,
              )
          val transform =
              Transform(
                  Pose(
                      Vector3(config.positionX, config.positionY, config.positionZ),
                      Quaternion(
                          config.rotationXDegrees,
                          config.rotationYDegrees,
                          config.rotationZDegrees,
                      ),
                  )
              )
          val scale = Scale(Vector3(config.scale))
          val visible = Visible(true)
          activeEntity =
              if (config.grabbable) {
                Entity.create(
                    mesh,
                    transform,
                    scale,
                    visible,
                    Grabbable(type = GrabbableType.PIVOT_Y),
                )
              } else {
                Entity.create(mesh, transform, scale, visible)
              }
          activeKey = key
          marker(
              "channel=spatial-sdk-asset-model status=entity-created module=${MODULE_ID} " +
                  "spatialSdk3dAssetModule=true sdkLoadableMeshUri=true " +
                  "meshComponent=SpatialSDK-Mesh uriSource=runtime-staged " +
                  "meshUriScheme=${config.meshUriSchemeToken()} sourceFormat=${config.sourceFormatToken()} " +
                  "label=${config.labelToken()} scale=${formatFloat(config.scale)} " +
                  "positionM=${formatFloat(config.positionX)};${formatFloat(config.positionY)};${formatFloat(config.positionZ)} " +
                  "rotationDegrees=${formatFloat(config.rotationXDegrees)};${formatFloat(config.rotationYDegrees)};${formatFloat(config.rotationZDegrees)} " +
                  "grabbable=${config.grabbable} collision=none defaultShader=unlit " +
                  "assetVisibilityBias=headset-visible-test-placement " +
                  "privateSourceAssetPackaged=false " +
                  "activationReceiptSchema=$ACTIVATION_RECEIPT_SCHEMA " +
                  "activationEffectiveMarker=$ACTIVATION_EFFECTIVE_MARKER " +
                  "${activationDecision.markerFields()} highRateJsonPayload=false"
          )
          true
        }
        .getOrElse { error ->
          activeEntity = null
          activeKey = ""
          marker(
              "channel=spatial-sdk-asset-model status=entity-create-failed module=${MODULE_ID} " +
                  "meshUriScheme=${config.meshUriSchemeToken()} sourceFormat=${config.sourceFormatToken()} " +
                  "error=${markerToken(error.javaClass.simpleName)} " +
                  "${activationDecision.markerFields()} highRateJsonPayload=false"
          )
          false
        }
  }

  fun destroy(reason: String) {
    val entity = activeEntity ?: return
    runCatching { entity.destroy() }
    activeEntity = null
    activeKey = ""
    marker(
        "channel=spatial-sdk-asset-model status=destroyed module=${MODULE_ID} " +
            "reason=${markerToken(reason)}"
    )
  }

  private data class SpatialStagedAssetConfig(
      val enabled: Boolean,
      val meshUri: String,
      val sourceFormat: String,
      val label: String,
      val positionX: Float,
      val positionY: Float,
      val positionZ: Float,
      val rotationXDegrees: Float,
      val rotationYDegrees: Float,
      val rotationZDegrees: Float,
      val scale: Float,
      val grabbable: Boolean,
      val activationProfileId: String,
      val activationProjectId: String,
      val activationFeatureId: String,
      val activationLockRevision: Long,
      val activationLockSha256: String,
  ) {
    fun identityKey(): String =
        listOf(
                meshUri,
                sourceFormatToken(),
                labelToken(),
                formatFloat(positionX),
                formatFloat(positionY),
                formatFloat(positionZ),
                formatFloat(rotationXDegrees),
                formatFloat(rotationYDegrees),
                formatFloat(rotationZDegrees),
                formatFloat(scale),
                grabbable.toString(),
            )
            .joinToString("|")

    fun activationInput(): SpatialAdapterRuntimeInput =
        SpatialAdapterRuntimeInput(
            enabled = enabled,
            profileId = activationProfileId,
            projectId = activationProjectId,
            featureId = activationFeatureId,
            lockRevision = activationLockRevision,
            lockSha256 = activationLockSha256,
        )

    fun isRawFbxUri(): Boolean {
      val parsed = runCatching { Uri.parse(meshUri) }.getOrNull() ?: return false
      val path = parsed.path?.lowercase(Locale.US) ?: return false
      return path.endsWith(".fbx")
    }

    fun meshUriSchemeToken(): String {
      val parsed = runCatching { Uri.parse(meshUri) }.getOrNull()
      return markerToken(parsed?.scheme ?: "path")
    }

    fun sourceFormatToken(): String = markerToken(sourceFormat.ifBlank { inferSourceFormat(meshUri) })

    fun labelToken(): String = markerToken(label.ifBlank { "staged-asset" })

    companion object {
      fun from(intent: Intent?): SpatialStagedAssetConfig {
        val meshUri = readString(intent, EXTRA_MESH_URI, PROPERTY_MESH_URI)
        return SpatialStagedAssetConfig(
            enabled = readBoolean(intent, EXTRA_ENABLED, PROPERTY_ENABLED, false),
            meshUri = meshUri,
            sourceFormat = readString(intent, EXTRA_SOURCE_FORMAT, PROPERTY_SOURCE_FORMAT),
            label = readString(intent, EXTRA_LABEL, PROPERTY_LABEL),
            positionX = readVector(intent, EXTRA_POSITION_M, PROPERTY_POSITION_M, 0, -0.55f),
            positionY = readVector(intent, EXTRA_POSITION_M, PROPERTY_POSITION_M, 1, 1.15f),
            positionZ = readVector(intent, EXTRA_POSITION_M, PROPERTY_POSITION_M, 2, -1.35f),
            rotationXDegrees =
                readVector(intent, EXTRA_ROTATION_DEGREES, PROPERTY_ROTATION_DEGREES, 0, 0.0f),
            rotationYDegrees =
                readVector(intent, EXTRA_ROTATION_DEGREES, PROPERTY_ROTATION_DEGREES, 1, 180.0f),
            rotationZDegrees =
                readVector(intent, EXTRA_ROTATION_DEGREES, PROPERTY_ROTATION_DEGREES, 2, 0.0f),
            scale =
                clamp(
                    readFloat(intent, EXTRA_SCALE, PROPERTY_SCALE, 0.25f),
                    MIN_SCALE,
                    MAX_SCALE,
                ),
            grabbable = readBoolean(intent, EXTRA_GRABBABLE, PROPERTY_GRABBABLE, true),
            activationProfileId =
                readString(intent, EXTRA_ACTIVATION_PROFILE_ID, PROPERTY_ACTIVATION_PROFILE_ID),
            activationProjectId =
                readString(intent, EXTRA_ACTIVATION_PROJECT_ID, PROPERTY_ACTIVATION_PROJECT_ID),
            activationFeatureId =
                readString(intent, EXTRA_ACTIVATION_FEATURE_ID, PROPERTY_ACTIVATION_FEATURE_ID),
            activationLockRevision =
                readLong(intent, EXTRA_ACTIVATION_LOCK_REVISION, PROPERTY_ACTIVATION_LOCK_REVISION),
            activationLockSha256 =
                readString(intent, EXTRA_ACTIVATION_LOCK_SHA256, PROPERTY_ACTIVATION_LOCK_SHA256),
        )
      }
    }
  }

  companion object {
    const val MODULE_ID = "spatial-sdk-staged-3d-asset"
    const val PROPERTY_ENABLED = "debug.rustyquest.spatial.asset_model.enabled"
    const val PROPERTY_MESH_URI = "debug.rustyquest.spatial.asset_model.mesh_uri"
    const val PROPERTY_SOURCE_FORMAT = "debug.rustyquest.spatial.asset_model.source_format"
    const val PROPERTY_LABEL = "debug.rustyquest.spatial.asset_model.label"
    const val PROPERTY_POSITION_M = "debug.rustyquest.spatial.asset_model.position_m"
    const val PROPERTY_ROTATION_DEGREES = "debug.rustyquest.spatial.asset_model.rotation_degrees"
    const val PROPERTY_SCALE = "debug.rustyquest.spatial.asset_model.scale"
    const val PROPERTY_GRABBABLE = "debug.rustyquest.spatial.asset_model.grabbable"
    const val PROPERTY_ACTIVATION_PROFILE_ID =
        "debug.rustyquest.spatial.asset_model.activation.profile_id"
    const val PROPERTY_ACTIVATION_PROJECT_ID =
        "debug.rustyquest.spatial.asset_model.activation.project_id"
    const val PROPERTY_ACTIVATION_FEATURE_ID =
        "debug.rustyquest.spatial.asset_model.activation.feature_id"
    const val PROPERTY_ACTIVATION_LOCK_REVISION =
        "debug.rustyquest.spatial.asset_model.activation.lock_revision"
    const val PROPERTY_ACTIVATION_LOCK_SHA256 =
        "debug.rustyquest.spatial.asset_model.activation.lock_sha256"

    const val ACTIVATION_PROFILE_ID =
        "profile.quest.spatial_camera_panel.spatial_asset_model_conformance"
    const val ACTIVATION_PROJECT_ID = "spatial-camera-panel"
    const val ACTIVATION_FEATURE_ID = "spatial-asset-model"
    const val ACTIVATION_RECEIPT_SCHEMA =
        "rusty.quest.spatial_asset_model.activation_receipt.v1"
    const val ACTIVATION_EFFECTIVE_MARKER = "rusty.quest.spatial_asset_model.effective"

    private const val EXTRA_ENABLED = "rusty.quest.spatial.asset_model.enabled"
    private const val EXTRA_MESH_URI = "rusty.quest.spatial.asset_model.mesh_uri"
    private const val EXTRA_SOURCE_FORMAT = "rusty.quest.spatial.asset_model.source_format"
    private const val EXTRA_LABEL = "rusty.quest.spatial.asset_model.label"
    private const val EXTRA_POSITION_M = "rusty.quest.spatial.asset_model.position_m"
    private const val EXTRA_ROTATION_DEGREES = "rusty.quest.spatial.asset_model.rotation_degrees"
    private const val EXTRA_SCALE = "rusty.quest.spatial.asset_model.scale"
    private const val EXTRA_GRABBABLE = "rusty.quest.spatial.asset_model.grabbable"
    private const val EXTRA_ACTIVATION_PROFILE_ID =
        "rusty.quest.spatial.asset_model.activation.profile_id"
    private const val EXTRA_ACTIVATION_PROJECT_ID =
        "rusty.quest.spatial.asset_model.activation.project_id"
    private const val EXTRA_ACTIVATION_FEATURE_ID =
        "rusty.quest.spatial.asset_model.activation.feature_id"
    private const val EXTRA_ACTIVATION_LOCK_REVISION =
        "rusty.quest.spatial.asset_model.activation.lock_revision"
    private const val EXTRA_ACTIVATION_LOCK_SHA256 =
        "rusty.quest.spatial.asset_model.activation.lock_sha256"

    private const val MIN_SCALE = 0.001f
    private const val MAX_SCALE = 10.0f

    fun startDeferredMarker(reason: String): String =
        "channel=spatial-sdk-asset-model status=start-deferred " +
            "module=$MODULE_ID reason=${markerToken(reason)} " +
            "deferredUntil=virtual-room-loaded spatialVirtualRoomLoaded=false " +
            "privateSourceAssetPackaged=false highRateJsonPayload=false"

    private fun readString(intent: Intent?, extraName: String, propertyName: String): String {
      val extraValue = intent?.getStringExtra(extraName)?.trim().orEmpty()
      if (extraValue.isNotBlank()) {
        return extraValue
      }
      return readSystemProperty(propertyName).trim()
    }

    private fun readBoolean(
        intent: Intent?,
        extraName: String,
        propertyName: String,
        defaultValue: Boolean,
    ): Boolean {
      if (intent?.hasExtra(extraName) == true) {
        return intent.getBooleanExtra(extraName, defaultValue)
      }
      val value = readSystemProperty(propertyName)
      return when (value.trim().lowercase(Locale.US)) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
      }
    }

    private fun readFloat(
        intent: Intent?,
        extraName: String,
        propertyName: String,
        defaultValue: Float,
    ): Float {
      if (intent?.hasExtra(extraName) == true) {
        return intent.getFloatExtra(extraName, defaultValue)
      }
      return readSystemProperty(propertyName).toFloatOrNull() ?: defaultValue
    }

    private fun readLong(intent: Intent?, extraName: String, propertyName: String): Long {
      if (intent?.hasExtra(extraName) == true) {
        return intent.getLongExtra(extraName, 0L)
      }
      return readSystemProperty(propertyName).toLongOrNull() ?: 0L
    }

    private fun readVector(
        intent: Intent?,
        extraName: String,
        propertyName: String,
        index: Int,
        defaultValue: Float,
    ): Float {
      val extraValue = intent?.getStringExtra(extraName)
      val value = if (extraValue.isNullOrBlank()) readSystemProperty(propertyName) else extraValue
      return value
          .split(';', ',', ' ')
          .mapNotNull { part -> part.trim().takeIf { it.isNotBlank() }?.toFloatOrNull() }
          .getOrNull(index) ?: defaultValue
    }

    private fun readSystemProperty(propertyName: String): String =
        runCatching {
              val systemProperties = Class.forName("android.os.SystemProperties")
              val get = systemProperties.getMethod("get", String::class.java, String::class.java)
              get.invoke(null, propertyName, "") as String
            }
            .getOrDefault("")

    private fun inferSourceFormat(meshUri: String): String {
      val path = runCatching { Uri.parse(meshUri).path }.getOrNull().orEmpty().lowercase(Locale.US)
      return when {
        path.endsWith(".glb") -> "glb"
        path.endsWith(".gltf") -> "gltf"
        path.endsWith(".fbx") -> "fbx"
        else -> "mesh-uri"
      }
    }

    private fun markerToken(value: String): String =
        value.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_.:-]+"), "-").ifBlank { "none" }

    private fun formatFloat(value: Float): String =
        String.format(Locale.US, "%.3f", value.toDouble()).trimEnd('0').trimEnd('.')

    private fun clamp(value: Float, minValue: Float, maxValue: Float): Float =
        max(minValue, min(value, maxValue))
  }
}
