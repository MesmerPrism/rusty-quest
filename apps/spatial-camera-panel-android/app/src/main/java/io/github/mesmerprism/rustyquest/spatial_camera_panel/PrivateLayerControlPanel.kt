package io.github.mesmerprism.rustyquest.spatial_camera_panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LayerPanelBackground = Color(0xFF141820)
private val LayerPanelSurface = Color(0xFF202634)
private val LayerPanelSurfaceAlt = Color(0xFF293142)
private val LayerPanelInk = Color(0xFFF4F7FA)
private val LayerPanelMuted = Color(0xFFAAB3C2)
private val LayerPanelAccent = Color(0xFF63D2FF)
private val LayerPanelWarm = Color(0xFFFFC857)
private val LayerPanelBorder = Color(0xFF3B465A)

@Composable
internal fun PrivateLayerControlPanel(
    layerOverride: Float,
    projectionPanelEnabled: Boolean,
    projectionScale: Float,
    projectionScaleRange: ClosedFloatingPointRange<Float>,
    depthLayerPolicy: Int,
    depthAlignment: PrivateLayerDepthAlignment,
    guideProcessing: PrivateLayerGuideProcessing,
    setLayerOverride: (Float, String) -> Float,
    setProjectionPanelEnabled: (Boolean, String) -> Boolean,
    updateProjectionScale: (Float, String) -> Float,
    updateDepthLayerPolicy: (Int, String) -> Int,
    updateDepthAlignment: (PrivateLayerDepthAlignment, String) -> PrivateLayerDepthAlignment,
    updateGuideProcessing:
        (PrivateLayerGuideProcessing, String) -> PrivateLayerGuideProcessing,
    closePanel: () -> Unit,
) {
  var localLayerOverride by remember(layerOverride) { mutableStateOf(layerOverride) }
  var localProjectionPanelEnabled by
      remember(projectionPanelEnabled) { mutableStateOf(projectionPanelEnabled) }
  var localProjectionScale by remember(projectionScale) { mutableStateOf(projectionScale) }
  var localDepthLayerPolicy by remember(depthLayerPolicy) { mutableStateOf(depthLayerPolicy) }
  var localDepthAlignment by remember(depthAlignment) { mutableStateOf(depthAlignment) }
  var localGuideProcessing by remember(guideProcessing) { mutableStateOf(guideProcessing) }
  val localZoneCompositor = PrivateLayerZoneCompositorPanelBridge.configuration
  Surface(
      modifier = Modifier.fillMaxSize(),
      color = LayerPanelBackground,
      contentColor = LayerPanelInk,
  ) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(LayerPanelBackground)
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
            modifier = Modifier.weight(1.0f).padding(end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          PanelGrabHandle()
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Layer Selection Panel",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Active: ${PrivateLayerControls.labelForOverride(localLayerOverride)}",
                style = MaterialTheme.typography.bodyMedium,
                color = LayerPanelMuted,
            )
          }
        }
        Button(
            onClick = closePanel,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = LayerPanelSurfaceAlt,
                    contentColor = LayerPanelInk,
                ),
        ) {
          Text("Close")
        }
      }

      PreviewBand()
      Section("Projection Panel Isolation") {
        Text(
            if (localProjectionPanelEnabled) {
              "Projection panel: On — video and custom projection are active."
            } else {
              "Projection panel: Off — carrier, video, and custom projection are disabled; passthrough remains on."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Button(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
              localProjectionPanelEnabled =
                  setProjectionPanelEnabled(
                      !localProjectionPanelEnabled,
                      "private-layer-control-panel-projection-toggle",
                  )
            },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor =
                        if (localProjectionPanelEnabled) LayerPanelWarm else LayerPanelAccent,
                    contentColor = Color(0xFF04111A),
                ),
        ) {
          Text(
              if (localProjectionPanelEnabled) {
                "Turn image projection panel off"
              } else {
                "Turn image projection panel on"
              }
          )
        }
      }
      Section("Active Rendering") {
        LayerButtonGrid(
            selectedLayerOverride = localLayerOverride,
            onSelect = { override ->
              localLayerOverride = setLayerOverride(override, "private-layer-control-panel")
            },
        )
      }

      Section("Projection Area") {
        Text(
            "Scale ${"%.2f".format(localProjectionScale)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Slider(
            value = localProjectionScale,
            onValueChange = { value ->
              localProjectionScale = updateProjectionScale(value, "private-layer-control-panel-scale")
            },
            valueRange = projectionScaleRange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OperatorButton("0.75x") {
            localProjectionScale =
                updateProjectionScale(0.75f, "private-layer-control-panel-scale-preset")
          }
          OperatorButton("1.00x") {
            localProjectionScale =
                updateProjectionScale(1.0f, "private-layer-control-panel-scale-preset")
          }
          OperatorButton("1.25x") {
            localProjectionScale =
                updateProjectionScale(1.25f, "private-layer-control-panel-scale-preset")
          }
        }
      }

      Section("Peripheral Stretch & Zone Blend") {
        Text(
            "The core follows the live projection scale, then the motion guard contracts it. Lens mode uses the native mirrored oval treatment; Linear preserves the earlier edge-ray mapping for A/B. Buffer mode fills only the released margin, while Full mode replaces the video layer.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Text(
            "Mode: ${PrivateLayerZoneCompositorControls.coverageToken(localZoneCompositor.coverageMode)} · style: ${PrivateLayerZoneCompositorControls.mappingToken(localZoneCompositor.stretchMapping)} · source: ${PrivateLayerZoneCompositorControls.sourceToken(localZoneCompositor.stretchSource)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Off", localZoneCompositor.coverageMode == PrivateLayerZoneCompositorControls.coverageOff) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.legacyOff,
                "private-layer-zone-preset-off",
            )
          }
          ChoiceButton("Lens buffer", localZoneCompositor == PrivateLayerZoneCompositorControls.nativeBuffer) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.nativeBuffer,
                "private-layer-zone-preset-native-buffer",
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Organic lens", localZoneCompositor == PrivateLayerZoneCompositorControls.organicBuffer) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.organicBuffer,
                "private-layer-zone-preset-organic-buffer",
            )
          }
          ChoiceButton("Full lens", localZoneCompositor == PrivateLayerZoneCompositorControls.fullStretch) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.fullStretch,
                "private-layer-zone-preset-full-stretch",
            )
          }
        }

        Text("Stretch style", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Native lens", localZoneCompositor.stretchMapping == PrivateLayerZoneCompositorControls.mappingMirroredLens) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.withMapping(
                    localZoneCompositor,
                    PrivateLayerZoneCompositorControls.mappingMirroredLens,
                ),
                "private-layer-zone-mapping-mirrored-lens",
            )
          }
          ChoiceButton("Linear", localZoneCompositor.stretchMapping == PrivateLayerZoneCompositorControls.mappingRectangularLinear) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                PrivateLayerZoneCompositorControls.withMapping(
                    localZoneCompositor,
                    PrivateLayerZoneCompositorControls.mappingRectangularLinear,
                ),
                "private-layer-zone-mapping-rectangular-linear",
            )
          }
        }

        Text("Stretch source", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Raw", localZoneCompositor.stretchSource == PrivateLayerZoneCompositorControls.sourceRaw) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(stretchSource = PrivateLayerZoneCompositorControls.sourceRaw),
                "private-layer-zone-source-raw",
            )
          }
          ChoiceButton("Processed", localZoneCompositor.stretchSource == PrivateLayerZoneCompositorControls.sourceProcessed) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(stretchSource = PrivateLayerZoneCompositorControls.sourceProcessed),
                "private-layer-zone-source-processed",
            )
          }
          ChoiceButton("Mix", localZoneCompositor.stretchSource == PrivateLayerZoneCompositorControls.sourceMixed) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(stretchSource = PrivateLayerZoneCompositorControls.sourceMixed),
                "private-layer-zone-source-mixed",
            )
          }
        }
        if (localZoneCompositor.stretchMapping == PrivateLayerZoneCompositorControls.mappingMirroredLens) {
          DepthSlider("Lens pullback", localZoneCompositor.edgeInsetUv, 0.0f..0.55f) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(edgeInsetUv = it),
                "private-layer-zone-lens-pullback",
            )
          }
          DepthSlider("Lens swirl", localZoneCompositor.maxInsetUv, 0.0f..1.50f) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(maxInsetUv = it),
                "private-layer-zone-lens-swirl",
            )
          }
          DepthSlider("Lens zoom", localZoneCompositor.stretchCurve, 0.0f..0.75f) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(stretchCurve = it),
                "private-layer-zone-lens-zoom",
            )
          }
        } else {
          DepthSlider("Edge inset", localZoneCompositor.edgeInsetUv, 0.0f..0.20f) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(edgeInsetUv = it),
                "private-layer-zone-edge-inset",
            )
          }
          DepthSlider("Maximum inset", localZoneCompositor.maxInsetUv, 0.015f..0.49f) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(maxInsetUv = it),
                "private-layer-zone-max-inset",
            )
          }
          DepthSlider("Stretch curve", localZoneCompositor.stretchCurve, 0.25f..6.0f) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(stretchCurve = it),
                "private-layer-zone-stretch-curve",
            )
          }
        }
        if (localZoneCompositor.stretchSource == PrivateLayerZoneCompositorControls.sourceMixed) {
          DepthSlider("Processed source mix", localZoneCompositor.processedMix, 0.0f..1.0f) {
            PrivateLayerZoneCompositorPanelBridge.submit(
                localZoneCompositor.copy(processedMix = it),
                "private-layer-zone-processed-mix",
            )
          }
        }

        Text("Inner seam · projection ↔ stretch", style = MaterialTheme.typography.titleSmall)
        ZoneSignalButtons(localZoneCompositor.innerSignal) { signal ->
          PrivateLayerZoneCompositorPanelBridge.submit(
              localZoneCompositor.copy(innerSignal = signal),
              "private-layer-zone-inner-signal",
          )
        }
        DepthSlider("Inner width", localZoneCompositor.innerWidthUv, 0.0f..0.25f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerWidthUv = it), "private-layer-zone-inner-width")
        }
        DepthSlider("Inner spatial curve", localZoneCompositor.innerCurve, 0.25f..6.0f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerCurve = it), "private-layer-zone-inner-curve")
        }
        ZoneChannelSliders("Inner", localZoneCompositor.innerThresholdR, localZoneCompositor.innerThresholdG, localZoneCompositor.innerThresholdB) { r, g, b ->
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerThresholdR = r, innerThresholdG = g, innerThresholdB = b), "private-layer-zone-inner-threshold")
        }
        DepthSlider("Inner softness", localZoneCompositor.innerSoftness, 0.001f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerSoftness = it), "private-layer-zone-inner-softness")
        }
        DepthSlider("Inner channel influence", localZoneCompositor.innerStrength, 0.0f..1.0f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerStrength = it), "private-layer-zone-inner-strength")
        }
        DepthSlider("Inner cycle amount", localZoneCompositor.innerCycleAmplitude, 0.0f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerCycleAmplitude = it), "private-layer-zone-inner-cycle-amount")
        }
        DepthSlider("Inner cycle speed", localZoneCompositor.innerCycleHz, 0.0f..1.0f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerCycleHz = it), "private-layer-zone-inner-cycle-speed")
        }
        DepthSlider("Inner motion response", localZoneCompositor.innerMotionGain, -0.5f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(innerMotionGain = it), "private-layer-zone-inner-motion")
        }

        Text("Outer seam · stretch ↔ video", style = MaterialTheme.typography.titleSmall)
        ZoneSignalButtons(localZoneCompositor.outerSignal) { signal ->
          PrivateLayerZoneCompositorPanelBridge.submit(
              localZoneCompositor.copy(outerSignal = signal),
              "private-layer-zone-outer-signal",
          )
        }
        DepthSlider("Outer width", localZoneCompositor.outerWidthUv, 0.0f..0.25f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerWidthUv = it), "private-layer-zone-outer-width")
        }
        DepthSlider("Outer spatial curve", localZoneCompositor.outerCurve, 0.25f..6.0f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerCurve = it), "private-layer-zone-outer-curve")
        }
        ZoneChannelSliders("Outer", localZoneCompositor.outerThresholdR, localZoneCompositor.outerThresholdG, localZoneCompositor.outerThresholdB) { r, g, b ->
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerThresholdR = r, outerThresholdG = g, outerThresholdB = b), "private-layer-zone-outer-threshold")
        }
        DepthSlider("Outer softness", localZoneCompositor.outerSoftness, 0.001f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerSoftness = it), "private-layer-zone-outer-softness")
        }
        DepthSlider("Outer channel influence", localZoneCompositor.outerStrength, 0.0f..1.0f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerStrength = it), "private-layer-zone-outer-strength")
        }
        DepthSlider("Outer cycle amount", localZoneCompositor.outerCycleAmplitude, 0.0f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerCycleAmplitude = it), "private-layer-zone-outer-cycle-amount")
        }
        DepthSlider("Outer cycle speed", localZoneCompositor.outerCycleHz, 0.0f..1.0f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerCycleHz = it), "private-layer-zone-outer-cycle-speed")
        }
        DepthSlider("Outer motion response", localZoneCompositor.outerMotionGain, -0.5f..0.5f) {
          PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(outerMotionGain = it), "private-layer-zone-outer-motion")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton("Normal", localZoneCompositor.debugMode == PrivateLayerZoneCompositorControls.debugOff) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(debugMode = PrivateLayerZoneCompositorControls.debugOff), "private-layer-zone-debug-off")
          }
          ChoiceButton("Regions", localZoneCompositor.debugMode == PrivateLayerZoneCompositorControls.debugRegions) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(debugMode = PrivateLayerZoneCompositorControls.debugRegions), "private-layer-zone-debug-regions")
          }
          ChoiceButton("Sample UV", localZoneCompositor.debugMode == PrivateLayerZoneCompositorControls.debugSampleUv) {
            PrivateLayerZoneCompositorPanelBridge.submit(localZoneCompositor.copy(debugMode = PrivateLayerZoneCompositorControls.debugSampleUv), "private-layer-zone-debug-sample-uv")
          }
        }
      }

      Section("Camera Sampling A/B") {
        Text(
            "Thin-line AA applies a modest footprint-aware five-tap tent filter at camera ingress. Linear preserves the previous single bilinear sample for direct comparison.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Text(
            "Active: ${PrivateLayerControls.cameraSamplingToken(localGuideProcessing.cameraSampling)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Thin-line AA",
              selected =
                  localGuideProcessing.cameraSampling ==
                      PrivateLayerControls.cameraSamplingThinLineTent5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        cameraSampling = PrivateLayerControls.cameraSamplingThinLineTent5,
                    ),
                    "private-layer-control-panel-camera-sampling-thin-line-aa",
                )
          }
          ChoiceButton(
              label = "Linear",
              selected =
                  localGuideProcessing.cameraSampling == PrivateLayerControls.cameraSamplingLinear,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        cameraSampling = PrivateLayerControls.cameraSamplingLinear,
                    ),
                    "private-layer-control-panel-camera-sampling-linear",
                )
          }
        }
      }

      Section("Guide Processing A/B") {
        Text(
            "Native parity is the verified target: 5-tap box pre/post blur with luma extracted before pre-blur. Gaussian and RGB remain live diagnostics.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Text(
            "Active: ${PrivateLayerControls.guideProcessingPresetToken(localGuideProcessing)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Native target",
              selected =
                  PrivateLayerControls.guideProcessingPresetToken(localGuideProcessing) ==
                      "native-parity",
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    PrivateLayerControls.nativeParityGuideProcessing.copy(
                        cameraSampling = localGuideProcessing.cameraSampling,
                    ),
                    "private-layer-control-panel-guide-native-parity",
                )
          }
          ChoiceButton(
              label = "Gaussian + RGB",
              selected =
                  PrivateLayerControls.guideProcessingPresetToken(localGuideProcessing) ==
                      "gaussian-rgb-diagnostic",
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    PrivateLayerControls.gaussianRgbGuideProcessing.copy(
                        cameraSampling = localGuideProcessing.cameraSampling,
                    ),
                    "private-layer-control-panel-guide-gaussian-rgb",
                )
          }
        }
        Text("Pre-blur kernel", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Native box 5",
              selected =
                  localGuideProcessing.preblurKernel == PrivateLayerControls.guideKernelNativeBox5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        preblurKernel = PrivateLayerControls.guideKernelNativeBox5,
                    ),
                    "private-layer-control-panel-guide-preblur-box5",
                )
          }
          ChoiceButton(
              label = "Gaussian 5",
              selected =
                  localGuideProcessing.preblurKernel == PrivateLayerControls.guideKernelGaussian5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        preblurKernel = PrivateLayerControls.guideKernelGaussian5,
                    ),
                    "private-layer-control-panel-guide-preblur-gaussian5",
                )
          }
        }
        Text("Pre-blur input", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Native luma",
              selected = localGuideProcessing.preblurInput == PrivateLayerControls.guideInputLuma,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(preblurInput = PrivateLayerControls.guideInputLuma),
                    "private-layer-control-panel-guide-input-luma",
                )
          }
          ChoiceButton(
              label = "Preserve RGB",
              selected =
                  localGuideProcessing.preblurInput == PrivateLayerControls.guideInputPreserveRgb,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        preblurInput = PrivateLayerControls.guideInputPreserveRgb,
                    ),
                    "private-layer-control-panel-guide-input-rgb",
                )
          }
        }
        Text("Post-blur kernel", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
          ChoiceButton(
              label = "Native box 5",
              selected =
                  localGuideProcessing.postblurKernel == PrivateLayerControls.guideKernelNativeBox5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        postblurKernel = PrivateLayerControls.guideKernelNativeBox5,
                    ),
                    "private-layer-control-panel-guide-postblur-box5",
                )
          }
          ChoiceButton(
              label = "Gaussian 5",
              selected =
                  localGuideProcessing.postblurKernel == PrivateLayerControls.guideKernelGaussian5,
          ) {
            localGuideProcessing =
                updateGuideProcessing(
                    localGuideProcessing.copy(
                        postblurKernel = PrivateLayerControls.guideKernelGaussian5,
                    ),
                    "private-layer-control-panel-guide-postblur-gaussian5",
                )
          }
        }
      }

      Section("Depth Source") {
        Text(
            "Active: ${PrivateLayerControls.labelForDepthLayerPolicy(localDepthLayerPolicy)}",
            style = MaterialTheme.typography.bodyMedium,
            color = LayerPanelMuted,
        )
        Text(
            "Meta supplies left/right depth layers. Stereo selects the matching layer for each eye; mono and compare remain diagnostics.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        DepthSourceButtonGrid(
            selectedPolicy = localDepthLayerPolicy,
            onSelect = { policy ->
              localDepthLayerPolicy =
                  updateDepthLayerPolicy(policy, "private-layer-control-panel-depth-source")
            },
        )
      }

      Section("Depth Alignment") {
        Text(
            "Auto uses Meta's per-eye FOV and pose first. These controls apply residual fine tuning for camera crop and headset-specific alignment.",
            style = MaterialTheme.typography.bodySmall,
            color = LayerPanelMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OperatorButton(
              if (localDepthAlignment.metadataAutoAlign) "Auto metadata: On" else "Auto metadata: Off"
          ) {
            localDepthAlignment =
                updateDepthAlignment(
                    localDepthAlignment.copy(
                        metadataAutoAlign = !localDepthAlignment.metadataAutoAlign,
                    ),
                    "private-layer-control-panel-depth-metadata-auto",
                )
          }
          OperatorButton("Reset fine tune") {
            localDepthAlignment =
                updateDepthAlignment(
                    PrivateLayerDepthAlignment(
                        metadataAutoAlign = localDepthAlignment.metadataAutoAlign,
                    ),
                    "private-layer-control-panel-depth-fine-tune-reset",
                )
          }
        }
        DepthSlider("Left depth X", localDepthAlignment.leftX, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(leftX = value),
                  "private-layer-control-panel-depth-left-x",
              )
        }
        DepthSlider("Left depth Y", localDepthAlignment.leftY, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(leftY = value),
                  "private-layer-control-panel-depth-left-y",
              )
        }
        DepthSlider("Right depth X", localDepthAlignment.rightX, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(rightX = value),
                  "private-layer-control-panel-depth-right-x",
              )
        }
        DepthSlider("Right depth Y", localDepthAlignment.rightY, -0.25f..0.25f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(rightY = value),
                  "private-layer-control-panel-depth-right-y",
              )
        }
        DepthSlider("Depth X scale", localDepthAlignment.sampleScale, 0.25f..3.0f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(sampleScale = value),
                  "private-layer-control-panel-depth-sample-scale-x",
              )
        }
        DepthSlider("Depth Y scale", localDepthAlignment.sampleScaleY, 0.25f..3.0f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(sampleScaleY = value),
                  "private-layer-control-panel-depth-sample-scale-y",
              )
        }
        DepthSlider("Depth roll", localDepthAlignment.rollDegrees, -15.0f..15.0f) { value ->
          localDepthAlignment =
              updateDepthAlignment(
                  localDepthAlignment.copy(rollDegrees = value),
                  "private-layer-control-panel-depth-roll",
              )
        }
      }
    }
  }
}

