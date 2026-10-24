//! One pure phase-step seam. The application retains its phase and frame clock.
use crate::feedback::FeedbackHost;

// Reversible wire encoding also makes the unchanged loader's input=5 probe
// represent a normal delta, independent of a render thread's subnormal mode.
pub const DELTA_WIRE_XOR: u32 = 0x3e80_0000;
pub const PROBE_A: i64 = 0x3e80_0005;
pub const PROBE_B: i64 = 0x3f00_0005;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StepSource { Module, NoHost, InvalidInput, HostError, InvalidOutput }

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct StepOutcome { pub phase: f32, pub source: StepSource }

pub fn step_or_resident(host: Option<&mut FeedbackHost>, phase: f32, delta: f32) -> f32 {
    step_outcome(host, phase, delta).phase
}

/// Report semantic adoption separately from the generic host's ABI completion.
pub fn step_outcome(host: Option<&mut FeedbackHost>, phase: f32, delta: f32) -> StepOutcome {
    let fallback = (phase + delta).rem_euclid(1.0);
    let builtin = |source| StepOutcome { phase: fallback, source };
    if !phase.is_finite() || !(0.0..1.0).contains(&phase)
        || !delta.is_finite() || delta < 0.0 { return builtin(StepSource::InvalidInput); }
    let Some(host) = host else { return builtin(StepSource::NoHost); };
    let packet = ((u64::from(phase.to_bits()) << 32)
        | u64::from(delta.to_bits() ^ DELTA_WIRE_XOR)) as i64;
    let Ok(result) = host.compute(packet) else { return builtin(StepSource::HostError); };
    let Ok(bits) = u32::try_from(result.value) else { return builtin(StepSource::InvalidOutput); };
    let next = f32::from_bits(bits);
    if next.is_finite() && (0.0..1.0).contains(&next) {
        StepOutcome { phase: next, source: StepSource::Module }
    } else { builtin(StepSource::InvalidOutput) }
}
