//! Lossless acquisition-time button observations, separate from coalesced poses.
use crate::breath_calibration_controller_action::{BreathCalibrationControllerAction, BreathCalibrationControllerActionSettings};
pub(crate) struct ControllerButton {
    action:BreathCalibrationControllerAction, last_sequence:u64, last_elapsed:u64,
    pressed:bool, trigger:Option<(u64,u64)>,
}
impl ControllerButton {
    pub(crate) fn new(settings:BreathCalibrationControllerActionSettings)->Self{
        Self{action:BreathCalibrationControllerAction::new(settings),last_sequence:0,last_elapsed:0,pressed:false,trigger:None}
    }
    pub(crate) fn observe(&mut self,sequence:u64,elapsed:u64,pressed:bool)->Result<(),String>{
        if sequence<=self.last_sequence||elapsed<=self.last_elapsed{return Err("sdk-button-observation-regressed".into());}
        let gap=elapsed-self.last_elapsed;
        if self.last_elapsed==0||gap>=crate::proof::EXPIRY_NS {self.action.update(0.0,false);self.pressed=false;}
        let dt=if self.pressed&&pressed {gap as f32/1e9}else{0.0};
        if self.action.update(dt,pressed){self.trigger=Some((sequence,elapsed));}
        self.pressed=pressed;self.last_sequence=sequence;self.last_elapsed=elapsed;Ok(())
    }
    pub(crate) fn take_trigger(&mut self,pose_sequence:u64,pose_elapsed:u64)->bool{
        let Some((sequence,elapsed))=self.trigger else{return false;};
        if pose_sequence<sequence||pose_elapsed<elapsed{return false;}
        self.trigger=None;pose_elapsed-elapsed<crate::proof::EXPIRY_NS
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn button()->ControllerButton{ControllerButton::new(BreathCalibrationControllerActionSettings::from_property_lookup(|key|
        if key.ends_with(".mode"){Some("right-secondary-hold-start".into())}else if key.ends_with(".hold_seconds"){Some("1.25".into())}else{None}))}
    #[test] fn release_press_between_pose_drains_does_not_merge_holds(){
        let mut b=button();let mut seq=1;let mut at=1_000_000_000;
        b.observe(seq,at,true).unwrap();
        for _ in 0..10{seq+=1;at+=100_000_000;b.observe(seq,at,true).unwrap();}
        seq+=1;at+=10_000_000;b.observe(seq,at,false).unwrap();
        seq+=1;at+=10_000_000;b.observe(seq,at,true).unwrap();
        for _ in 0..3{seq+=1;at+=100_000_000;b.observe(seq,at,true).unwrap();}
        assert!(!b.take_trigger(seq,at),"two partial holds must not merge");
        for _ in 0..10{seq+=1;at+=100_000_000;b.observe(seq,at,true).unwrap();}
        assert!(b.take_trigger(seq,at));assert!(!b.take_trigger(seq,at));
        seq+=1;at+=10_000_000;b.observe(seq,at,false).unwrap();
        seq+=1;at+=10_000_000;b.observe(seq,at,true).unwrap();
        for _ in 0..13{seq+=1;at+=100_000_000;b.observe(seq,at,true).unwrap();}
        assert!(b.take_trigger(seq,at),"release rearms the canonical action");
    }
    #[test] fn acquisition_gap_cannot_credit_unobserved_hold(){
        let mut b=button();b.observe(1,1_000_000_000,true).unwrap();b.observe(2,3_000_000_000,true).unwrap();
        assert!(!b.take_trigger(2,3_000_000_000));assert!(b.observe(1,4_000_000_000,false).is_err());
    }
}
