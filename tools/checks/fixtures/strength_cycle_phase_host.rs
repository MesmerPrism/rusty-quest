use std::path::Path;
fn activate(path: &str, generation: u64, probe: i64) {
    let bytes = std::fs::read(path).unwrap();
    let hash = feedback::digest::sha256(&bytes);
    strength_cycle_step::activate(Path::new(path), &hash, generation, probe).unwrap();
}
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    assert_eq!(args.len(), 5);
    let mut clock = SpatialPublicStrengthCyclePhase::default();
    assert_eq!(advance_strength_cycle_phase(&mut clock, 10.0, 0.25), 0.0);
    assert_eq!(strength_cycle_step::phase_call_count(), 0);
    strength_cycle_step::set_enabled(true);
    activate(&args[0], 1, strength_cycle_adapter::PROBE_A);
    let allocation = strength_cycle_step::snapshot().unwrap().allocation_token;
    assert_eq!(advance_strength_cycle_phase(&mut clock, 11.0, 0.25), 0.25);
    assert_eq!(
        strength_cycle_step::last_outcome().source,
        strength_cycle_adapter::StepSource::Module
    );
    assert_eq!(strength_cycle_step::adopted_module_generation(), 1);
    activate(&args[1], 2, strength_cycle_adapter::PROBE_B);
    assert_eq!(advance_strength_cycle_phase(&mut clock, 12.0, 0.25), 0.75);
    activate(&args[2], 3, strength_cycle_adapter::PROBE_A);
    assert_eq!(advance_strength_cycle_phase(&mut clock, 12.5, 0.25), 0.875);
    let before = strength_cycle_step::snapshot().unwrap();
    let bytes = std::fs::read(&args[3]).unwrap();
    let hash = feedback::digest::sha256(&bytes);
    assert!(strength_cycle_step::activate(
        Path::new(&args[3]),
        &hash,
        4,
        strength_cycle_adapter::PROBE_A
    )
    .is_err());
    let after = strength_cycle_step::snapshot().unwrap();
    assert_eq!(after.generation, 3);
    assert_eq!(after.completed, before.completed);
    assert_eq!(after.value, before.value);
    assert_eq!(after.active_sha256, before.active_sha256);
    assert_eq!(advance_strength_cycle_phase(&mut clock, 13.0, 0.25), 0.0);
    let calls = strength_cycle_step::phase_call_count();
    assert_eq!(advance_strength_cycle_phase(&mut clock, 1.0, 0.25), 0.0);
    assert_eq!(strength_cycle_step::phase_call_count(), calls);
    assert_eq!(advance_strength_cycle_phase(&mut clock, 2.0, 0.25), 0.25);
    activate(&args[4], 5, strength_cycle_adapter::PROBE_A);
    assert_eq!(advance_strength_cycle_phase(&mut clock, 3.0, 0.25), 0.5);
    assert_eq!(
        strength_cycle_step::last_outcome().source,
        strength_cycle_adapter::StepSource::InvalidOutput
    );
    assert_eq!(strength_cycle_step::adopted_module_generation(), 0);
    let before = strength_cycle_step::snapshot().unwrap();
    strength_cycle_step::set_enabled(false);
    assert_eq!(advance_strength_cycle_phase(&mut clock, 4.0, 0.25), 0.75);
    assert_eq!(
        strength_cycle_step::snapshot().unwrap().completed,
        before.completed
    );
    assert_eq!(
        strength_cycle_step::last_outcome().source,
        strength_cycle_adapter::StepSource::NoHost
    );
    assert_eq!(normalize_strength_cycle_speed_hz(f32::NAN), 0.25);
    assert_eq!(normalize_strength_cycle_speed_hz(4.0), 2.0);
    assert_eq!(normalize_strength_cycle_speed_hz(-1.0), 0.0);
    assert_eq!(
        strength_cycle_step::snapshot().unwrap().allocation_token,
        allocation
    );
    assert_eq!(strength_cycle_step::snapshot().unwrap().retained_images, 5);
    println!("PASS actual app function + real provider/app step: A .25 B .75 freshA .875; refusal/rebase/NaN/default-off; PID={} allocation={} phase_calls={}", std::process::id(), allocation, strength_cycle_step::phase_call_count());
}
