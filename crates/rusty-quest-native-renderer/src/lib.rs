//! Contracts for Quest-native HWB Vulkan renderer plans.
//!
//! This crate is intentionally platform-contract only. It describes the public
//! AGPL-owned blur/stretch/SDF renderer route, private layer extension
//! boundaries, and timing evidence expected from a native Quest implementation
//! without linking Android, OpenXR, or Vulkan SDKs.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

/// Native renderer plan schema id.
pub const NATIVE_RENDERER_PLAN_SCHEMA: &str = "rusty.quest.native_renderer_plan.v1";

/// Native renderer timing scorecard schema id.
pub const NATIVE_RENDERER_TIMING_SCORECARD_SCHEMA: &str =
    "rusty.quest.native_renderer_timing_scorecard.v1";

/// A Quest-native renderer plan.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NativeRendererPlan {
    /// Schema id.
    pub schema: String,
    /// Stable plan id.
    pub plan_id: String,
    /// Target runtime.
    pub target_runtime: String,
    /// Public licensing and extension policy.
    pub license_policy: LicensePolicy,
    /// Camera source and import path.
    pub camera_source: CameraSourcePlan,
    /// Ordered public/private layer stack.
    pub layer_stack: Vec<LayerPlan>,
    /// Renderer passes.
    pub render_graph: Vec<RenderPassPlan>,
    /// Cost model for acceptance gates.
    pub cost_model: CostModel,
    /// Observability requirements.
    pub observability: ObservabilityPlan,
}

/// Public/private license and extension policy.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LicensePolicy {
    /// License for the public core route.
    pub public_core_license: String,
    /// Whether private extension slots are supported.
    pub private_extension_slots_supported: bool,
    /// Whether public plans may include private implementation payloads.
    pub private_extension_payloads_allowed_in_public_plan: bool,
}

/// Camera source shape for the native renderer.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CameraSourcePlan {
    /// Camera source kind.
    pub source_kind: CameraSourceKind,
    /// Android Camera2 ids used for left/right outside cameras.
    pub camera_ids: StereoCameraIds,
    /// Hardware buffer import policy.
    pub hardware_buffer_import: HardwareBufferImportPlan,
}

/// Camera source kind.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum CameraSourceKind {
    /// Android Camera2 frames as AHardwareBuffer external images.
    Camera2HardwareBuffer,
}

/// Stereo camera ids.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StereoCameraIds {
    /// Left display-eye outside camera id.
    pub left: String,
    /// Right display-eye outside camera id.
    pub right: String,
}

/// HWB import and descriptor plan.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HardwareBufferImportPlan {
    /// Import path.
    pub import_path: String,
    /// Descriptor shape expected by the renderer.
    pub descriptor_shape: String,
    /// Sampler/conversion binding policy.
    pub sampler_binding: String,
    /// Whether color conformance is required before visual acceptance.
    pub color_conformance_required: bool,
}

/// One layer in the native renderer stack.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LayerPlan {
    /// Stable layer id.
    pub layer_id: String,
    /// Layer kind.
    pub kind: LayerKind,
    /// Layer owner.
    pub owner: LayerOwner,
    /// Public ABI or descriptor id.
    pub abi_id: String,
    /// Whether this layer may affect final projection color.
    pub affects_color: bool,
}

/// Layer kind.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LayerKind {
    /// Camera projection/composite layer.
    CameraProjection,
    /// Public offscreen guide blur layer.
    BlurGuide,
    /// Public final-projection target-edge stretch/blend border layer.
    PeripheralStretchBorder,
    /// Public SDF/hand-mesh input layer.
    SdfFieldInput,
    /// Public timing/diagnostic layer.
    TimingDiagnostics,
    /// Private extension slot with public ABI only.
    PrivateExtensionSlot,
}

/// Layer owner class.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LayerOwner {
    /// Public Rusty Quest renderer code.
    PublicQuest,
    /// Public Rusty Matter data/SDF contract.
    PublicMatter,
    /// Public Rusty Optics projection/effect contract.
    PublicOptics,
    /// Private downstream extension implementation.
    PrivateExtension,
}

