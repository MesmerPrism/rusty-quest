package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCreationSystem
import com.meta.spatial.toolkit.MeshMaterialOverrides
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import java.util.Locale

internal class SpatialAvatarHandInvestigationFeature(
    private val marker: (String) -> Unit,
) : SpatialFeature {
  override fun lateSystemsToRegister(): List<SystemBase> =
      listOf(SpatialAvatarHandInvestigationSystem(marker))
}

@OptIn(SpatialSDKExperimentalAPI::class)
private class SpatialAvatarHandInvestigationSystem(
    private val marker: (String) -> Unit,
) : SystemBase() {
  private var frameIndex = 0
  private var disabledLogged = false
  private var capabilityLogged = false

  override fun execute() {
    frameIndex += 1
    val config = SpatialAvatarHandInvestigationConfig.read()
    if (!config.enabled) {
      if (!disabledLogged) {
        disabledLogged = true
        marker(
            "channel=spatial-avatar-hand-investigation status=disabled " +
                "enabledProperty=${SpatialAvatarHandInvestigationConfig.PROPERTY_ENABLED} " +
                "samplePeriodFrames=${config.samplePeriodFrames} " +
                "publicApiProbe=inactive"
        )
      }
      return
    }
    disabledLogged = false

    if (!capabilityLogged) {
      capabilityLogged = true
      marker(
          "channel=spatial-avatar-hand-investigation status=capability " +
              "schema=$PROBE_SCHEMA spatialSdkVersion=0.13.1 " +
              "avatarSystemPublicApi=setShowHands-setShowControllers-getters " +
              "avatarSystemMaterialSurfacePublic=false " +
              "sceneMeshVertexReadbackPublic=false sceneMeshIndexReadbackPublic=false " +
              "triangleMeshWritePublic=true meshManagerAssociatedFilesPublic=true " +
              "internalHandShaderAssetsKnown=true internalSdkAssetCopyPolicy=do-not-copy " +
              "wireframeFallbackProvider=spatial-hand-billboard-flock-trianglemesh"
      )
    }

    if (frameIndex % config.samplePeriodFrames != 0) {
      return
    }

    val sample =
        runCatching { collectSample(config) }
            .getOrElse { throwable ->
              marker(
                  "channel=spatial-avatar-hand-investigation status=sample-failed " +
                      "schema=$PROBE_SCHEMA error=${markerToken(throwable.javaClass.simpleName)}"
              )
              return
            }
    marker(sample.summaryMarker())
    sample.details.take(config.detailLimit).forEach { marker(it.detailMarker()) }
  }

  private fun collectSample(config: SpatialAvatarHandInvestigationConfig): SpatialAvatarHandSample {
    val avatarSystem =
        runCatching { systemManager.tryFindSystem(AvatarSystem::class) }.getOrNull()
    val meshCreationSystem =
        runCatching { systemManager.tryFindSystem(MeshCreationSystem::class) }.getOrNull()
    val meshManager = meshCreationSystem?.meshManager
    val meshCreators =
        runCatching { meshManager?.meshCreators?.keys?.map(::markerToken)?.sorted().orEmpty() }
            .getOrDefault(emptyList())
    val cachedMeshTokenCount =
        runCatching { meshManager?.cachedMeshTokens()?.size ?: 0 }.getOrDefault(0)

    val avatarBodyEntities = queryEntities(AvatarBody.id, "AvatarBody")
    val avatarAttachmentEntities = queryEntities(AvatarAttachment.id, "AvatarAttachment")
    val controllerEntities = queryEntities(Controller.id, "Controller")
    val meshEntities = queryEntities(Mesh.id, "Mesh")
    val materialEntities = queryEntities(Material.id, "Material")
    val visibleEntities = queryEntities(Visible.id, "Visible")
    val materialOverrideEntities = queryEntities(MeshMaterialOverrides.id, "MeshMaterialOverrides")

    var localAvatarBodyCount = 0
    var playerAvatarBodyCount = 0
    var avatarHandTransformCount = 0
    val candidates = linkedMapOf<Long, SpatialAvatarHandCandidate>()

    avatarBodyEntities.forEach { entity ->
      val avatarBody = entity.tryGetComponent<AvatarBody>() ?: return@forEach
      val localBody = runCatching { entity.isLocal() }.getOrDefault(false)
      if (localBody) {
        localAvatarBodyCount += 1
      }
      if (localBody && avatarBody.isPlayerControlled) {
        playerAvatarBodyCount += 1
        addCandidate(candidates, avatarBody.leftHand, "avatarbody-left-hand")
        addCandidate(candidates, avatarBody.rightHand, "avatarbody-right-hand")
        if (avatarBody.leftHand.tryGetComponent<Transform>() != null) {
          avatarHandTransformCount += 1
        }
        if (avatarBody.rightHand.tryGetComponent<Transform>() != null) {
          avatarHandTransformCount += 1
        }
      }
    }

    avatarAttachmentEntities.forEach { entity ->
      val attachmentType =
          entity.tryGetComponent<AvatarAttachment>()?.type?.trim()?.lowercase(Locale.US).orEmpty()
      if (attachmentType.contains("hand") || attachmentType.contains("controller")) {
        addCandidate(candidates, entity, "avatarattachment-$attachmentType")
      }
    }

    var handControllerCount = 0
    var activeHandControllerCount = 0
    var localControllerCount = 0
    controllerEntities.forEach { entity ->
      val controller = entity.tryGetComponent<Controller>() ?: return@forEach
      if (runCatching { entity.isLocal() }.getOrDefault(false)) {
        localControllerCount += 1
      }
      if (controller.type == ControllerType.HAND) {
        handControllerCount += 1
        if (controller.isActive) {
          activeHandControllerCount += 1
        }
        addCandidate(candidates, entity, "controller-type-hand")
      }
    }

    meshEntities.forEach { entity ->
      val meshUri = entity.tryGetComponent<Mesh>()?.mesh?.toString()?.lowercase(Locale.US).orEmpty()
      if (meshUri.contains("hand") || meshUri.contains("avatar") || meshUri.contains("controller")) {
        addCandidate(candidates, entity, "mesh-uri-$meshUri")
      }
    }

    val candidateDetails =
        candidates.values.mapIndexed { index, candidate ->
          candidate.detail(index, meshManager, config.includeAssociatedMeshFiles)
        }
    val handCandidateMeshCount = candidateDetails.count { it.hasMesh }
    val handCandidateMaterialCount = candidateDetails.count { it.hasMaterial || it.hasMaterialOverrides }
    val publicHandMeshObserved = handCandidateMeshCount > 0
    val meshExtractionStatus =
        if (publicHandMeshObserved) {
          "metadata-only-no-public-topology-readback"
        } else {
          "not-observed-through-public-ecs"
        }
    val wireframeAttachTarget =
        if (publicHandMeshObserved) {
          "public-hand-entity-transform-proxy"
        } else {
          "avatarbody-hand-transform-proxy"
        }

    return SpatialAvatarHandSample(
        schema = PROBE_SCHEMA,
        frameIndex = frameIndex,
        avatarSystemFound = avatarSystem != null,
        avatarHandsVisible = avatarSystem?.getShowHands(),
        avatarControllersVisible = avatarSystem?.getShowControllers(),
        avatarBodyCount = avatarBodyEntities.size,
        localAvatarBodyCount = localAvatarBodyCount,
        playerAvatarBodyCount = playerAvatarBodyCount,
        avatarAttachmentCount = avatarAttachmentEntities.size,
        controllerCount = controllerEntities.size,
        localControllerCount = localControllerCount,
        handControllerCount = handControllerCount,
        activeHandControllerCount = activeHandControllerCount,
        meshEntityCount = meshEntities.size,
        materialEntityCount = materialEntities.size,
        visibleEntityCount = visibleEntities.size,
        materialOverrideEntityCount = materialOverrideEntities.size,
        handCandidateCount = candidateDetails.size,
        handCandidateMeshCount = handCandidateMeshCount,
        handCandidateMaterialCount = handCandidateMaterialCount,
        avatarHandTransformCount = avatarHandTransformCount,
        meshCreationSystemFound = meshCreationSystem != null,
        meshCreatorCount = meshCreators.size,
        meshCreators = meshCreators.take(8),
        cachedMeshTokenCount = cachedMeshTokenCount,
        publicHandMeshObserved = publicHandMeshObserved,
        meshExtractionStatus = meshExtractionStatus,
        wireframeAttachTarget = wireframeAttachTarget,
        details = candidateDetails,
    )
  }

  private fun queryEntities(componentId: Int, componentName: String): List<Entity> =
      runCatching { Query.where { has(componentId) }.eval().toList() }
          .getOrElse { throwable ->
            marker(
                "channel=spatial-avatar-hand-investigation status=query-failed " +
                    "schema=$PROBE_SCHEMA component=${markerToken(componentName)} " +
                    "error=${markerToken(throwable.javaClass.simpleName)}"
            )
            emptyList()
          }

  private fun addCandidate(
      candidates: LinkedHashMap<Long, SpatialAvatarHandCandidate>,
      entity: Entity,
      reason: String,
  ) {
    val candidate =
        candidates.getOrPut(entity.id) {
          SpatialAvatarHandCandidate(entity, mutableListOf())
        }
    candidate.reasons.add(markerToken(reason))
  }
}