@Composable
private fun PanelGrabHandle() {
  Column(
      modifier =
          Modifier
              .width(30.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(LayerPanelSurfaceAlt)
              .border(1.dp, LayerPanelBorder, RoundedCornerShape(8.dp))
              .padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    repeat(3) {
      Box(
          modifier =
              Modifier
                  .fillMaxWidth()
                  .height(2.dp)
                  .background(LayerPanelAccent, RoundedCornerShape(1.dp))
      )
    }
  }
}

@Composable
private fun PreviewBand() {
  Box(
      modifier =
          Modifier
              .fillMaxWidth()
              .height(62.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(
                  Brush.horizontalGradient(
                      listOf(
                          Color(0xFF111827),
                          Color(0xFF2B8FD8),
                          LayerPanelWarm,
                          Color(0xFFD84F9A),
                          Color(0xFF111827),
                      )
                  )
              )
              .border(1.dp, LayerPanelBorder, RoundedCornerShape(8.dp)),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        "private layer selector",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(
      modifier =
          Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(LayerPanelSurface)
              .border(1.dp, LayerPanelBorder, RoundedCornerShape(8.dp))
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    HorizontalDivider(color = LayerPanelBorder)
    content()
  }
}

@Composable
private fun LayerButtonGrid(
    selectedLayerOverride: Float,
    onSelect: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    LayerButtonRow(
        choices =
            listOf(
                PrivateLayerChoice(-1, "Cycle", "cycle"),
                PrivateLayerControls.layers[0],
            ),
        selectedLayerOverride = selectedLayerOverride,
        onSelect = onSelect,
    )
    PrivateLayerControls.layers.drop(1).chunked(2).forEach { row ->
      LayerButtonRow(row, selectedLayerOverride, onSelect)
    }
  }
}

@Composable
private fun LayerButtonRow(
    choices: List<PrivateLayerChoice>,
    selectedLayerOverride: Float,
    onSelect: (Float) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    choices.forEach { choice ->
      val override =
          if (choice.index < 0) PrivateLayerControls.cycleOverride else choice.index.toFloat()
      val selected =
          if (override < 0.0f) {
            selectedLayerOverride < 0.0f
          } else {
            selectedLayerOverride.toInt() == choice.index
          }
      Button(
          modifier = Modifier.weight(1.0f).height(52.dp),
          onClick = { onSelect(override) },
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = if (selected) LayerPanelAccent else LayerPanelSurfaceAlt,
                  contentColor = if (selected) Color(0xFF04111A) else LayerPanelInk,
              ),
      ) {
        Text(choice.title)
      }
    }
    if (choices.size == 1) {
      Spacer(Modifier.weight(1.0f))
    }
  }
}

