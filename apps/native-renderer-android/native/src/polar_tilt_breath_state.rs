//! Causal 25 Hz Polar State classifier fitted to three paired controller runs.
//!
//! `new` keeps the fixed sensor-axis model. `new_adaptive` additionally follows
//! posture and breathing direction while retaining the fitted phase weights.
//! Large movements pause assessment until the signal has settled.

use crate::native_controller_breath_state::{
    cross3, dot3, normalized_axis, rotate_vec3_by_quat, DynamicControllerAxis,
};
use rusty_quest_breath_contract::assessment::CommonBreathPhase;

const STEP_S: f64 = 0.04;
const MAX_GAP_S: f64 = 0.125;
const CLOCK_EPSILON_S: f64 = 1.0e-7;
const HISTORY_LEN: usize = 23; // Current sample plus the longest 22-step lookback.
const HOLD: usize = 1;
const PHASES: [CommonBreathPhase; 3] = [
    CommonBreathPhase::Exhale,
    CommonBreathPhase::Hold,
    CommonBreathPhase::Inhale,
];

// Older-only fit, with feature standardization folded into weights and bias.
// Each triplet is sensor X/Y/Z in the same order as the replay model.
#[rustfmt::skip]
const WEIGHTS: [[f64; 15]; 3] = [
    [
        -0.0149833372999502, -0.02315505102807928, 0.001052825131976726,
        -0.01252300363945034, -0.0456710796878445, -0.002242086332460257,
        -0.009854398106709131, 0.06872175911389035, 0.005968950264077458,
        0.003399293424114851, 0.1585240002930607, 0.006103698862083247,
        -0.0605778034154611, -0.5242783440316829, -0.07606617307844235,
    ],
    [
        0.01250467562253092, -0.005072215165876475, -0.01665361921245525,
        0.01596058078293824, 0.09865622924786975, -0.005618394997415752,
        0.0197988664812258, -0.02015030608922042, -0.01086085939581243,
        -0.006736264891104189, -0.1743309040372197, -0.01482078248313538,
        -0.02658290232049663, 0.1413628840697556, 0.002616875727941199,
    ],
    [
        0.002478661677419283, 0.02822726619395479, 0.01560079408047815,
        -0.003437577143488036, -0.05298514956002652, 0.00786048132987604,
        -0.009944468374516667, -0.04857145302466964, 0.004891909131735181,
        0.003336971466989534, 0.01580690374415765, 0.008717083621052375,
        0.08716070573595727, 0.3829154599619278, 0.07344929735050151,
    ],
];
const INTERCEPT: [f64; 3] = [-0.235820882895118, 1.9384749754048052, -1.7026540925096652];

#[derive(Debug)]
pub(crate) struct PolarTiltBreathState {
    last_input: Option<(f64, [f64; 3])>,
    next_tick_s: f64,
    fast: [f64; 3],
    medium: [f64; 3],
    baseline: [f64; 3],
    history: [[[f64; 3]; 2]; HISTORY_LEN],
    history_cursor: usize,
    scores: [f64; 3],
    state: usize,
    candidate: usize,
    candidate_count: u8,
    frame: Option<AdaptiveTiltFrame>,
}

impl PolarTiltBreathState {
    pub(crate) fn new() -> Self {
        Self {
            last_input: None,
            next_tick_s: 0.0,
            fast: [0.0; 3],
            medium: [0.0; 3],
            baseline: [0.0; 3],
            history: [[[0.0; 3]; 2]; HISTORY_LEN],
            history_cursor: 0,
            scores: [0.0; 3],
            state: HOLD,
            candidate: HOLD,
            candidate_count: 0,
            frame: None,
        }
    }

    pub(crate) fn new_adaptive() -> Self {
        Self {
            frame: Some(AdaptiveTiltFrame::default()),
            ..Self::new()
        }
    }

    pub(crate) fn reset_history(&mut self) {
        *self = if self.frame.is_some() {
            Self::new_adaptive()
        } else {
            Self::new()
        };
    }