/// One pass in the renderer graph.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RenderPassPlan {
    /// Stable pass id.
    pub pass_id: String,
    /// Pass kind.
    pub kind: RenderPassKind,
    /// Input resource ids.
    pub inputs: Vec<String>,
    /// Output resource ids.
    pub outputs: Vec<String>,
    /// Expected cost budget in milliseconds.
    pub budget_ms: f32,
}

/// Renderer pass kind.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum RenderPassKind {
    /// Import/acquire external hardware buffers.
    AcquireHardwareBuffer,
    /// Low-resolution horizontal blur directly from external HWB.
    HwbHorizontalGuideBlur,
    /// Low-resolution vertical blur from guide texture.
    GuideVerticalBlur,
    /// Prepare SDF or hand-mesh field inputs.
    SdfFieldPrepare,
    /// Composite final custom projection.
    ProjectionComposite,
    /// Emit timing and cost markers.
    TimingReadback,
    /// Private extension ABI hook.
    PrivateExtensionHook,
}

/// Cost model and render-budget policy.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CostModel {
    /// Target display refresh rate.
    pub target_refresh_hz: u32,
    /// Maximum external camera samples in the final projection pass.
    pub external_hwb_samples_per_final_fragment: u32,
    /// Maximum guide texture samples in the final projection pass.
    pub guide_texture_samples_per_final_fragment: u32,
    /// Expected offscreen pass count per eye.
    pub offscreen_passes_per_eye: u32,
    /// Whether timing evidence must separate acquire, import, graph, and submit.
    pub stage_timing_required: bool,
}

/// Observability plan.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ObservabilityPlan {
    /// Required marker prefix.
    pub marker_prefix: String,
    /// Required stage timings.
    pub stage_timings: Vec<StageTimingBudget>,
    /// Required counters.
    pub counters: Vec<String>,
}

/// Timing budget for one stage.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StageTimingBudget {
    /// Stage id.
    pub stage_id: String,
    /// Stage kind.
    pub kind: StageTimingKind,
    /// Budget in milliseconds.
    pub budget_ms: f32,
}

/// Stage timing kind.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum StageTimingKind {
    /// Camera acquire.
    CameraAcquire,
    /// Vulkan external image import/cache lookup.
    HwbImport,
    /// Offscreen blur/guide graph.
    GuideGraph,
    /// SDF field update.
    SdfUpdate,
    /// Final projection/composite.
    ProjectionComposite,
    /// OpenXR submit/present.
    XrSubmit,
}

/// Timing scorecard emitted by a native Quest renderer.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NativeRendererTimingScorecard {
    /// Schema id.
    pub schema: String,
    /// Source renderer plan id.
    pub plan_id: String,
    /// Whether timing is acceptable.
    pub timing_ready: bool,
    /// Samples by stage.
    pub stages: Vec<StageTimingSample>,
    /// Counters by id.
    pub counters: BTreeMap<String, u64>,
}

/// Timing sample for one stage.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StageTimingSample {
    /// Stage id.
    pub stage_id: String,
    /// Observed milliseconds.
    pub observed_ms: f32,
    /// Budget milliseconds.
    pub budget_ms: f32,
}

/// Validation failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ValidationError {
    /// Human-readable message.
    pub message: String,
}

impl ValidationError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl std::fmt::Display for ValidationError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for ValidationError {}

