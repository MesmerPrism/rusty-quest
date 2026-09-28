//! Admission for the one-way concurrent-to-Local rollback, never Peer Stop.
#[derive(Clone,Copy,Debug,Default)]
pub struct CleanupFacts { pub host_closed:bool,pub own_unclaimed:bool,pub epoch_retired:bool,
 pub renderer_terminal:bool,pub banks_inactive:bool }
pub fn allow_local(f:CleanupFacts)->bool {f.host_closed&&f.own_unclaimed&&f.epoch_retired&&f.renderer_terminal&&f.banks_inactive}
#[cfg(test)] mod tests {use super::*;
 fn terminal()->CleanupFacts{CleanupFacts{host_closed:true,own_unclaimed:true,epoch_retired:true,renderer_terminal:true,banks_inactive:true}}
 #[test] fn peer_stop_and_every_pending_physical_owner_preserve_own_route(){
  let ready=terminal();assert!(allow_local(ready));
  for missing in 0..5 {let mut f=ready;match missing{0=>f.host_closed=false,1=>f.own_unclaimed=false,2=>f.epoch_retired=false,3=>f.renderer_terminal=false,_=>f.banks_inactive=false};assert!(!allow_local(f));}
 }
 #[test] fn unstarted_or_uncertain_is_not_terminal(){assert!(!allow_local(CleanupFacts::default()));}
}