@OptIn(SpatialSDKExperimentalAPI::class)
private data class SpatialAvatarHandCandidate(
    val entity: Entity,
    val reasons: MutableList<String>,
) {
  fun detail(
      index: Int,
      meshManager: com.meta.spatial.toolkit.MeshManager?,
      includeAssociatedMeshFiles: Boolean,
  ): SpatialAvatarHandCandidateDetail {
    val attachment = entity.tryGetComponent<AvatarAttachment>()
    val controller = entity.tryGetComponent<Controller>()
    val mesh = entity.tryGetComponent<Mesh>()
    val material = entity.tryGetComponent<Material>()
    val visible = entity.tryGetComponent<Visible>()
    val overrides = entity.tryGetComponent<MeshMaterialOverrides>()
    val transform = entity.tryGetComponent<Transform>()?.transform
    val associatedFiles =
        if (includeAssociatedMeshFiles && meshManager != null && mesh != null) {
          runCatching {
                meshManager.retrieveAssociatedMeshFilesForEntity(entity)
                    ?.map { markerToken(it.toString()) }
                    ?.take(6)
                    ?: emptyList()
              }
              .getOrDefault(emptyList())
        } else {
          emptyList()
        }
    return SpatialAvatarHandCandidateDetail(
        index = index,
        entityId = entity.id,
        local = runCatching { entity.isLocal() }.getOrDefault(false),
        reasons = reasons.distinct().take(8),
        attachmentType = attachment?.type ?: "none",
        controllerType = controller?.type?.name ?: "none",
        controllerActive = controller?.isActive ?: false,
        hasTransform = transform != null,
        transform = transform,
        hasMesh = mesh != null,
        meshUri = mesh?.mesh?.toString() ?: "none",
        meshCollision = mesh?.hittable?.name ?: "none",
        meshDefaultShaderOverride = mesh?.defaultShaderOverride ?: "none",
        hasMaterial = material != null,
        materialShader = material?.shader ?: "none",
        materialUnlit = material?.unlit ?: false,
        materialBaseTextureResourceId = material?.baseTextureAndroidResourceId ?: 0,
        hasMaterialOverrides = overrides != null,
        materialOverrideSize = overrides?.size ?: 0,
        visible = visible?.isVisible,
        associatedMeshFiles = associatedFiles,
    )
  }
}

