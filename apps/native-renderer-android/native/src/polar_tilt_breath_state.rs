//! Causal 25 Hz Polar State classifier fitted to three paired controller runs.

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
        }
    }

    pub(crate) fn reset_history(&mut self) {
        *self = Self::new();
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

        let blocks = [
            self.slope(self.fast, 0, 8, 0.32),
            self.slope(self.fast, 0, 22, 0.88),
            self.slope(self.medium, 1, 8, 0.32),
            self.slope(self.medium, 1, 22, 0.88),
            std::array::from_fn(|axis| self.medium[axis] - self.baseline[axis]),
        ];
        let features = std::array::from_fn(|i| blocks[i / 3][i % 3]);
        self.history[self.history_cursor] = [self.fast, self.medium];
        self.history_cursor = (self.history_cursor + 1) % HISTORY_LEN;
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
    fn long_gap_starts_with_fresh_history() {
        let mut model = PolarTiltBreathState::new();
        let mut fresh = PolarTiltBreathState::new();
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
    }
}