    pub(crate) fn lose_signal(&mut self) {
        self.reset_history();
    }

    pub(crate) fn push_acceleration(
        &mut self,
        at: f64,
        raw_g: [f64; 3],
    ) -> (CommonBreathPhase, bool) {
        if !at.is_finite() || !raw_g.iter().all(|value| value.is_finite()) {
            self.lose_signal();
            return (CommonBreathPhase::BadTracking, true);
        }

        // The replay normalized milligravity vectors, with a minimum norm of 1 mg.
        let norm_g = raw_g.iter().map(|value| value * value).sum::<f64>().sqrt();
        let tilt = raw_g.map(|value| value * (1_000.0 / norm_g.max(0.001)));
        if let Some((previous_at, previous_tilt)) = self.last_input {
            if at < previous_at || at - previous_at > MAX_GAP_S {
                self.lose_signal();
            } else {
                // Fill elapsed ticks with the last sample available at each tick.
                // An input can affect its own tick but never an earlier one.
                while self.next_tick_s <= at + CLOCK_EPSILON_S {
                    let sample = if self.next_tick_s >= at - CLOCK_EPSILON_S {
                        tilt
                    } else {
                        previous_tilt
                    };
                    self.step(sample);
                    self.next_tick_s += STEP_S;
                }
            }
        }

        if self.last_input.is_none() {
            if let Some(frame) = &mut self.frame {
                frame.reference_gravity = normalized_axis(tilt);
            }
            self.fast = tilt;
            self.medium = tilt;
            self.baseline = tilt;
            self.history.fill([tilt; 2]);
            self.scores = probabilities([0.0; 15]);
            self.next_tick_s = at + STEP_S;
        }
        self.last_input = Some((at, tilt));
        (PHASES[self.state], false)
    }

    fn step(&mut self, tilt: [f64; 3]) {
        self.fast = ema(self.fast, tilt, 0.10);
        self.medium = ema(self.medium, tilt, 0.22);
        self.baseline = ema(self.baseline, self.medium, 1.3);

        let mut blocks = [
            self.slope(self.fast, 0, 8, 0.32),
            self.slope(self.fast, 0, 22, 0.88),
            self.slope(self.medium, 1, 8, 0.32),
            self.slope(self.medium, 1, 22, 0.88),
            std::array::from_fn(|axis| self.medium[axis] - self.baseline[axis]),
        ];
        self.history[self.history_cursor] = [self.fast, self.medium];
        self.history_cursor = (self.history_cursor + 1) % HISTORY_LEN;
        if let Some(frame) = &mut self.frame {
            let Some(compensated) = frame.compensate(self.baseline, blocks) else {
                if frame.rebase {
                    // Posture settling must not leave its exponential tail looking
                    // like an exhale after the movement guard opens again.
                    self.fast = self.medium;
                    self.baseline = self.medium;
                    self.history.fill([self.medium; 2]);
                }
                self.scores = probabilities([0.0; 15]);
                self.state = HOLD;
                self.candidate = HOLD;
                self.candidate_count = 0;
                return;
            };
            blocks = compensated;
        }
        let features = std::array::from_fn(|i| blocks[i / 3][i % 3]);
        self.scores = ema(self.scores, probabilities(features), 0.085);

        let best = (1..self.scores.len()).fold(0, |best, index| {
            if self.scores[index] > self.scores[best] {
                index
            } else {
                best
            }
        });
        let next = if self.scores[best] - self.scores[self.state] >= 0.10 {
            best
        } else {
            self.state
        };
        if next == self.state {
            self.candidate = self.state;
            self.candidate_count = 0;
            return;
        }
        if next != self.candidate {
            self.candidate = next;
            self.candidate_count = 0;
        }
        self.candidate_count += 1;
        let confirmation_samples = if next == HOLD { 2 } else { 4 };
        if self.candidate_count >= confirmation_samples {
            self.state = next;
            self.candidate_count = 0;
        }
    }

