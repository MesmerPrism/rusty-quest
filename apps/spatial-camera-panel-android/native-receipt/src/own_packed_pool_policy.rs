#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) struct HoldLimits { pub slots: usize, pub bytes: u64, pub gpu_uses: usize }
impl HoldLimits { pub fn validate(self,w:u32,h:u32)->Result<(),String> {
 let bytes=u64::from(w).checked_mul(u64::from(h)).and_then(|v|v.checked_mul(4)).and_then(|v|v.checked_mul(self.slots as u64));
 if self.slots==0 || self.gpu_uses==0 || w==0 || w%2!=0 || h==0 || bytes.is_none_or(|v|v>self.bytes) { return Err("missing/invalid admitted packed hold limits".into()); } Ok(())
}}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct PairIdentity { pub pair_id:u64,pub left_frame:u64,pub right_frame:u64,pub left_ns:i64,pub right_ns:i64 }
impl PairIdentity { pub(crate) fn validate(self)->Result<(),String> { if self.pair_id==0 || self.left_ns<=0 || self.right_ns<=0 {Err("invalid accepted pair".into())}else{Ok(())} } pub fn pts(self)->i64 {self.left_ns.max(self.right_ns)} }
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct WriteTicket { pub pool:u64,pub serial:u64,pub framebuffer:u32 }

#[cfg(test)] mod tests { use super::*;
#[test] fn missing_hold_contract_and_over_budget_fail() { for limits in [HoldLimits{slots:0,bytes:100,gpu_uses:1},HoldLimits{slots:1,bytes:0,gpu_uses:1},HoldLimits{slots:1,bytes:32,gpu_uses:0},HoldLimits{slots:2,bytes:31,gpu_uses:1}] {assert!(limits.validate(2,2).is_err());} assert!(HoldLimits{slots:2,bytes:32,gpu_uses:1}.validate(2,2).is_ok()); }
#[test] fn invalid_geometry_and_pair_rejected_exact_pts_preserved() {let limits=HoldLimits{slots:1,bytes:u64::MAX,gpu_uses:1};assert!(limits.validate(3,2).is_err());assert!(limits.validate(2,0).is_err());let p=PairIdentity{pair_id:1,left_frame:0,right_frame:9,left_ns:19,right_ns:17};assert!(p.validate().is_ok());assert_eq!(p.pts(),19);assert!(PairIdentity{left_ns:0,..p}.validate().is_err());}
}

pub(crate) fn ticket_matches(ticket:WriteTicket,pool:u64,serial:u64,framebuffer:u32)->bool {ticket.pool==pool && ticket.serial==serial && ticket.framebuffer==framebuffer && serial>0 && framebuffer>0}
#[cfg(test)] mod ticket_tests {use super::*;#[test] fn stale_generation_serial_and_fbo_are_rejected() {let t=WriteTicket{pool:9,serial:7,framebuffer:6};assert!(ticket_matches(t,9,7,6));assert!(!ticket_matches(t,8,7,6));assert!(!ticket_matches(t,9,8,6));assert!(!ticket_matches(t,9,7,8));}}

#[derive(Clone,Copy,PartialEq,Eq)] pub(crate) enum PhysicalUsePhase {Prepared,Entered,Quarantined}
impl PhysicalUsePhase {
 pub(crate) fn enter(&mut self)->bool {if *self==Self::Prepared {*self=Self::Entered;true}else{false}}
 pub(crate) fn may_cancel(self)->bool {self==Self::Prepared}
 pub(crate) fn may_observe(self)->bool {self==Self::Entered}
}
#[cfg(test)] mod submission_tests {use super::*;
 #[test] fn cancellation_cannot_retire_a_driver_entered_or_failed_hold() {let mut phase=PhysicalUsePhase::Prepared;assert!(phase.may_cancel());assert!(!phase.may_observe());assert!(phase.enter());assert!(!phase.may_cancel());assert!(phase.may_observe());assert!(!phase.enter());phase=PhysicalUsePhase::Quarantined;assert!(!phase.may_cancel());assert!(!phase.may_observe());assert!(!phase.enter());}
}