/// Validate a native renderer plan.
pub fn validate_native_renderer_plan(
    plan: &NativeRendererPlan,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    if plan.schema != NATIVE_RENDERER_PLAN_SCHEMA {
        errors.push(ValidationError::new(format!(
            "unsupported native renderer schema {}",
            plan.schema
        )));
    }
    if plan.plan_id.trim().is_empty() {
        errors.push(ValidationError::new("plan_id must not be empty"));
    }
    if plan.target_runtime != "quest-native-openxr-vulkan" {
        errors.push(ValidationError::new(format!(
            "unsupported target_runtime {}",
            plan.target_runtime
        )));
    }
    validate_license_policy(&plan.license_policy, &mut errors);
    validate_camera_source(&plan.camera_source, &mut errors);
    validate_layers(&plan.layer_stack, &mut errors);
    validate_render_graph(&plan.render_graph, &mut errors);
    validate_cost_model(&plan.cost_model, &mut errors);
    validate_observability(&plan.observability, &mut errors);

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_license_policy(policy: &LicensePolicy, errors: &mut Vec<ValidationError>) {
    if policy.public_core_license != "AGPL-3.0-or-later" {
        errors.push(ValidationError::new(
            "public_core_license must be AGPL-3.0-or-later",
        ));
    }
    if policy.private_extension_payloads_allowed_in_public_plan {
        errors.push(ValidationError::new(
            "public plans must not contain private extension payloads",
        ));
    }
}

fn validate_camera_source(source: &CameraSourcePlan, errors: &mut Vec<ValidationError>) {
    if source.source_kind != CameraSourceKind::Camera2HardwareBuffer {
        errors.push(ValidationError::new(
            "native renderer camera source must be camera2-hardware-buffer",
        ));
    }
    if source.camera_ids.left.trim().is_empty() || source.camera_ids.right.trim().is_empty() {
        errors.push(ValidationError::new(
            "stereo camera ids must declare left and right ids",
        ));
    }
    if source.hardware_buffer_import.import_path != "camera2-ahardwarebuffer-vulkan-external" {
        errors.push(ValidationError::new(
            "hardware_buffer_import.import_path must be camera2-ahardwarebuffer-vulkan-external",
        ));
    }
    if source.hardware_buffer_import.descriptor_shape
        != "combined-immutable-sampler-ycbcr-conversion"
    {
        errors.push(ValidationError::new(
            "descriptor_shape must declare combined immutable sampler YCbCr conversion",
        ));
    }
    if !source.hardware_buffer_import.color_conformance_required {
        errors.push(ValidationError::new(
            "color_conformance_required must stay true until visual color is accepted",
        ));
    }
}

fn validate_layers(layers: &[LayerPlan], errors: &mut Vec<ValidationError>) {
    let mut ids = BTreeSet::new();
    let mut has_blur = false;
    let mut has_projection = false;
    let mut has_timing = false;
    for layer in layers {
        validate_stable_id("layer_id", &layer.layer_id, errors);
        validate_stable_id("abi_id", &layer.abi_id, errors);
        if !ids.insert(layer.layer_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "duplicate layer_id {}",
                layer.layer_id
            )));
        }
        match layer.kind {
            LayerKind::BlurGuide => {
                has_blur = true;
                if layer.owner != LayerOwner::PublicOptics {
                    errors.push(ValidationError::new(
                        "blur-guide layer must be public-optics owned",
                    ));
                }
            }
            LayerKind::PeripheralStretchBorder => {
                if layer.owner != LayerOwner::PublicOptics {
                    errors.push(ValidationError::new(
                        "peripheral-stretch-border layer must be public-optics owned",
                    ));
                }
            }
            LayerKind::CameraProjection => {
                has_projection = true;
                if layer.owner != LayerOwner::PublicOptics {
                    errors.push(ValidationError::new(
                        "camera-projection layer must be public-optics owned",
                    ));
                }
            }
            LayerKind::SdfFieldInput => {
                if layer.owner != LayerOwner::PublicMatter {
                    errors.push(ValidationError::new(
                        "sdf-field-input layer must be public-matter owned",
                    ));
                }
            }
            LayerKind::TimingDiagnostics => {
                has_timing = true;
                if layer.owner != LayerOwner::PublicQuest {
                    errors.push(ValidationError::new(
                        "timing-diagnostics layer must be public-quest owned",
                    ));
                }
            }
            LayerKind::PrivateExtensionSlot => {
                if layer.owner != LayerOwner::PrivateExtension {
                    errors.push(ValidationError::new(
                        "private-extension-slot layer must be private-extension owned",
                    ));
                }
                if contains_private_payload_hint(&layer.abi_id) {
                    errors.push(ValidationError::new(
                        "private extension layer abi_id must not expose paths or implementation payloads",
                    ));
                }
            }
        }
    }
    if !has_blur {
        errors.push(ValidationError::new("layer_stack must include blur-guide"));
    }
    if !has_projection {
        errors.push(ValidationError::new(
            "layer_stack must include camera-projection",
        ));
    }
    if !has_timing {
        errors.push(ValidationError::new(
            "layer_stack must include timing-diagnostics",
        ));
    }
}