    fn slope(&self, current: [f64; 3], filter: usize, lag: usize, lag_s: f64) -> [f64; 3] {
        let old = self.history[(self.history_cursor + HISTORY_LEN - lag) % HISTORY_LEN][filter];
        std::array::from_fn(|axis| (current[axis] - old[axis]) / lag_s)
    }
}

/// Carry the initial classifier frame across posture changes, then learn changes
/// in the breathing direction within that gravity-stabilized frame. The learned
/// axis retains its sign through holds and movement recovery.
#[derive(Debug, Default)]
struct AdaptiveTiltFrame {
    reference_gravity: Option<[f64; 3]>,
    reference_axis: Option<[f64; 3]>,
    learned_axis: Option<[f64; 3]>,
    learner: DynamicControllerAxis,
    ticks: u64,
    stable_ticks: usize,
    settling_ticks: usize,
    moving: bool,
    rebase: bool,
    adapting: bool,
}

impl AdaptiveTiltFrame {
    fn compensate(&mut self, baseline: [f64; 3], blocks: [[f64; 3]; 5]) -> Option<[[f64; 3]; 5]> {
        // A large displacement must settle before it can teach the breath axis.
        // Hysteresis keeps ordinary breath motion from repeatedly closing it.
        const MOTION_ENTER_MG: f64 = 80.0;
        const MOTION_LEAVE_MG: f64 = 20.0;
        const LEARN_TICKS: usize = 75; // Three seconds at 25 Hz.
        const ADAPT_DEGREES: f64 = 5.0;

        self.ticks += 1;
        self.rebase = false;
        let residual = blocks[4];
        let motion = dot3(residual, residual).sqrt();
        self.moving = motion
            > if self.moving {
                MOTION_LEAVE_MG
            } else {
                MOTION_ENTER_MG
            };
        if self.moving {
            self.learner = DynamicControllerAxis::default();
            self.stable_ticks = 0;
            self.settling_ticks = HISTORY_LEN;
            return None;
        }
        if self.settling_ticks > 0 {
            self.settling_ticks -= 1;
            self.rebase = self.settling_ticks == 0;
            return None;
        }

        let gravity = normalized_axis(baseline)?;
        let reference_gravity = *self.reference_gravity.get_or_insert(gravity);
        let posture_rotation = rotation_between(gravity, reference_gravity);
        let stabilized =
            rotate_vec3_by_quat(residual.map(|value| value / 1_000.0), posture_rotation);
        self.stable_ticks += 1;
        if let Some(axis) =
            self.learner
                .observe(self.ticks as f64 * STEP_S, stabilized, self.learned_axis)
        {
            // Normalized gravity measurements move on a sphere. Their useful
            // breathing direction lies in its tangent plane.
            let along_gravity = dot3(axis, reference_gravity);
            if let Some(tangent) = normalized_axis(std::array::from_fn(|i| {
                axis[i] - along_gravity * reference_gravity[i]
            })) {
                self.learned_axis = Some(tangent);
                if self.stable_ticks >= LEARN_TICKS && self.reference_axis.is_none() {
                    self.reference_axis = Some(tangent);
                }
            }
        }

        let direction_changed =
            self.reference_axis
                .zip(self.learned_axis)
                .is_some_and(|(reference, learned)| {
                    dot3(reference, learned) < ADAPT_DEGREES.to_radians().cos()
                });
        self.adapting |= direction_changed
            || dot3(gravity, reference_gravity) < ADAPT_DEGREES.to_radians().cos();
        if !self.adapting {
            return Some(blocks);
        }

        let twist = self.reference_axis.zip(self.learned_axis).map_or(
            [0.0, 0.0, 0.0, 1.0],
            |(reference, learned)| {
                let sine = dot3(reference_gravity, cross3(learned, reference));
                let cosine = dot3(learned, reference);
                let half_angle = 0.5 * sine.atan2(cosine);
                [
                    reference_gravity[0] * half_angle.sin(),
                    reference_gravity[1] * half_angle.sin(),
                    reference_gravity[2] * half_angle.sin(),
                    half_angle.cos(),
                ]
            },
        );
        Some(
            blocks.map(|value| {
                rotate_vec3_by_quat(rotate_vec3_by_quat(value, posture_rotation), twist)
            }),
        )
    }
}

