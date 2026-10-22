//! Retained SDK call observation only. No callbacks, authority, retries or errors are serialized.
use std::sync::{Arc, Mutex};
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Direction {
    Outbound,
    Incoming,
}
impl Direction {
    pub fn name(self) -> &'static str {
        match self {
            Self::Outbound => "outbound",
            Self::Incoming => "incoming",
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FrameKind {
    View,
    Viewed,
    DecoderPrepare,
    DecoderPrepared,
}
impl FrameKind {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "view" => Some(Self::View),
            "viewed" => Some(Self::Viewed),
            "decoder_prepare" => Some(Self::DecoderPrepare),
            "decoder_prepared" => Some(Self::DecoderPrepared),
            _ => None,
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            Self::View => "view",
            Self::Viewed => "viewed",
            Self::DecoderPrepare => "decoder_prepare",
            Self::DecoderPrepared => "decoder_prepared",
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Stage {
    RendererBegin,
    SenderPrepare,
    SourceCheck,
    FrameClock,
    FrameShape,
    FrameSign,
    FrameEncode,
    FrameExchange,
    FrameDecode,
    ResponseIdentity,
    RemoteView,
    DecoderReceipt,
    DecoderWait,
    OwnerReady,
    RendererDormant,
    IncomingBind,
    IncomingSender,
    IncomingDecoder,
    ReadyStatus,
}
impl Stage {
    #[cfg(test)]
    pub const ALL: [Self; 19] = [
        Self::RendererBegin,
        Self::SenderPrepare,
        Self::SourceCheck,
        Self::FrameClock,
        Self::FrameShape,
        Self::FrameSign,
        Self::FrameEncode,
        Self::FrameExchange,
        Self::FrameDecode,
        Self::ResponseIdentity,
        Self::RemoteView,
        Self::DecoderReceipt,
        Self::DecoderWait,
        Self::OwnerReady,
        Self::RendererDormant,
        Self::IncomingBind,
        Self::IncomingSender,
        Self::IncomingDecoder,
        Self::ReadyStatus,
    ];
    pub fn name(self) -> &'static str {
        match self {
            Self::RendererBegin => "renderer_begin",
            Self::SenderPrepare => "sender_prepare",
            Self::SourceCheck => "source_check",
            Self::FrameClock => "frame_clock",
            Self::FrameShape => "frame_shape",
            Self::FrameSign => "frame_sign",
            Self::FrameEncode => "frame_encode",
            Self::FrameExchange => "frame_exchange",
            Self::FrameDecode => "frame_decode",
            Self::ResponseIdentity => "response_identity",
            Self::RemoteView => "remote_view",
            Self::DecoderReceipt => "decoder_receipt",
            Self::DecoderWait => "decoder_wait",
            Self::OwnerReady => "owner_ready",
            Self::RendererDormant => "renderer_dormant",
            Self::IncomingBind => "incoming_bind",
            Self::IncomingSender => "incoming_sender",
            Self::IncomingDecoder => "incoming_decoder",
            Self::ReadyStatus => "ready_status",
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Reason {
    CallFailed,
    PredicateRejected,
    SourceChanged,
    CallbackException,
    CallbackUnavailable,
    FrameBindingRejected,
    FrameSignatureRejected,
    FrameShapeRejected,
    FrameWireRejected,
}
impl Reason {
    pub fn name(self) -> &'static str {
        match self {
            Self::CallFailed => "call_failed",
            Self::PredicateRejected => "predicate_rejected",
            Self::SourceChanged => "source_changed",
            Self::CallbackException => "callback_exception",
            Self::CallbackUnavailable => "callback_unavailable",
            Self::FrameBindingRejected => "frame_binding_rejected",
            Self::FrameSignatureRejected => "frame_signature_rejected",
            Self::FrameShapeRejected => "frame_shape_rejected",
            Self::FrameWireRejected => "frame_wire_rejected",
        }
    }
    fn classify(self, error: &str) -> Self {
        if error == "prepared signed exact source changed before metadata completion" {
            Self::SourceChanged
        } else if error == "prepared signed peer/route/audit binding" {
            Self::FrameBindingRejected
        } else if matches!(
            error,
            "prepared metadata signature" | "prepared enrolled key"
        ) {
            Self::FrameSignatureRejected
        } else if matches!(
            error,
            "prepared closed frame shape/identity" | "prepared frame kind"
        ) {
            Self::FrameShapeRejected
        } else if matches!(
            error,
            "prepared wire bounds" | "prepared closed wire JSON" | "prepared signature hex"
        ) {
            Self::FrameWireRejected
        } else if error.starts_with("java_bridge.") && error.ends_with(".exception") {
            Self::CallbackException
        } else if error.starts_with("java_bridge.") {
            Self::CallbackUnavailable
        } else {
            self
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub(crate) enum State {
    #[default]
    Unobserved,
    Entered,
    Failed,
    Succeeded,
}
impl State {
    pub fn name(self) -> &'static str {
        match self {
            Self::Unobserved => "unobserved",
            Self::Entered => "entered",
            Self::Failed => "failed",
            Self::Succeeded => "succeeded",
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub(crate) struct Observation {
    pub state: State,
    pub stage: Option<Stage>,
    pub frame_kind: Option<FrameKind>,
    pub reason: Option<Reason>,
}
#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub(crate) struct Snapshot {
    pub outbound: Observation,
    pub incoming: Observation,
    pub first_failure: Option<(Direction, Observation)>,
}
impl Snapshot {
    fn lane(&mut self, direction: Direction) -> &mut Observation {
        match direction {
            Direction::Outbound => &mut self.outbound,
            Direction::Incoming => &mut self.incoming,
        }
    }
}
#[derive(Clone, Default)]
pub(crate) struct Trace(Arc<Mutex<Snapshot>>);
impl Trace {
    pub fn call<T>(
        &self,
        direction: Direction,
        stage: Stage,
        kind: Option<FrameKind>,
        reason: Reason,
        action: impl FnOnce() -> Result<T, String>,
    ) -> Result<T, String> {
        if let Ok(mut journal) = self.0.lock() {
            let lane = journal.lane(direction);
            if lane.state != State::Failed {
                *lane = Observation {
                    state: State::Entered,
                    stage: Some(stage),
                    frame_kind: kind,
                    reason: None,
                };
            }
        }
        // Observation locks are released before the one original fallible action.
        let result = action();
        if let Err(error) = &result {
            if let Ok(mut journal) = self.0.lock() {
                let lane = journal.lane(direction);
                if lane.state != State::Failed {
                    *lane = Observation {
                        state: State::Failed,
                        stage: Some(stage),
                        frame_kind: kind,
                        reason: Some(reason.classify(error)),
                    };
                }
                let observed = lane.clone();
                if journal.first_failure.is_none() {
                    journal.first_failure = Some((direction, observed));
                }
            }
        }
        result
    }
    pub fn finish(&self, direction: Direction, success: bool) {
        if success {
            if let Ok(mut journal) = self.0.lock() {
                let lane = journal.lane(direction);
                if lane.state != State::Failed {
                    *lane = Observation {
                        state: State::Succeeded,
                        ..Observation::default()
                    };
                }
            }
        }
    }
    pub fn read(&self) -> Result<Snapshot, ()> {
        self.0.lock().map(|v| v.clone()).map_err(|_| ())
    }
    #[cfg(test)]
    pub fn poison_for_test(&self) {
        let _ = std::panic::catch_unwind(|| {
            let _held = self.0.lock().unwrap();
            panic!("host poison");
        });
    }
}
