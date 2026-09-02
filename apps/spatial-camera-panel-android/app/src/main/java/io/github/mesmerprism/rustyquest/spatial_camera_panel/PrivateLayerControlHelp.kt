package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.runtime.staticCompositionLocalOf

internal data class PrivateLayerControlHelpEntry(
    val title: String,
    val description: String,
)

internal val LocalPrivateLayerControlHelpRequest =
    staticCompositionLocalOf<(String) -> Unit> { {} }

/** Central copy for the compact, controller-friendly help strip in the fixed panel header. */
internal object PrivateLayerControlHelp {
  val requiredGroupLabels =
      listOf(
          "Custom projection",
          "Rendering layer",
          "Background",
          "Shared media folder",
          "Video playback",
          "Video presentation",
          "Active video",
          "Projection depth",
          "Surface topology",
          "Inner transparency",
          "Transparency driver",
          "Stretch transparency policy",
          "RGB transform mode",
          "RGB edge handling",
          "Buffer geometry",
          "Buffer content",
          "Stretch starting point",
          "Stretch extent",
          "Stretch attachment",
          "Stretch source",
          "Center–Middle transition signal",
          "Middle–Outer transition signal",
          "Outer target",
          "Debug view",
          "Camera sampling",
          "Guide processing preset",
          "Guide preblur kernel",
          "Guide input",
          "Guide postblur kernel",
          "Depth source",
          "Depth metadata alignment",
      )