private data class SpatialAvatarHandSample(
    val schema: String,
    val frameIndex: Int,
    val avatarSystemFound: Boolean,
    val avatarHandsVisible: Boolean?,
    val avatarControllersVisible: Boolean?,
    val avatarBodyCount: Int,
    val localAvatarBodyCount: Int,
    val playerAvatarBodyCount: Int,
    val avatarAttachmentCount: Int,
    val controllerCount: Int,
    val localControllerCount: Int,
    val handControllerCount: Int,
    val activeHandControllerCount: Int,
    val meshEntityCount: Int,
    val materialEntityCount: Int,
    val visibleEntityCount: Int,
    val materialOverrideEntityCount: Int,
    val handCandidateCount: Int,
    val handCandidateMeshCount: Int,
    val handCandidateMaterialCount: Int,
    val avatarHandTransformCount: Int,
    val meshCreationSystemFound: Boolean,
    val meshCreatorCount: Int,
    val meshCreators: List<String>,
    val cachedMeshTokenCount: Int,
    val publicHandMeshObserved: Boolean,
    val meshExtractionStatus: String,
    val wireframeAttachTarget: String,
    val details: List<SpatialAvatarHandCandidateDetail>,
) {
  fun summaryMarker(): String =
      "channel=spatial-avatar-hand-investigation status=sample " +
          "schema=$schema frameIndex=$frameIndex " +
          "avatarSystemFound=$avatarSystemFound " +
          "avatarHandsVisible=${avatarHandsVisible ?: "unknown"} " +
          "avatarControllersVisible=${avatarControllersVisible ?: "unknown"} " +
          "avatarBodyCount=$avatarBodyCount localAvatarBodyCount=$localAvatarBodyCount " +
          "playerAvatarBodyCount=$playerAvatarBodyCount " +
          "avatarAttachmentCount=$avatarAttachmentCount controllerCount=$controllerCount " +
          "localControllerCount=$localControllerCount handControllerCount=$handControllerCount " +
          "activeHandControllerCount=$activeHandControllerCount " +
          "meshEntityCount=$meshEntityCount materialEntityCount=$materialEntityCount " +
          "visibleEntityCount=$visibleEntityCount " +
          "materialOverrideEntityCount=$materialOverrideEntityCount " +
          "handCandidateCount=$handCandidateCount handCandidateMeshCount=$handCandidateMeshCount " +
          "handCandidateMaterialCount=$handCandidateMaterialCount " +
          "avatarHandTransformCount=$avatarHandTransformCount " +
          "meshCreationSystemFound=$meshCreationSystemFound meshCreatorCount=$meshCreatorCount " +
          "meshCreators=${markerList(meshCreators)} cachedMeshTokenCount=$cachedMeshTokenCount " +
          "sdkBuiltInHandMeshPubliclyObserved=$publicHandMeshObserved " +
          "spatialAvatarHandMeshWireframeSupported=false " +
          "meshExtractionStatus=$meshExtractionStatus " +
          "skinningExtractionStatus=not-public-through-spatial-sdk-api " +
          "wireframeAttachTarget=$wireframeAttachTarget " +
          "wireframeFallbackProvider=spatial-hand-billboard-flock-trianglemesh"
}

