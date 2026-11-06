use super::*;

#[test]
fn production_scissor_policy_matches_for_finite_product_domain() {
    let mut state = 0x1234_abcd_u32;
    for _ in 0..12000 {
        let mut next = || {
            state = state.wrapping_mul(1664525).wrapping_add(1013904223);
            state
        };
        let width = 1 + next() % 8192;
        let height = 1 + next() % 8192;
        let rect = std::array::from_fn(|_| (next() % 40001) as f32 / 10000.0 - 1.0);
        let actual = policy::project(width, height, rect).expect("finite product leaf missing");
        let (x, w) = reference_interval(width, rect[0], rect[0] + rect[2]);
        let (y, h) = reference_interval(height, rect[1], rect[1] + rect[3]);
        assert_eq!(
            actual,
            scissor_abi::Pixels {
                x,
                y,
                width: w,
                height: h
            }
        );
    }
    assert_eq!(
        policy::project(1920, 1080, [0.1, 0.2, 0.55, 0.5]),
        Some(scissor_abi::Pixels {
            x: 192,
            y: 216,
            width: 1056,
            height: 540
        })
    );
}

#[test]
fn unsupported_inputs_refuse_before_computation() {
    for (w, h, r) in [
        (0, 100, [0., 0., 1., 1.]),
        (100, 0, [0., 0., 1., 1.]),
        (scissor_abi::MAX_EXTENT + 1, 10, [0., 0., 1., 1.]),
        (1, 1, [f32::NAN, 0., 1., 1.]),
        (1, 1, [0., 0., f32::INFINITY, 1.]),
        (1, 1, [f32::MAX, 0., f32::MAX, 1.]),
    ] {
        assert!(policy::project(w, h, r).is_none());
    }
}

#[test]
fn c_table_header_and_pod_layout_are_closed() {
    use scissor_abi::*;
    let valid = || Header {
        abi_version: VERSION,
        table_size: std::mem::size_of::<Api>() as u32,
        contract: CONTRACT,
        generation: 3,
    };
    assert!(validate(valid(), 3).is_ok());
    let mut h = valid();
    h.abi_version += 1;
    assert!(validate(h, 3).is_err());
    let mut h = valid();
    h.table_size = 24;
    assert!(validate(h, 3).is_err());
    let mut h = valid();
    h.contract = 0;
    assert!(validate(h, 3).is_err());
    assert!(validate(valid(), 2).is_err());
    assert!(validate(valid(), 0).is_err());
    assert_eq!(std::mem::size_of::<Pixels>(), 16);
}

#[test]
fn exported_call_refuses_without_writes_and_dispatch_retains_host_state() {
    use scissor_abi::*;
    let api = unsafe { &*compiled::rq_scissor_api().cast::<Api>() };
    let function = api.project.unwrap();
    scissor_runtime::probe(function, false).unwrap();
    let mut output = scissor_runtime::Guarded::new();
    let mut completed = 0;
    scissor_runtime::dispatch(
        function,
        &mut completed,
        &mut output,
        1920,
        1080,
        [0.1, 0.2, 0.55, 0.5],
    )
    .unwrap();
    let saved = output;
    assert_eq!(completed, 1);
    assert!(scissor_runtime::dispatch(
        function,
        &mut completed,
        &mut output,
        1920,
        1080,
        [f32::NAN, 0., 1., 1.]
    )
    .is_err());
    assert_eq!(output, saved);
    assert_eq!(completed, 1);
    completed = u64::MAX;
    assert!(scissor_runtime::dispatch(
        function,
        &mut completed,
        &mut output,
        1920,
        1080,
        [0., 0., 1., 1.]
    )
    .is_err());
    assert_eq!(output, saved);
    assert_eq!(completed, u64::MAX);
    for size in [1, 2, MAX_EXTENT - 1, MAX_EXTENT] {
        for rect in [[0., 0., 1., 1.], [-1., -1., 4., 4.], [0.8, 0.9, -0.2, -0.5]] {
            let actual = policy::project(size, size, rect).unwrap();
            let (x, w) = reference_interval(size, rect[0], rect[0] + rect[2]);
            let (y, h) = reference_interval(size, rect[1], rect[1] + rect[3]);
            assert_eq!(
                actual,
                Pixels {
                    x,
                    y,
                    width: w,
                    height: h
                }
            );
        }
    }
}
