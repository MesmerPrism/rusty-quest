package io.github.mesmerprism.rustyquest.spatial_video_control

import android.util.Log
import android.view.Surface
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.Equirect180ShapeOptions
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import kotlin.math.max
import kotlin.math.min

/**
 * Quest-owned Spatial SDK carrier for the example's closed video catalog.
 *
 * One immutable registration is created per build-time descriptor because a
 * Spatial video panel's shape and stereo mode are registration settings. A
 * selection destroys only the previous video entity and activates the matching
 * registration at the retained world anchor; the Activity and control panel
 * remain alive.
 */
class SpatialVideoPanelCoordinator(
    private val panelCatalog: VideoCatalog,
    private val onSurfaceReady: (String, Surface) -> Unit,
) {
  private val panelIds =
      listOf(
          R.id.video_surface_panel_01,
          R.id.video_surface_panel_02,
          R.id.video_surface_panel_03,
          R.id.video_surface_panel_04,
          R.id.video_surface_panel_05,
          R.id.video_surface_panel_06,
          R.id.video_surface_panel_07,
          R.id.video_surface_panel_08,
          R.id.video_surface_panel_09,
          R.id.video_surface_panel_10,
          R.id.video_surface_panel_11,
          R.id.video_surface_panel_12,
          R.id.video_surface_panel_13,
          R.id.video_surface_panel_14,
          R.id.video_surface_panel_15,
          R.id.video_surface_panel_16,
      )
  private val registrationIdByVideoId =
      panelCatalog.videos().mapIndexed { index, video -> video.id() to panelIds[index] }.toMap()
  private var worldAnchorPose: Pose? = null
  private var activeVideoId: String? = null
  private var entity: Entity? = null

  init {
    check(panelCatalog.videos().size <= panelIds.size) {
      "video catalog exceeds the fixed Spatial panel registration bound"
    }
  }

  fun panelRegistrations(): List<PanelRegistration> =
      panelCatalog.videos().map { video ->
        VideoSurfacePanelRegistration(
            requireNotNull(registrationIdByVideoId[video.id()]),
            surfaceConsumer = { _, surface ->
              if (activeVideoId == video.id()) {
                onSurfaceReady(video.id(), surface)
              }
            },
            settingsCreator = { mediaPanelSettings(video) },
            panelSetup = { panel, _ ->
              if (activeVideoId == video.id()) {
                Log.i(
                    LOG_TAG,
                    "channel=trusted-local-http-v1 status=video-panel-ready " +
                        markerFields(video) + " surfaceValid=${panel.surface.isValid}",
                )
              }
            },
        )
      }

  fun spawnAtViewer(viewerPose: Pose, initialVideoId: String) {
    if (worldAnchorPose == null) {
      worldAnchorPose = Pose(viewerPose.t, viewerPose.q)
    }
    select(initialVideoId, "initial-spawn")
  }

  fun select(videoId: String, reason: String = "remote-selection") {
    val video = panelCatalog.require(videoId)
    val anchor = checkNotNull(worldAnchorPose) { "video world anchor is not ready" }
    val registrationId = requireNotNull(registrationIdByVideoId[video.id()])
    entity?.destroy()
    entity = null
    activeVideoId = video.id()
    entity =
        Entity.create(
            Panel(registrationId),
            Transform(entityPose(video, anchor)),
            Visible(true),
        )
    Log.i(
        LOG_TAG,
        "channel=trusted-local-http-v1 status=video-carrier-activated " +
            "reason=$reason ${markerFields(video)} worldAnchored=true " +
            "activityRestarted=false controlPanelRetained=true",
    )
  }

  fun release() {
    entity?.destroy()
    entity = null
    activeVideoId = null
  }

  private fun entityPose(video: VideoCatalog.Video, anchor: Pose): Pose =
      if (video.projectionShape() == VideoCatalog.ProjectionShape.FLAT) {
        val direction = anchor.forward()
        Pose(
            anchor.t + direction * FLAT_PANEL_DISTANCE_METERS,
            Quaternion.lookRotationAroundY(direction),
        )
      } else {
        Pose(anchor.t, anchor.q)
      }

  private fun mediaPanelSettings(video: VideoCatalog.Video): MediaPanelSettings {
    val shape =
        when (video.projectionShape()) {
          VideoCatalog.ProjectionShape.FLAT -> {
            val aspect = video.perEyeAspectRatio().toFloat()
            val height =
                min(
                    FLAT_PANEL_MAX_HEIGHT_METERS,
                    max(FLAT_PANEL_MIN_HEIGHT_METERS, FLAT_PANEL_WIDTH_METERS / aspect),
                )
            QuadShapeOptions(width = FLAT_PANEL_WIDTH_METERS, height = height)
          }
          VideoCatalog.ProjectionShape.EQUIRECT_180 ->
              Equirect180ShapeOptions(radius = IMMERSIVE_RADIUS_METERS)
          VideoCatalog.ProjectionShape.EQUIRECT_360 ->
              Equirect360ShapeOptions(radius = IMMERSIVE_RADIUS_METERS)
        }
    val stereoMode =
        when (video.stereoLayout()) {
          VideoCatalog.StereoLayout.MONO -> StereoMode.None
          VideoCatalog.StereoLayout.SIDE_BY_SIDE_LEFT_RIGHT -> StereoMode.LeftRight
          VideoCatalog.StereoLayout.TOP_BOTTOM -> StereoMode.UpDown
        }
    return MediaPanelSettings(
        shape = shape,
        display = PixelDisplayOptions(width = video.widthPx(), height = video.heightPx()),
        rendering =
            MediaPanelRenderOptions(
                stereoMode = stereoMode,
                zIndex = VIDEO_BACKGROUND_Z_INDEX,
            ),
        input = PanelInputOptions(0),
    )
  }

  private fun markerFields(video: VideoCatalog.Video): String =
      "videoId=${video.id()} projectionShape=${video.projectionShape().protocolName()} " +
          "stereoLayout=${video.stereoLayout().protocolName()} " +
          "packedWidthPx=${video.widthPx()} packedHeightPx=${video.heightPx()} " +
          "radiusMeters=$IMMERSIVE_RADIUS_METERS"

  private companion object {
    const val LOG_TAG = "RustyQuestVideoControl"
    const val IMMERSIVE_RADIUS_METERS = 50.0f
    const val VIDEO_BACKGROUND_Z_INDEX = -40
    const val FLAT_PANEL_DISTANCE_METERS = 2.25f
    const val FLAT_PANEL_WIDTH_METERS = 1.45f
    const val FLAT_PANEL_MIN_HEIGHT_METERS = 0.50f
    const val FLAT_PANEL_MAX_HEIGHT_METERS = 1.60f
  }
}