  fun forLabel(rawLabel: String): PrivateLayerControlHelpEntry {
    val label = rawLabel.trim()
    val normalized = label.lowercase()
    val description =
        when {
          normalized == "custom projection" ->
              "Shows or hides the head-mounted custom projection without disabling the separate immersive-video carrier."
          normalized == "rendering layer" ->
              "Chooses which intermediate or final render target is shown on the custom projection."
          normalized == "background" ->
              "Black keeps an opaque backing behind the video layer; Passthrough reveals the system camera layer; LUT passthrough applies the existing animated Spatial SDK color LUT."
          normalized == "shared media folder" ->
              "Grants persistent access to an external RustySpatialMedia folder. If the provider permits it, the app creates only the fixed plain-video folder taxonomy; it never writes video bytes. Encrypted packs stay under offline-media-packs. Plain files use plain-videos/<shape>/<stereo>; the app checks container metadata and a sampled frame before listing them. Nothing is copied into the APK."
          normalized == "video playback" ->
              "Starts or pauses the current immersive video without changing its selected item or projection settings."
          normalized == "video presentation" ->
              "World anchored keeps the immersive carrier fixed in space; head-fixed border keeps it attached to the viewer. Packed top-bottom video remains split into top-left-eye and bottom-right-eye views in either mode."
          normalized == "active video" ->
              "Moves to the previous or next catalog item. Stored tuning profiles intentionally do not change this selection."
          normalized == "projection scale" ->
              "Changes the overall angular size of the custom projection target. Screen-space stretch inset becomes relatively deeper as this target shrinks."
          normalized == "projection depth" ->
              "Selects whether guide depth moves the projection surface and how strongly it moves."
          normalized == "maximum displacement" ->
              "Caps how far guide depth may move the projection surface from its reference plane."
          normalized == "reference distance" ->
              "Sets the distance represented by the undisplaced projection surface."
          normalized == "polarity" ->
              "Reverses whether nearer guide values pull the surface toward or away from the viewer."
          normalized == "edge taper" ->
              "Reduces depth displacement near the projection boundary to avoid a hard depth discontinuity."
          normalized == "surface topology" ->
              "Switches between a connected surface, square tiles, or independently centered triangle tiles."
          normalized == "tile gap" ->
              "Adds uniform separation between tiles while preserving the original content coordinates on each tile."
          normalized == "tile depth flexibility" ->
              "Blends tile-center depth motion from rigid planar tiles toward the full requested depth displacement."
          normalized == "tile scope" ->
              "Chooses whether tiling affects Inner plus the effective buffer region, regardless of its content, or Inner only. Stretch outside the buffer is not included."
          normalized == "inner transparency" ->
              "Enables color-driven alpha inside the custom projection so lower layers can show through."
          normalized == "transparency driver" ->
              "Selects the processed color channel or luminance measure that generates transparency."
          normalized == "threshold" ->
              "Sets the channel value around which inner pixels begin becoming transparent."
          normalized == "softness" ->
              "Widens the transition around the threshold so alpha changes gradually instead of cutting sharply."
          normalized == "amount" ->
              "Scales the overall strength of color-driven transparency from opaque to fully applied."
          normalized == "transparency direction" ->
              "Normal and invert choose which side of the color threshold becomes transparent."
          normalized == "stretch transparency policy" ->
              "Lets stretch follow the projection alpha rules or stay independently opaque."
          normalized == "exact projection mask" ->
              "Makes stretch use the exact inner mask instead of only following the general transparency policy."
          normalized == "rgb transform mode" ->
              "Bypass leaves channels together; linked applies one motion to all channels; independent gives red, green, and blue separate controls."
          normalized == "rgb edge handling" ->
              "Chooses how displaced channel samples behave at image edges: clamp, mirror, or fade."
          normalized == "direction" ->
              "Sets the displacement direction for this color channel in turns around the image plane."
          normalized == "direction speed" ->
              "Rotates this channel's displacement direction over time; negative values reverse rotation."
          normalized == "strength" ->
              "Sets this channel's displacement distance in normalized image coordinates."
          normalized == "image scale" ->
              "Scales sampled imagery for this channel around its center."
          normalized == "coverage scale" ->
              "Restricts how much of the target this channel covers, useful for controlled edge reveals."
          normalized == "buffer geometry" ->
              "Off removes the middle region, Static gives it a fixed width, and Dynamic uses the margin released by the anti-image-drag footprint. This does not choose its content."
          normalized == "static buffer width" ->
              "Expands Middle outward from the Center boundary by a fixed normalized amount, clipped to the current projection area."
          normalized == "buffer content" ->
              "Chooses what occupies an active buffer: the selected Outer target, a transparent reveal, or Stretch. Geometry and content remain independent."
          normalized == "stretch starting point" ->
              "Loads only Stretch-owned sampling parameters. It does not change buffer geometry, transition settings, or the Outer target."
          normalized == "stretch extent" ->
              "Buffer only confines Stretch to the middle region. Replace Outer continues Stretch from the buffer through the carrier edge while retaining the Outer selection for later."
          normalized == "stretch attachment" ->
              "Selects the sampling and seam-attachment geometry used only by Stretch: standard blend, legacy sample warp, smooth radial trail, or the optimized seamless attachment."
          normalized == "stretch source" ->
              "Chooses raw camera color, processed projection color, or a blend as the source pulled into the stretch region."
          normalized == "edge inset" ->
              "Sets the first source-space inset used at the projection boundary."
          normalized == "maximum inset" ->
              "Caps how deeply the outermost stretch samples pull from inside the source image."
          normalized == "stretch curve" ->
              "Shapes how quickly source inset increases from the inner boundary toward the outer field."
          normalized == "processed source mix" ->
              "Blends raw and processed imagery when Stretch source is set to Mix."
          normalized == "outer target" ->
              "Chooses readable same-surface video or transparency that reveals the separate world-anchored 180/360 Spatial video. It does not choose buffer content."
          normalized == "blend test preset" ->
              "Loads deterministic component, region, or underlay transition stimuli for inspecting boundaries. Synthetic region tests disable surface displacement for clarity."
          normalized.contains("width") ->
              "Sets the width of this blend band inside the outgoing region. It softens the boundary but does not resize Center, Middle, or Outer."
          normalized.contains("spatial curve") ->
              "Shapes easing across this blend band; higher values concentrate the change toward the incoming side without moving the boundary."
          normalized.contains("threshold") ->
              "Sets the color-channel threshold used by this transition signal."
          normalized.contains("softness") ->
              "Controls how gradually this transition signal changes around its threshold."
          normalized.contains("channel influence") || normalized.endsWith(" influence") ->
              "Sets how strongly the selected color signal influences this transition."
          normalized.contains("cycle amount") ->
              "Sets the amplitude of time-varying modulation for this transition."
          normalized.contains("cycle speed") ->
              "Sets the modulation frequency in cycles per second."
          normalized.contains("phase") ->
              "Offsets this channel's modulation cycle relative to the other channels."
          normalized.contains("motion response") ->
              "Adds or subtracts transition response based on measured image motion."
          normalized.contains("transition signal") ->
              "Chooses the image measurement that modulates blending inside this boundary band. It does not select region content or change region size."
          normalized.contains("blend application") ->
              "Chooses legacy combined blending, independent color components, or a channel-selected regional driver."
          normalized.contains("blend sample") ->
              "Chooses whether dynamic blending samples the outgoing side, midpoint, or incoming side of the transition."
          normalized.contains("region driver") ->
              "Selects the channel used as the scalar driver when regional blending is active."
          normalized == "debug view" ->
              "Overlays region or sample-coordinate diagnostics; Normal returns to the intended composite."
          normalized == "camera sampling" ->
              "Switches the camera sampler between linear filtering and the thin-line antialiasing kernel."
          normalized == "guide processing preset" ->
              "Loads a known guide-blur/input combination as a starting point for image processing tests."
          normalized == "guide preblur kernel" ->
              "Selects the blur kernel applied before the guide signal is evaluated."
          normalized == "guide input" ->
              "Chooses luminance-only or RGB-preserving guide input."
          normalized == "guide postblur kernel" ->
              "Selects the blur kernel applied after the guide signal is formed."
          normalized == "depth source" ->
              "Chooses per-eye depth, a single depth layer for both eyes, or a diagnostic comparison."
          normalized == "depth metadata alignment" ->
              "Uses camera/depth metadata for alignment when enabled; manual offsets remain available for controlled tests."
          normalized.contains("depth x") ->
              "Adjusts horizontal depth sampling alignment or scale without moving the color image."
          normalized.contains("depth y") ->
              "Adjusts vertical depth sampling alignment or scale without moving the color image."
          normalized == "depth roll" ->
              "Rotates depth sampling around the image center to correct small stereo calibration roll."
          else ->
              "Explains and adjusts $label. Changes apply immediately and can be captured in a named profile."
        }
    return PrivateLayerControlHelpEntry(label, description)
  }
}