fn validate_render_graph(passes: &[RenderPassPlan], errors: &mut Vec<ValidationError>) {
    let mut ids = BTreeSet::new();
    let mut kinds = BTreeSet::new();
    for pass in passes {
        validate_stable_id("pass_id", &pass.pass_id, errors);
        if !ids.insert(pass.pass_id.as_str()) {
            errors.push(ValidationError::new(format!(
                "duplicate pass_id {}",
                pass.pass_id
            )));
        }
        kinds.insert(pass.kind);
        if pass.budget_ms <= 0.0 {
            errors.push(ValidationError::new(format!(
                "pass {} budget_ms must be positive",
                pass.pass_id
            )));
        }
        for input in &pass.inputs {
            validate_stable_id("pass input", input, errors);
        }
        for output in &pass.outputs {
            validate_stable_id("pass output", output, errors);
        }
        if pass.kind == RenderPassKind::PrivateExtensionHook
            && pass
                .outputs
                .iter()
                .any(|output| contains_private_payload_hint(output))
        {
            errors.push(ValidationError::new(
                "private extension pass outputs must be public ABI resource ids only",
            ));
        }
    }
    for required in [
        RenderPassKind::AcquireHardwareBuffer,
        RenderPassKind::HwbHorizontalGuideBlur,
        RenderPassKind::GuideVerticalBlur,
        RenderPassKind::ProjectionComposite,
        RenderPassKind::TimingReadback,
    ] {
        if !kinds.contains(&required) {
            errors.push(ValidationError::new(format!(
                "render_graph missing required pass kind {required:?}"
            )));
        }
    }
}

fn validate_cost_model(cost: &CostModel, errors: &mut Vec<ValidationError>) {
    if cost.target_refresh_hz != 72 {
        errors.push(ValidationError::new(
            "initial Quest-native target_refresh_hz must be 72",
        ));
    }
    if cost.external_hwb_samples_per_final_fragment != 0 {
        errors.push(ValidationError::new(
            "final projection must not sample external HWB camera textures",
        ));
    }
    if cost.guide_texture_samples_per_final_fragment != 1 {
        errors.push(ValidationError::new(
            "final projection should sample one guide texture per fragment",
        ));
    }
    if cost.offscreen_passes_per_eye > 2 {
        errors.push(ValidationError::new(
            "guide blur graph should stay at or below two offscreen passes per eye",
        ));
    }
    if !cost.stage_timing_required {
        errors.push(ValidationError::new("stage_timing_required must be true"));
    }
}

fn validate_observability(plan: &ObservabilityPlan, errors: &mut Vec<ValidationError>) {
    if plan.marker_prefix != "RUSTY_QUEST_NATIVE_RENDERER" {
        errors.push(ValidationError::new(
            "marker_prefix must be RUSTY_QUEST_NATIVE_RENDERER",
        ));
    }
    let stage_kinds: BTreeSet<StageTimingKind> =
        plan.stage_timings.iter().map(|stage| stage.kind).collect();
    for required in [
        StageTimingKind::CameraAcquire,
        StageTimingKind::HwbImport,
        StageTimingKind::GuideGraph,
        StageTimingKind::ProjectionComposite,
        StageTimingKind::XrSubmit,
    ] {
        if !stage_kinds.contains(&required) {
            errors.push(ValidationError::new(format!(
                "observability missing stage timing {required:?}"
            )));
        }
    }
    for stage in &plan.stage_timings {
        validate_stable_id("stage_id", &stage.stage_id, errors);
        if stage.budget_ms <= 0.0 {
            errors.push(ValidationError::new(format!(
                "stage {} budget_ms must be positive",
                stage.stage_id
            )));
        }
    }
    for counter in &plan.counters {
        validate_stable_id("counter", counter, errors);
    }
}