private data class SpatialAvatarHandCandidateDetail(
    val index: Int,
    val entityId: Long,
    val local: Boolean,
    val reasons: List<String>,
    val attachmentType: String,
    val controllerType: String,
    val controllerActive: Boolean,
    val hasTransform: Boolean,
    val transform: Pose?,
    val hasMesh: Boolean,
    val meshUri: String,
    val meshCollision: String,
    val meshDefaultShaderOverride: String,
    val hasMaterial: Boolean,
    val materialShader: String,
    val materialUnlit: Boolean,
    val materialBaseTextureResourceId: Int,
    val hasMaterialOverrides: Boolean,
    val materialOverrideSize: Int,
    val visible: Boolean?,
    val associatedMeshFiles: List<String>,
) {
  fun detailMarker(): String =
      "channel=spatial-avatar-hand-investigation-detail status=entity " +
          "schema=$PROBE_SCHEMA index=$index entityId=$entityId local=$local " +
          "candidateReasons=${markerList(reasons)} attachmentType=${markerToken(attachmentType)} " +
          "controllerType=${markerToken(controllerType)} controllerActive=$controllerActive " +
          "hasTransform=$hasTransform transform=${poseMarker(transform)} " +
          "hasMesh=$hasMesh meshUri=${markerToken(meshUri)} " +
          "meshCollision=${markerToken(meshCollision)} " +
          "meshDefaultShaderOverride=${markerToken(meshDefaultShaderOverride)} " +
          "hasMaterial=$hasMaterial materialShader=${markerToken(materialShader)} " +
          "materialUnlit=$materialUnlit materialBaseTextureResourceId=$materialBaseTextureResourceId " +
          "hasMaterialOverrides=$hasMaterialOverrides materialOverrideSize=$materialOverrideSize " +
          "visible=${visible ?: "unknown"} associatedMeshFiles=${markerList(associatedMeshFiles)}"
}