@Composable
private fun DepthSourceButtonGrid(
    selectedPolicy: Int,
    onSelect: (Int) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    PrivateLayerControls.depthSourcePolicies.chunked(2).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        row.forEach { choice ->
          val selected =
              PrivateLayerControls.normalizeDepthLayerPolicy(selectedPolicy) == choice.code
          Button(
              modifier = Modifier.weight(1.0f).height(52.dp),
              onClick = { onSelect(choice.code) },
              colors =
                  ButtonDefaults.buttonColors(
                      containerColor = if (selected) LayerPanelAccent else LayerPanelSurfaceAlt,
                      contentColor = if (selected) Color(0xFF04111A) else LayerPanelInk,
                  ),
          ) {
            Text(choice.title)
          }
        }
      }
    }
  }
}

@Composable
private fun ZoneSignalButtons(selectedSignal: Int, onSelect: (Int) -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    ChoiceButton("Flat", selectedSignal == PrivateLayerZoneCompositorControls.signalFlat) {
      onSelect(PrivateLayerZoneCompositorControls.signalFlat)
    }
    ChoiceButton("RGB", selectedSignal == PrivateLayerZoneCompositorControls.signalRgb) {
      onSelect(PrivateLayerZoneCompositorControls.signalRgb)
    }
    ChoiceButton("Luma", selectedSignal == PrivateLayerZoneCompositorControls.signalLuma) {
      onSelect(PrivateLayerZoneCompositorControls.signalLuma)
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    ChoiceButton("Chroma", selectedSignal == PrivateLayerZoneCompositorControls.signalChroma) {
      onSelect(PrivateLayerZoneCompositorControls.signalChroma)
    }
    ChoiceButton("Difference", selectedSignal == PrivateLayerZoneCompositorControls.signalDifference) {
      onSelect(PrivateLayerZoneCompositorControls.signalDifference)
    }
  }
}