fn rotation_between(from: [f64; 3], to: [f64; 3]) -> [f64; 4] {
    let vector = cross3(from, to);
    let scalar = 1.0 + dot3(from, to).clamp(-1.0, 1.0);
    let length = (dot3(vector, vector) + scalar * scalar).sqrt();
    if length < 1.0e-8 {
        let reference = if from[0].abs() < 0.9 {
            [1.0, 0.0, 0.0]
        } else {
            [0.0, 1.0, 0.0]
        };
        let axis = normalized_axis(cross3(from, reference)).expect("perpendicular unit axes");
        return [axis[0], axis[1], axis[2], 0.0];
    }
    [
        vector[0] / length,
        vector[1] / length,
        vector[2] / length,
        scalar / length,
    ]
}

fn ema(previous: [f64; 3], current: [f64; 3], tau_s: f64) -> [f64; 3] {
    let weight = -(-STEP_S / tau_s).exp_m1();
    std::array::from_fn(|i| previous[i] + weight * (current[i] - previous[i]))
}

fn probabilities(features: [f64; 15]) -> [f64; 3] {
    let logits: [f64; 3] = std::array::from_fn(|i| {
        INTERCEPT[i]
            + WEIGHTS[i]
                .iter()
                .zip(features)
                .map(|(w, x)| w * x)
                .sum::<f64>()
    });
    let max = logits.into_iter().fold(f64::NEG_INFINITY, f64::max);
    let scores = logits.map(|value| (value - max).exp());
    let sum = scores.iter().sum::<f64>();
    scores.map(|value| value / sum)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn probe(i: usize) -> [f64; 3] {
        let breath = (i as f64 * std::f64::consts::PI / 50.0).sin();
        [
            -0.630 + 0.030 * breath,
            0.080 + 0.010 * breath,
            0.720 + 0.030 * breath,
        ]
    }

    #[test]
    fn matches_exported_python_model_phase_transitions() {
        let mut model = PolarTiltBreathState::new();
        let mut transitions = vec![];
        for i in 0..300 {
            let (phase, rejected) = model.push_acceleration(i as f64 * STEP_S, probe(i));
            assert!(!rejected);
            if transitions
                .last()
                .is_none_or(|(_, previous)| *previous != phase)
            {
                transitions.push((i, phase));
            }
        }
        use CommonBreathPhase::{Exhale, Hold, Inhale};
        assert_eq!(
            transitions,
            [
                (0, Hold),
                (18, Inhale),
                (40, Hold),
                (57, Exhale),
                (97, Hold),
                (110, Inhale),
                (143, Hold),
                (159, Exhale),
                (196, Hold),
                (209, Inhale),
                (243, Hold),
                (260, Exhale),
                (296, Hold),
            ]
        );
    }

    #[test]
    fn reclined_postures_recover_the_upright_breath_phases() {
        use crate::native_controller_breath_state::rotate_vec3_by_quat;

        for degrees in [-60.0_f64, -35.0, 35.0, 60.0] {
            let mut upright = PolarTiltBreathState::new();
            let mut reclining = PolarTiltBreathState::new_adaptive();
            let mut matches = 0;
            let mut compared = 0;
            let mut observed = Vec::new();
            for i in 0..1_500 {
                let time = i as f64 * STEP_S;
                let progress = ((time - 12.0) / 3.0).clamp(0.0, 1.0);
                let half_angle = 0.5 * degrees.to_radians() * progress;
                let input =
                    rotate_vec3_by_quat(probe(i), [0.0, half_angle.sin(), 0.0, half_angle.cos()]);
                let expected = upright.push_acceleration(time, probe(i)).0;
                let actual = reclining.push_acceleration(time, input).0;
                if time >= 28.0 {
                    compared += 1;
                    matches += usize::from(actual == expected);
                    observed.push(actual);
                }
            }
            assert!(observed.contains(&CommonBreathPhase::Inhale));
            assert!(observed.contains(&CommonBreathPhase::Exhale));
            assert!(observed.contains(&CommonBreathPhase::Hold));
            assert!(
                matches as f64 / compared as f64 > 0.90,
                "{degrees} degree recline matched only {matches}/{compared} phases",
            );
        }
    }

    #[test]
    fn breathing_direction_relearns_without_reversing_inhale_and_exhale() {
        use crate::native_controller_breath_state::{normalized_axis, rotate_vec3_by_quat};

        let center = probe(0);
        let gravity = normalized_axis(center).unwrap();
        let mut upright = PolarTiltBreathState::new();
        let mut adapting = PolarTiltBreathState::new_adaptive();
        let mut matches = 0;
        let mut compared = 0;
        for i in 0..1_500 {
            let time = i as f64 * STEP_S;
            let progress = ((time - 12.0) / 5.0).clamp(0.0, 1.0);
            let half_angle = 0.5 * 70.0_f64.to_radians() * progress;
            let rotation = [
                gravity[0] * half_angle.sin(),
                gravity[1] * half_angle.sin(),
                gravity[2] * half_angle.sin(),
                half_angle.cos(),
            ];
            let offset = std::array::from_fn(|axis| probe(i)[axis] - center[axis]);
            let rotated = rotate_vec3_by_quat(offset, rotation);
            let input = std::array::from_fn(|axis| center[axis] + rotated[axis]);
            let expected = upright.push_acceleration(time, probe(i)).0;
            let actual = adapting.push_acceleration(time, input).0;
            if time >= 30.0 {
                compared += 1;
                matches += usize::from(actual == expected);
            }
        }
        assert!(
            matches as f64 / compared as f64 > 0.90,
            "learned direction matched only {matches}/{compared} phases",
        );
    }

    #[test]
    fn large_posture_movement_is_held_and_breathing_recovers_after_stillness() {
        let mut model = PolarTiltBreathState::new_adaptive();
        let mut upright = PolarTiltBreathState::new();
        let half_angle = 0.5 * 65.0_f64.to_radians();
        let recline = [half_angle.sin(), 0.0, 0.0, half_angle.cos()];
        for i in 0..300 {
            model.push_acceleration(i as f64 * STEP_S, probe(i));
            upright.push_acceleration(i as f64 * STEP_S, probe(i));
        }
        let still = rotate_vec3_by_quat(probe(0), recline);
        for i in 300..600 {
            let phase = model.push_acceleration(i as f64 * STEP_S, still).0;
            assert_eq!(phase, CommonBreathPhase::Hold, "posture shift at tick {i}");
            upright.push_acceleration(i as f64 * STEP_S, probe(0));
        }
        let mut matches = 0;
        let mut compared = 0;
        for i in 600..1_200 {
            let time = i as f64 * STEP_S;
            let expected = upright.push_acceleration(time, probe(i)).0;
            let actual = model
                .push_acceleration(time, rotate_vec3_by_quat(probe(i), recline))
                .0;
            if i >= 850 {
                compared += 1;
                matches += usize::from(actual == expected);
            }
        }
        assert!(matches as f64 / compared as f64 > 0.90);
    }

    #[test]
    fn long_gap_starts_with_fresh_history() {
        for make in [
            PolarTiltBreathState::new,
            PolarTiltBreathState::new_adaptive,
        ] {
            let mut model = make();
            let mut fresh = make();
            for i in 0..39 {
                model.push_acceleration(i as f64 * STEP_S, probe(i));
            }
            for i in 0..80 {
                let timestamp = 2.0 + i as f64 * STEP_S;
                assert_eq!(
                    model.push_acceleration(timestamp, probe(i)),
                    fresh.push_acceleration(timestamp, probe(i))
                );
            }
            assert_eq!(model.frame.is_some(), fresh.frame.is_some());
        }
    }
}