private data class SpatialAvatarHandInvestigationConfig(
    val enabled: Boolean,
    val samplePeriodFrames: Int,
    val detailLimit: Int,
    val includeAssociatedMeshFiles: Boolean,
) {
  companion object {
    const val PROPERTY_ENABLED = "debug.rustyquest.spatial.avatar_hand_probe.enabled"
    private const val PROPERTY_SAMPLE_PERIOD_FRAMES =
        "debug.rustyquest.spatial.avatar_hand_probe.sample_period_frames"
    private const val PROPERTY_DETAIL_LIMIT =
        "debug.rustyquest.spatial.avatar_hand_probe.detail_limit"
    private const val PROPERTY_ASSOCIATED_FILES =
        "debug.rustyquest.spatial.avatar_hand_probe.associated_files"

    fun read(): SpatialAvatarHandInvestigationConfig =
        SpatialAvatarHandInvestigationConfig(
            enabled = readBooleanSystemProperty(PROPERTY_ENABLED, false),
            samplePeriodFrames = readIntSystemProperty(PROPERTY_SAMPLE_PERIOD_FRAMES, 60, 1, 600),
            detailLimit = readIntSystemProperty(PROPERTY_DETAIL_LIMIT, 12, 0, 64),
            includeAssociatedMeshFiles = readBooleanSystemProperty(PROPERTY_ASSOCIATED_FILES, true),
        )
  }
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

private fun readSystemProperty(propertyName: String): String =
    runCatching {
          val systemProperties = Class.forName("android.os.SystemProperties")
          val get = systemProperties.getMethod("get", String::class.java, String::class.java)
          get.invoke(null, propertyName, "") as String
        }
        .getOrDefault("")

private fun markerToken(value: String): String =
    value
        .trim()
        .replace('\u0000', '_')
        .replace(Regex("[^A-Za-z0-9._:/-]+"), "-")
        .ifBlank { "none" }
        .take(160)

private fun markerList(values: List<String>): String =
    if (values.isEmpty()) {
      "none"
    } else {
      values.joinToString("|") { markerToken(it) }.take(220)
    }

private fun poseMarker(pose: Pose?): String =
    if (pose == null) {
      "none"
    } else {
      "${vectorMarker(pose.t)};${quaternionMarker(pose.q)}"
    }

private fun vectorMarker(vector: Vector3): String =
    "${formatFloat(vector.x)},${formatFloat(vector.y)},${formatFloat(vector.z)}"

private fun quaternionMarker(quaternion: com.meta.spatial.core.Quaternion): String =
    "${formatFloat(quaternion.x)},${formatFloat(quaternion.y)}," +
        "${formatFloat(quaternion.z)},${formatFloat(quaternion.w)}"

private fun formatFloat(value: Float): String = String.format(Locale.US, "%.4f", value)

private const val PROBE_SCHEMA = "rusty.quest.spatial.avatar_hand_investigation.v1"