@Composable
private fun ZoneChannelSliders(
    prefix: String,
    red: Float,
    green: Float,
    blue: Float,
    onChange: (Float, Float, Float) -> Unit,
) {
  DepthSlider("$prefix red threshold", red, 0.0f..1.0f) { onChange(it, green, blue) }
  DepthSlider("$prefix green threshold", green, 0.0f..1.0f) { onChange(red, it, blue) }
  DepthSlider("$prefix blue threshold", blue, 0.0f..1.0f) { onChange(red, green, it) }
}

@Composable
private fun DepthSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
      Text("%.3f".format(value), style = MaterialTheme.typography.bodyMedium, color = LayerPanelMuted)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range)
  }
}

@Composable
private fun OperatorButton(label: String, onClick: () -> Unit) {
  Button(
      onClick = onClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = LayerPanelSurfaceAlt,
              contentColor = LayerPanelInk,
          ),
  ) {
    Text(label)
  }
}

@Composable
private fun RowScope.ChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
  Button(
      modifier = Modifier.weight(1.0f).height(52.dp),
      onClick = onClick,
      colors =
          ButtonDefaults.buttonColors(
              containerColor = if (selected) LayerPanelAccent else LayerPanelSurfaceAlt,
              contentColor = if (selected) Color(0xFF04111A) else LayerPanelInk,
          ),
  ) {
    Text(label)
  }
}