/// Validate a native renderer timing scorecard against a plan.
pub fn validate_timing_scorecard(
    plan: &NativeRendererPlan,
    scorecard: &NativeRendererTimingScorecard,
) -> Result<(), Vec<ValidationError>> {
    let mut errors = Vec::new();
    if scorecard.schema != NATIVE_RENDERER_TIMING_SCORECARD_SCHEMA {
        errors.push(ValidationError::new(format!(
            "unsupported timing scorecard schema {}",
            scorecard.schema
        )));
    }
    if scorecard.plan_id != plan.plan_id {
        errors.push(ValidationError::new(
            "scorecard plan_id does not match plan",
        ));
    }
    let stage_budgets: BTreeMap<&str, f32> = plan
        .observability
        .stage_timings
        .iter()
        .map(|stage| (stage.stage_id.as_str(), stage.budget_ms))
        .collect();
    for sample in &scorecard.stages {
        match stage_budgets.get(sample.stage_id.as_str()) {
            Some(expected_budget) => {
                if (sample.budget_ms - expected_budget).abs() > 0.0001 {
                    errors.push(ValidationError::new(format!(
                        "stage {} budget_ms does not match plan",
                        sample.stage_id
                    )));
                }
            }
            None => errors.push(ValidationError::new(format!(
                "scorecard contains unknown stage {}",
                sample.stage_id
            ))),
        }
        if sample.observed_ms > sample.budget_ms {
            errors.push(ValidationError::new(format!(
                "stage {} exceeds budget",
                sample.stage_id
            )));
        }
    }
    for counter in &plan.observability.counters {
        if !scorecard.counters.contains_key(counter) {
            errors.push(ValidationError::new(format!(
                "scorecard missing counter {}",
                counter
            )));
        }
    }
    if !scorecard.timing_ready {
        errors.push(ValidationError::new(
            "scorecard timing_ready must be true for acceptance",
        ));
    }
    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}

fn validate_stable_id(label: &str, value: &str, errors: &mut Vec<ValidationError>) {
    if value.trim().is_empty() {
        errors.push(ValidationError::new(format!("{label} must not be empty")));
        return;
    }
    if contains_private_payload_hint(value) {
        errors.push(ValidationError::new(format!(
            "{label} must not contain paths or implementation payload hints: {value}"
        )));
    }
    if value.contains(' ') {
        errors.push(ValidationError::new(format!(
            "{label} must not contain spaces: {value}"
        )));
    }
}

fn contains_private_payload_hint(value: &str) -> bool {
    value.contains(":\\")
        || value.contains(":/")
        || value.contains("../")
        || value.contains("..\\")
        || value.contains(".dll")
        || value.contains(".so")
        || value.contains(".apk")
}

#[cfg(test)]
mod tests {
    use super::{
        validate_native_renderer_plan, validate_timing_scorecard, LayerKind, NativeRendererPlan,
        NativeRendererTimingScorecard,
    };

    fn public_plan() -> NativeRendererPlan {
        serde_json::from_str(include_str!(
            "../../../fixtures/native-renderer/native-hwb-blur-sdf-public.plan.json"
        ))
        .expect("valid native renderer plan JSON")
    }

    #[test]
    fn native_hwb_blur_sdf_public_plan_validates() {
        let plan = public_plan();
        validate_native_renderer_plan(&plan).expect("plan validates");
        assert!(plan
            .layer_stack
            .iter()
            .any(|layer| layer.kind == LayerKind::PeripheralStretchBorder));
    }

    #[test]
    fn native_timing_scorecard_validates_against_plan() {
        let plan = public_plan();
        let scorecard: NativeRendererTimingScorecard = serde_json::from_str(include_str!(
            "../../../fixtures/native-renderer/native-hwb-blur-sdf-public.timing-scorecard.json"
        ))
        .expect("valid timing scorecard JSON");
        validate_timing_scorecard(&plan, &scorecard).expect("scorecard validates");
    }

    #[test]
    fn private_extension_payload_leak_is_rejected() {
        let damaged: NativeRendererPlan = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-private-extension-path.plan.json"
        ))
        .expect("damaged native renderer plan JSON");
        let errors =
            validate_native_renderer_plan(&damaged).expect_err("private path must be rejected");
        assert!(errors
            .iter()
            .any(|error| error.message.contains("implementation payload")));
    }

    #[test]
    fn final_pass_external_hwb_sampling_is_rejected() {
        let damaged: NativeRendererPlan = serde_json::from_str(include_str!(
            "../../../fixtures/damaged/native-renderer-final-pass-hwb-samples.plan.json"
        ))
        .expect("damaged native renderer plan JSON");
        let errors = validate_native_renderer_plan(&damaged)
            .expect_err("final-pass external HWB sampling must be rejected");
        assert!(errors.iter().any(|error| error
            .message
            .contains("final projection must not sample external")));
    }
}
