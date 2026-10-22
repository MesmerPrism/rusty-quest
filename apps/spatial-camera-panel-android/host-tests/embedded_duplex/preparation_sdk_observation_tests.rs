#[path = "../../native-receipt/src/embedded_duplex/preparation_sdk_observation.rs"]
mod observation;
use observation::{Direction as D, FrameKind as K, Reason as R, Stage as S, State, Trace};
#[test]
fn actual_error_order_and_first_failure_are_retained_without_extra_calls() {
    let trace = Trace::default();
    let mut calls = 0;
    let result: Result<(), String> = trace.call(
        D::Outbound,
        S::FrameExchange,
        Some(K::View),
        R::CallFailed,
        || {
            trace.call(
                D::Outbound,
                S::FrameSign,
                Some(K::View),
                R::CallFailed,
                || {
                    calls += 1;
                    Err("java_bridge.sign_call.exception".to_owned())
                },
            )
        },
    );
    assert_eq!(result.unwrap_err(), "java_bridge.sign_call.exception");
    assert_eq!(calls, 1);
    let before = trace.read().unwrap();
    assert_eq!(before.outbound.stage, Some(S::FrameSign));
    assert_eq!(before.outbound.reason, Some(R::CallbackException));
    let _: Result<(), String> = trace.call(
        D::Incoming,
        S::IncomingBind,
        Some(K::View),
        R::CallFailed,
        || Err("later private error".into()),
    );
    assert_eq!(trace.read().unwrap().first_failure, before.first_failure);
    trace.finish(D::Outbound, true);
    assert_eq!(trace.read().unwrap().outbound.state, State::Failed);
}
#[test]
fn all_stages_both_directions_and_frame_kinds_preserve_error_and_read_state() {
    for direction in [D::Outbound, D::Incoming] {
        for stage in S::ALL {
            for kind in [
                None,
                Some(K::View),
                Some(K::Viewed),
                Some(K::DecoderPrepare),
                Some(K::DecoderPrepared),
            ] {
                let trace = Trace::default();
                let mut calls = 0;
                let error = "primary private text".to_string();
                let actual: Result<(), String> =
                    trace.call(direction, stage, kind, R::PredicateRejected, || {
                        calls += 1;
                        Err(error.clone())
                    });
                assert_eq!(actual.unwrap_err(), error);
                assert_eq!(calls, 1);
                let a = trace.read().unwrap();
                assert_eq!(a, trace.read().unwrap());
                assert_eq!(a.first_failure.as_ref().unwrap().1.stage, Some(stage));
                assert_eq!(
                    a.first_failure.as_ref().unwrap().1.reason,
                    Some(R::PredicateRejected)
                );
            }
        }
    }
}
#[test]
fn unobserved_success_and_poison_are_honest() {
    let trace = Trace::default();
    assert_eq!(trace.read().unwrap().outbound.state, State::Unobserved);
    trace
        .call(D::Outbound, S::RendererBegin, None, R::CallFailed, || {
            Ok::<_, String>(())
        })
        .unwrap();
    trace.finish(D::Outbound, true);
    let view = trace.read().unwrap();
    assert_eq!(view.outbound.state, State::Succeeded);
    assert!(view.outbound.stage.is_none() && view.first_failure.is_none());
    trace.poison_for_test();
    assert!(trace.read().is_err());
    let mut calls = 0;
    trace
        .call(D::Outbound, S::SenderPrepare, None, R::CallFailed, || {
            calls += 1;
            Ok::<_, String>(())
        })
        .unwrap();
    assert_eq!(calls, 1);
}
