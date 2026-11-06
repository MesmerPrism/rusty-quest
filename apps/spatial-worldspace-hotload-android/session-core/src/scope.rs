//! Serializes only platform effects/lifecycle. Never held by reducer admission.
use std::sync::{Mutex,MutexGuard};
#[derive(Default)]
struct State { epoch:u64, live:bool }
#[derive(Default)]
pub(crate) struct ScopeGate(Mutex<State>);
pub(crate) struct Lease<'a> { _guard:MutexGuard<'a,State> }
impl ScopeGate {
    pub(crate) fn replace<T>(&self,epoch:u64,effect:impl FnOnce()->Result<T,String>)->Result<T,String>{
        let mut state=self.0.lock().map_err(|_|"sdk-effect-gate-poisoned")?;
        if epoch==0||epoch>=i64::MAX as u64||epoch<=state.epoch{return Err("sdk-owner-epoch-reused".into());}
        state.epoch=epoch; state.live=false;
        let result=effect()?; state.live=true; Ok(result)
    }
    pub(crate) fn lease(&self,epoch:u64)->Result<Lease<'_>,String>{
        let guard=self.0.lock().map_err(|_|"sdk-effect-gate-poisoned")?;
        if !guard.live||guard.epoch!=epoch{return Err("sdk-owner-retired".into());}
        Ok(Lease{_guard:guard})
    }
    pub(crate) fn cleanup_lease(&self,epoch:u64)->Result<Lease<'_>,String>{
        let guard=self.0.lock().map_err(|_|"sdk-effect-gate-poisoned")?;
        if guard.epoch!=epoch{return Err("sdk-owner-replaced".into());}
        Ok(Lease{_guard:guard})
    }
    pub(crate) fn revoke<T>(&self,epoch:u64,effect:impl FnOnce()->Result<T,String>)->Result<T,String>{
        let mut state=self.0.lock().map_err(|_|"sdk-effect-gate-poisoned")?;
        if state.epoch!=epoch{return Err("sdk-owner-stale".into());}
        state.live=false; effect()
    }
}
#[cfg(test)] mod tests{
    use super::*; use std::sync::{Arc,mpsc}; use std::thread;
    #[test] fn revoke_waits_for_actual_effect_and_no_effect_can_follow_its_return(){
        let gate=Arc::new(ScopeGate::default());gate.replace(1,||Ok(())).unwrap();
        let (entered,wait)=mpsc::channel();let (release,resume)=mpsc::channel();
        let g=gate.clone();let effect=thread::spawn(move||{let _lease=g.lease(1).unwrap();entered.send(()).unwrap();resume.recv().unwrap();});
        wait.recv().unwrap();let g=gate.clone();let (done,finished)=mpsc::channel();
        let revoke=thread::spawn(move||{g.revoke(1,||Ok(())).unwrap();done.send(()).unwrap();});
        assert!(finished.try_recv().is_err());release.send(()).unwrap();effect.join().unwrap();finished.recv().unwrap();revoke.join().unwrap();
        assert!(gate.lease(1).is_err());
    }
    #[test] fn blocked_old_poll_cannot_mutate_replacement(){
        let gate=Arc::new(ScopeGate::default());gate.replace(1,||Ok(())).unwrap();
        let (read,reading)=mpsc::channel();let (release,resume)=mpsc::channel();let g=gate.clone();
        let old=thread::spawn(move||{read.send(()).unwrap();resume.recv().unwrap();g.lease(1).is_ok()});
        reading.recv().unwrap();gate.replace(2,||Ok(())).unwrap();release.send(()).unwrap();assert!(!old.join().unwrap());assert!(gate.lease(2).is_ok());
    }
}

pub(crate) fn positive_counter(value:i64)->Result<u64,String>{
    if value<=0||value==i64::MAX{return Err("sdk-positive-counter-invalid-or-exhausted".into());}Ok(value as u64)
}
#[cfg(test)] mod counter_tests {use super::*;
    #[test] fn signed_negative_and_exhausted_never_poison_ordering(){
        for invalid in [i64::MIN,-1,0,i64::MAX]{assert!(positive_counter(invalid).is_err());}
        assert_eq!(positive_counter(1).unwrap(),1);assert_eq!(positive_counter(i64::MAX-1).unwrap(),(i64::MAX-1) as u64);
    }
}
