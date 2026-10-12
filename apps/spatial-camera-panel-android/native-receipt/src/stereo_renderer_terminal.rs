//! Bounded source-graph observation only; never media/cleanup authority.
use std::sync::Mutex;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct GraphToken { pub process: u64, pub route: u64, pub sequence: u64 }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Snapshot {
    pub token: GraphToken, pub surface: u64, pub state: &'static str,
    pub phase: &'static str, pub category: &'static str,
    pub cleanup: &'static str, pub observed_at_ns: u64,
    active_phase: &'static str,
}
struct Latch { sequence: u64, current: Option<Snapshot> }
impl Latch {
    const fn new() -> Self { Self { sequence: 0, current: None } }
    // Called only after the existing native physical owner claim succeeds.
    fn begin_after_claim(&mut self, process: u64, route: u64) -> Option<GraphToken> {
        if process == 0 || route == 0 { return None; }
        let Some(sequence) = self.sequence.checked_add(1) else {
            self.current = None; return None; // Observation exhaustion cannot veto effects.
        };
        self.sequence = sequence;
        let token = GraphToken { process, route, sequence };
        self.current = Some(Snapshot { token, surface: 0, state: "running",
            phase: "none", category: "none", cleanup: "unknown", observed_at_ns: 0, active_phase: "seed" });
        Some(token)
    }
    fn selected(&mut self, token: GraphToken) -> Option<&mut Snapshot> {
        self.current.as_mut().filter(|value| value.token == token && value.state == "running")
    }
    fn failure(&mut self, token: GraphToken, error: &str, now: u64) {
        if let Some(value) = self.selected(token) {
            if value.category == "none" {
                let (phase, category) = classify(error);
                value.phase = if phase == "native_other" { value.active_phase } else { phase };
                value.category = category; value.observed_at_ns = now;
            }
        }
    }
    fn surface(&mut self, token: GraphToken, surface: u64) {
        if let Some(value) = self.selected(token) {
            if value.surface == 0 && surface != 0 { value.surface = surface; }
        }
    }
    fn cleanup(&mut self, token: GraphToken, terminal: bool, now: u64) {
        if !terminal { self.failure(token, "physical-cleanup-pending", now); }
        if let Some(value) = self.selected(token) {
            value.cleanup = if terminal { "terminal" } else { "pending" };
        }
    }
    fn finish(&mut self, token: GraphToken, result: Result<(), &str>, now: u64) {
        if let Err(error) = result { self.failure(token, error, now); }
        if let Some(value) = self.selected(token) {
            // Unknown/pending physical cleanup never becomes terminal success.
            value.state = if value.cleanup != "terminal" { "cleanup_pending" }
                else if value.category == "none" { "succeeded" } else { "failed" };
            if value.category == "none" { value.observed_at_ns = now; }
        }
    }
    fn read(&self, expected_process: u64) -> Option<Snapshot> {
        self.current.filter(|value| expected_process != 0 && value.token.process == expected_process)
    }
}
fn classify(error: &str) -> (&'static str, &'static str) {
    if error == "panic" { return ("panic", "panic"); }
    if error == "physical-cleanup-pending" { return ("physical_cleanup", "retirement_pending"); }
    if error == "stereo-normalizer-control-changed-during-refresh" { return ("import_refresh", "control_changed"); }
    if error.starts_with("source-set-real-seed") || error.starts_with("source-set-cancelled-before-seed") { return ("seed", "source_binding"); }
    if error.starts_with("stereo-import") || error.starts_with("stereo-normalizer") || error.starts_with("own-source") { return ("import_refresh", "resource_or_driver"); }
    if error.starts_with("peer-source-binding") || error.starts_with("peer-exact-") { return ("import_refresh", "source_binding"); }
    if error.starts_with("stereo-recording") || error.starts_with("stereo-policy") { return ("command_record", "source_binding"); }
    if error.starts_with("peer-acquire-") { return ("wsi_acquire", "resource_or_driver"); }
    if error.starts_with("peer-queue-") { return ("queue_submit", "resource_or_driver"); }
    if error.contains("sdk-retirement") || error.contains("sdk-enqueue") || error.contains("during-sdk") { return ("sdk_retirement", "retirement_pending"); }
    ("native_other", "other_native")
}
static STATE: Mutex<Latch> = Mutex::new(Latch::new());
pub(crate) fn begin_after_owner_claim(process: u64, route: u64) -> Option<GraphToken> {
    STATE.lock().ok()?.begin_after_claim(process, route)
}
pub(crate) fn bind_surface(token: Option<GraphToken>, surface: u64) {
    if let (Some(token), Ok(mut state)) = (token, STATE.lock()) { state.surface(token, surface); }
}
pub(crate) fn set_phase(token: Option<GraphToken>, phase: &'static str) {
    if !["seed","import_refresh","command_record","wsi_acquire","queue_submit","sdk_retirement","physical_cleanup"].contains(&phase) { return; }
    if let (Some(token), Ok(mut state)) = (token, STATE.lock()) {
        if let Some(value) = state.selected(token) { value.active_phase = phase; }
    }
}
pub(crate) fn failure(token: Option<GraphToken>, error: &str, now: u64) {
    if let (Some(token), Ok(mut state)) = (token, STATE.lock()) { state.failure(token, error, now); }
}
pub(crate) fn record_cleanup(token: Option<GraphToken>, terminal: bool, now: u64) {
    if let (Some(token), Ok(mut state)) = (token, STATE.lock()) { state.cleanup(token, terminal, now); }
}
pub(crate) fn finish(token: Option<GraphToken>, result: Result<(), &str>, now: u64) {
    if let (Some(token), Ok(mut state)) = (token, STATE.lock()) { state.finish(token, result, now); }
}
pub(crate) fn read_snapshot(process: u64) -> Option<Snapshot> { STATE.lock().ok()?.read(process) }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn first_failure_and_late_graph_actor_fences() {
        let mut s=Latch::new();assert!(s.read(1).is_none());assert!(s.begin_after_claim(0,1).is_none());
        let old=s.begin_after_claim(1,7).unwrap();s.surface(old,3);s.surface(old,4);
        s.failure(old,"peer-source-binding-stale",10);s.cleanup(old,false,11);s.finish(old,Err("panic"),12);
        let v=s.read(1).unwrap();assert_eq!((v.phase,v.category,v.surface,v.state),("import_refresh","source_binding",3,"cleanup_pending"));assert_eq!(v.observed_at_ns,10);assert!(s.read(2).is_none());
        let new=s.begin_after_claim(1,7).unwrap();assert!(new.sequence>old.sequence);
        s.failure(old,"panic",20);s.cleanup(old,true,20);s.finish(old,Err("panic"),20);
        assert_eq!(s.read(1).unwrap().state,"running");assert_eq!(s.read(1).unwrap().category,"none");
        s.cleanup(new,true,21);s.finish(new,Ok(()),22);assert_eq!(s.read(1).unwrap().state,"succeeded");
        s.failure(new,"panic",23);s.cleanup(new,false,24);s.finish(new,Err("panic"),25);
        assert_eq!((s.read(1).unwrap().state,s.read(1).unwrap().cleanup,s.read(1).unwrap().category),("succeeded","terminal","none"));
    }
    #[test]
    fn success_unknown_pending_and_observation_exhaustion_do_not_mint_cleanup() {
        let mut s=Latch::new();let t=s.begin_after_claim(1,1).unwrap();s.finish(t,Ok(()),1);assert_eq!(s.read(1).unwrap().state,"cleanup_pending");assert_eq!(s.read(1).unwrap().cleanup,"unknown");
        let next=s.begin_after_claim(1,1).unwrap();s.cleanup(next,false,2);s.finish(next,Ok(()),3);assert_eq!(s.read(1).unwrap().cleanup,"pending");
        s.sequence=u64::MAX;assert!(s.begin_after_claim(1,1).is_none());assert!(s.read(1).is_none());
    }
    #[test]
    fn categories_are_closed_and_do_not_retain_raw_errors() {
        assert_eq!(classify("peer-acquire-next-image-ERROR_DEVICE_LOST"),("wsi_acquire","resource_or_driver"));
        assert_eq!(classify("secret arbitrary payload"),("native_other","other_native"));
        let mut s=Latch::new();let t=s.begin_after_claim(1,1).unwrap();s.failure(t,"secret arbitrary payload",1);s.cleanup(t,true,2);s.finish(t,Ok(()),3);assert_eq!(s.read(1).unwrap().state,"failed");
    }
}
