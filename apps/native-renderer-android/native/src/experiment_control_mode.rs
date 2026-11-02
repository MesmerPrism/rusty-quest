//! Process-local operator-mode authority and controller shortcut admission.

use std::sync::atomic::{AtomicU64, Ordering};

const DEFAULT_SNAPSHOT: u64 = 9;
const MAX_REVISION: u64 = (1 << 60) - 1;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ControlMode {
    Choose = 0,
    Direct = 1,
    Remote = 2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ModeSnapshot(u64);

impl ModeSnapshot {
    pub(crate) fn packed(self) -> u64 {
        self.0
    }
    pub(crate) fn selected(self) -> bool {
        self.0 & 4 != 0
    }
    pub(crate) fn mode(self) -> ControlMode {
        match self.0 & 3 {
            0 => ControlMode::Choose,
            2 => ControlMode::Remote,
            _ => ControlMode::Direct,
        }
    }
    pub(crate) fn revision(self) -> u64 {
        self.0 >> 3
    }
    pub(crate) fn chooser_required(self) -> bool {
        self.selected() && self.mode() == ControlMode::Choose
    }

    pub(crate) fn startup_panel_required(self, remote_preparation: bool) -> bool {
        !remote_preparation || self.chooser_required()
    }
}

struct ModeAuthority(AtomicU64);
impl ModeAuthority {
    const fn new() -> Self {
        Self(AtomicU64::new(DEFAULT_SNAPSHOT))
    }
    fn read(&self) -> ModeSnapshot {
        ModeSnapshot(self.0.load(Ordering::SeqCst))
    }
    fn initialize(&self, selected: bool) -> ModeSnapshot {
        if selected {
            // Promotion is process-local and one-way; recreation cannot reset a choice.
            let _ =
                self.0
                    .compare_exchange(DEFAULT_SNAPSHOT, 20, Ordering::SeqCst, Ordering::SeqCst);
        }
        self.read()
    }
    fn select(&self, expected: u64, requested: i32) -> ModeSnapshot {
        let current = self.read();
        if current.packed() != expected
            || !current.selected()
            || !matches!(requested, 1 | 2)
            || current.mode() as i32 == requested
            || current.revision() == MAX_REVISION
        {
            return current;
        }
        let next = ((current.revision() + 1) << 3) | 4 | requested as u64;
        match self
            .0
            .compare_exchange(expected, next, Ordering::SeqCst, Ordering::SeqCst)
        {
            Ok(_) => ModeSnapshot(next),
            Err(actual) => ModeSnapshot(actual),
        }
    }
}

static AUTHORITY: ModeAuthority = ModeAuthority::new();
pub(crate) fn read() -> ModeSnapshot {
    AUTHORITY.read()
}

#[cfg(target_os = "android")]
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ExperimentControlMode_nativeInitialize(
    _env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    selected: jni::sys::jboolean,
) -> jni::sys::jlong {
    AUTHORITY.initialize(selected).packed() as i64
}

#[cfg(target_os = "android")]
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ExperimentControlMode_nativeRead(
    _env: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jni::sys::jlong {
    read().packed() as i64
}

#[cfg(target_os = "android")]
#[cfg(not(feature = "showcase"))]
#[no_mangle]
pub extern "system" fn Java_io_github_mesmerprism_rustyquest_native_1renderer_ExperimentControlMode_nativeSelect(
    _env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    expected: jni::sys::jlong,
    mode: jni::sys::jint,
) -> jni::sys::jlong {
    AUTHORITY.select(expected as u64, mode).packed() as i64
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct ControllerModeInput {
    pub(crate) primary: bool,
    pub(crate) secondary: bool,
    pub(crate) grip: bool,
    pub(crate) trigger: bool,
    pub(crate) select: bool,
    pub(crate) sticks_neutral: bool,
    pub(crate) scene_sticks_available: bool,
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct ControllerModeActions {
    pub(crate) clear_pending: bool,
    pub(crate) direct_shortcuts: bool,
    pub(crate) scene_sticks: bool,
    pub(crate) recenter: bool,
    pub(crate) panel_toggle: bool,
}

pub(crate) struct ControllerModeGate {
    snapshot: ModeSnapshot,
    require_neutral: bool,
    scene_sticks_armed: bool,
    previous_primary: bool,
    previous_secondary: bool,
    secondary_presses: u8,
    secondary_elapsed: f32,
}
impl ControllerModeGate {
    pub(crate) fn new(snapshot: ModeSnapshot) -> Self {
        Self {
            snapshot,
            require_neutral: snapshot.selected(),
            scene_sticks_armed: false,
            previous_primary: false,
            previous_secondary: false,
            secondary_presses: 0,
            secondary_elapsed: 0.0,
        }
    }
    pub(crate) fn update(
        &mut self,
        snapshot: ModeSnapshot,
        input: Option<ControllerModeInput>,
        dt: f32,
    ) -> ControllerModeActions {
        if snapshot != self.snapshot {
            self.snapshot = snapshot;
            self.require_neutral = snapshot.selected();
            self.scene_sticks_armed = false;
        }
        if !snapshot.selected() {
            return ControllerModeActions {
                direct_shortcuts: true,
                scene_sticks: true,
                ..Default::default()
            };
        }
        let Some(input) = input else {
            self.require_neutral = true;
            self.scene_sticks_armed = false;
            self.clear_sequence();
            return ControllerModeActions {
                clear_pending: true,
                ..Default::default()
            };
        };
        if !input.scene_sticks_available {
            self.scene_sticks_armed = false;
        } else if input.sticks_neutral {
            self.scene_sticks_armed = true;
        }
        if self.require_neutral {
            self.clear_sequence();
            self.require_neutral = input.primary
                || input.secondary
                || input.grip
                || input.trigger
                || input.select
                || !input.sticks_neutral;
            return ControllerModeActions {
                clear_pending: true,
                ..Default::default()
            };
        }
        if snapshot.mode() == ControlMode::Direct {
            return ControllerModeActions {
                direct_shortcuts: true,
                scene_sticks: self.scene_sticks_armed,
                ..Default::default()
            };
        }
        let primary_edge = input.primary && !self.previous_primary;
        let secondary_edge = input.secondary && !self.previous_secondary;
        self.previous_primary = input.primary;
        self.previous_secondary = input.secondary;
        if input.grip {
            self.secondary_presses = 0;
            self.secondary_elapsed = 0.0;
            return ControllerModeActions::default();
        }
        if self.secondary_presses > 0 && dt.is_finite() && dt > 0.0 {
            self.secondary_elapsed += dt.min(60.0);
            if self.secondary_elapsed > 5.0 {
                self.secondary_presses = 0;
            }
        }
        let mut panel_toggle = false;
        if secondary_edge {
            if self.secondary_presses == 0 {
                self.secondary_elapsed = 0.0;
            }
            self.secondary_presses += 1;
            if self.secondary_presses == 3 {
                panel_toggle = true;
                self.secondary_presses = 0;
                self.secondary_elapsed = 0.0;
            }
        }
        ControllerModeActions {
            recenter: primary_edge,
            panel_toggle,
            ..Default::default()
        }
    }

    fn clear_sequence(&mut self) {
        self.previous_primary = false;
        self.previous_secondary = false;
        self.secondary_presses = 0;
        self.secondary_elapsed = 0.0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn neutral() -> ControllerModeInput {
        ControllerModeInput {
            sticks_neutral: true,
            scene_sticks_available: true,
            ..Default::default()
        }
    }
    fn selected(mode: i32) -> (ModeAuthority, ModeSnapshot) {
        let authority = ModeAuthority::new();
        let choose = authority.initialize(true);
        let snapshot = if mode == 0 {
            choose
        } else {
            authority.select(choose.packed(), mode)
        };
        (authority, snapshot)
    }

    #[test]
    fn legacy_default_is_unselected_direct_and_initialization_is_idempotent() {
        let authority = ModeAuthority::new();
        assert_eq!(authority.read().packed(), 9);
        assert_eq!(authority.initialize(false).mode(), ControlMode::Direct);
        assert!(!authority.read().selected());
        let choose = authority.initialize(true);
        assert_eq!(choose.packed(), 20);
        assert_eq!(choose.mode(), ControlMode::Choose);
        assert_eq!(choose.revision(), 2);
        assert_eq!(authority.initialize(true), choose);
        assert_eq!(authority.initialize(false), choose);
    }

    #[test]
    fn explicit_selection_requires_current_snapshot_and_survives_recreation() {
        let (authority, choose) = selected(0);
        let remote = authority.select(choose.packed(), 2);
        assert_eq!(remote.packed(), 30);
        assert_eq!(authority.select(choose.packed(), 1), remote);
        assert_eq!(authority.initialize(true), remote);
        assert_eq!(authority.initialize(false), remote);
        assert_eq!(authority.select(remote.packed(), 2), remote);
        assert_eq!(authority.select(remote.packed(), 1).packed(), 37);
    }

    #[test]
    fn invalid_modes_unselected_calls_and_exhausted_revision_cannot_select() {
        let authority = ModeAuthority::new();
        assert_eq!(authority.select(9, 2).packed(), 9);
        let choose = authority.initialize(true);
        for mode in [-1, 0, 3, i32::MAX] {
            assert_eq!(authority.select(choose.packed(), mode), choose);
        }
        assert_eq!(authority.select(u64::MAX, 2), choose);
        let exhausted = ModeAuthority(AtomicU64::new((MAX_REVISION << 3) | 5));
        assert_eq!(
            exhausted.select(exhausted.read().packed(), 2),
            exhausted.read()
        );
    }

    #[test]
    fn concurrent_selections_have_one_snapshot_winner() {
        use std::sync::{Arc, Barrier};
        let (authority, choose) = selected(0);
        let authority = Arc::new(authority);
        let barrier = Arc::new(Barrier::new(3));
        let workers: Vec<_> = [1, 2]
            .into_iter()
            .map(|mode| {
                let authority = authority.clone();
                let barrier = barrier.clone();
                std::thread::spawn(move || {
                    barrier.wait();
                    authority.select(choose.packed(), mode)
                })
            })
            .collect();
        barrier.wait();
        for worker in workers {
            assert_eq!(worker.join().unwrap(), authority.read());
        }
        assert_eq!(authority.read().revision(), 3);
        assert!(matches!(
            authority.read().mode(),
            ControlMode::Direct | ControlMode::Remote
        ));
    }

    #[test]
    fn selected_startup_requires_chooser_but_legacy_and_explicit_remote_do_not() {
        assert!(!ModeAuthority::new().read().chooser_required());
        assert!(selected(0).1.chooser_required());
        assert!(!selected(2).1.chooser_required());
    }

    #[test]
    fn remote_preparation_opens_unresolved_chooser_without_changing_legacy_startup() {
        let legacy = ModeAuthority::new().read();
        assert!(!legacy.startup_panel_required(true));
        assert!(legacy.startup_panel_required(false));
        let (_, choose) = selected(0);
        assert!(choose.startup_panel_required(true));
        assert!(choose.startup_panel_required(false));
        assert!(!selected(2).1.startup_panel_required(true));
    }

    #[test]
    fn legacy_direct_keeps_shortcuts_without_a_new_release_barrier() {
        let snapshot = ModeAuthority::new().read();
        let mut gate = ControllerModeGate::new(snapshot);
        assert!(gate.update(snapshot, None, 0.1).direct_shortcuts);
        let held = ControllerModeInput {
            primary: true,
            grip: true,
            ..neutral()
        };
        assert!(gate.update(snapshot, Some(held), 0.1).direct_shortcuts);
        assert!(gate.update(snapshot, Some(held), 0.1).scene_sticks);
    }

    #[test]
    fn absent_scene_sticks_do_not_block_menu_but_returned_held_sticks_require_release() {
        let (_, direct) = selected(1);
        let mut gate = ControllerModeGate::new(direct);
        let absent = ControllerModeInput {
            scene_sticks_available: false,
            ..neutral()
        };
        gate.update(direct, Some(absent), 0.1);
        let ready = gate.update(direct, Some(absent), 0.1);
        assert!(ready.direct_shortcuts);
        assert!(!ready.scene_sticks);
        let held = ControllerModeInput {
            sticks_neutral: false,
            ..neutral()
        };
        assert!(!gate.update(direct, Some(held), 0.1).scene_sticks);
        assert!(gate.update(direct, Some(neutral()), 0.1).scene_sticks);
        assert!(gate.update(direct, Some(held), 0.1).scene_sticks);
        assert!(!gate.update(direct, Some(absent), 0.1).scene_sticks);
        assert!(!gate.update(direct, Some(held), 0.1).scene_sticks);
    }

    #[test]
    fn selected_mode_requires_neutral_after_startup_and_every_revision_change() {
        let (authority, remote) = selected(2);
        let mut gate = ControllerModeGate::new(remote);
        let held = ControllerModeInput {
            primary: true,
            ..neutral()
        };
        assert!(!gate.update(remote, Some(held), 0.1).recenter);
        assert!(!gate.update(remote, Some(neutral()), 0.1).recenter);
        assert!(gate.update(remote, Some(held), 0.1).recenter);
        let direct = authority.select(remote.packed(), 1);
        let result = gate.update(direct, Some(held), 0.1);
        assert!(result.clear_pending);
        assert!(!result.direct_shortcuts);
        assert!(!gate.update(direct, None, 0.1).direct_shortcuts);
        assert!(
            !gate
                .update(
                    direct,
                    Some(ControllerModeInput {
                        trigger: true,
                        ..neutral()
                    }),
                    0.1
                )
                .direct_shortcuts
        );
        assert!(
            !gate
                .update(
                    direct,
                    Some(ControllerModeInput {
                        sticks_neutral: false,
                        ..neutral()
                    }),
                    0.1
                )
                .direct_shortcuts
        );
        assert!(!gate.update(direct, Some(neutral()), 0.1).direct_shortcuts);
        assert!(gate.update(direct, Some(held), 0.1).direct_shortcuts);
    }

    #[test]
    fn remote_and_choose_allow_only_ungripped_recenter_and_triple_secondary() {
        for mode in [0, 2] {
            let (_, snapshot) = selected(mode);
            let mut gate = ControllerModeGate::new(snapshot);
            gate.update(snapshot, Some(neutral()), 0.1);
            let primary = ControllerModeInput {
                primary: true,
                ..neutral()
            };
            assert!(gate.update(snapshot, Some(primary), 0.1).recenter);
            assert!(!gate.update(snapshot, Some(primary), 0.1).recenter);
            gate.update(snapshot, Some(neutral()), 0.1);
            assert!(
                !gate
                    .update(
                        snapshot,
                        Some(ControllerModeInput {
                            grip: true,
                            ..primary
                        }),
                        0.1
                    )
                    .recenter
            );
            gate.update(snapshot, Some(neutral()), 0.1);
            for press in 0..3 {
                let result = gate.update(
                    snapshot,
                    Some(ControllerModeInput {
                        secondary: true,
                        ..neutral()
                    }),
                    0.1,
                );
                assert!(!result.direct_shortcuts);
                assert_eq!(result.panel_toggle, press == 2);
                gate.update(snapshot, Some(neutral()), 0.1);
            }
        }
    }

    #[test]
    fn switching_and_grip_cancel_partial_secondary_gestures() {
        let (authority, choose) = selected(0);
        let mut gate = ControllerModeGate::new(choose);
        gate.update(choose, Some(neutral()), 0.1);
        let b = ControllerModeInput {
            secondary: true,
            ..neutral()
        };
        for _ in 0..2 {
            gate.update(choose, Some(b), 0.1);
            gate.update(choose, Some(neutral()), 0.1);
        }
        let remote = authority.select(choose.packed(), 2);
        assert!(!gate.update(remote, Some(b), 0.1).panel_toggle);
        gate.update(remote, Some(neutral()), 0.1);
        assert!(!gate.update(remote, Some(b), 0.1).panel_toggle);
        gate.update(
            remote,
            Some(ControllerModeInput {
                grip: true,
                ..neutral()
            }),
            0.1,
        );
        gate.update(remote, Some(neutral()), 0.1);
        for press in 0..3 {
            assert_eq!(gate.update(remote, Some(b), 0.1).panel_toggle, press == 2);
            gate.update(remote, Some(neutral()), 0.1);
        }
    }
}
