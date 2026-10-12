package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONObject;

public final class PolarProjectedStopActionsTest {
    static final class Owner implements PolarProjectedStopActions.Target {
        boolean main=true,closed,throwsBeforeIntent,throwsAfterStop;
        int calls;String stopped="";
        long generation=1,acc=3,ecg=5;
        String selected="polar-111111111111",connected="polar-111111111111",epoch="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        public boolean mainThread(){return main;}
        public JSONObject state() throws Exception {
            return new JSONObject().put("pid",10).put("runtime_generation",generation).put("owner_epoch",epoch)
                .put("closed",closed).put("selected_sensor",selected).put("connected_sensor",connected)
                .put("acc_revision",acc).put("ecg_revision",ecg);
        }
        public void stop(String mode) throws Exception {
            calls++;if(throwsBeforeIntent)throw new Exception("before-intent");
            stopped=mode;if(mode.equals("acc"))acc++;else ecg++;
            if(throwsAfterStop)throw new Exception("partial-effect");
        }
    }
    static JSONObject command(PolarProjectedStopActions adapter,Owner owner,String operation) throws Exception {
        JSONObject state=adapter.project(owner),result=new JSONObject();
        for(String field:new String[]{"pid","runtime_generation","owner_epoch","action_revision","selected_sensor","connected_sensor","acc_revision","ecg_revision"})result.put(field,state.get(field));
        return result.put("schema",PolarProjectedStopActions.SCHEMA).put("operation",operation)
            .put("operation_id",state.getString(operation+"_operation_id"));
    }
    interface Attempt{void run()throws Exception;}
    static void deny(Attempt attempt)throws Exception{boolean denied=false;try{attempt.run();}catch(Exception expected){denied=true;}check(denied,"must deny");}
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        final Owner original=new Owner();final PolarProjectedStopActions adapter=new PolarProjectedStopActions();
        final JSONObject request=command(adapter,original,"stop_ecg");JSONObject receipt=adapter.execute(request,original);
        check(original.calls==1&&original.stopped.equals("ecg")&&original.acc==3&&original.ecg==6,"explicit stream ignores ambient ACC mode");
        check(receipt.getString("dispatch_status").equals("dispatched")&&receipt.getString("effect_status").equals("not-confirmed"),"dispatch is no ACK");
        String pendingFeedback=PolarProjectedStopActions.feedback("stop_ecg",new JSONObject().put("action_receipt",receipt));
        check(pendingFeedback.contains("requested")&&pendingFeedback.contains("pending")&&!pendingFeedback.contains("stopped"),"UI dispatch never labels stream stopped");
        check(PolarProjectedStopActions.feedback("stop_acc",new JSONObject().put("dispatch_status","rejected").put("reason","polar-owner-closed")).contains("owner is closed"),"closed owner feedback visible");
        check(PolarProjectedStopActions.feedback("stop_acc",new JSONObject().put("dispatch_status","rejected").put("reason","native-library-unavailable")).contains("not ready"),"native prerequisite feedback visible");
        check(PolarProjectedStopActions.feedback("stop_acc",new JSONObject().put("dispatch_status","rejected").put("reason","consumed-or-stale-action-revision")).contains("rejected"),"stale offer feedback visible");
        check(PolarProjectedStopActions.feedback("stop_acc",new JSONObject().put("action_receipt",new JSONObject().put("dispatch_status","outcome-unknown"))).contains("unknown"),"unknown feedback visible");
        String immutable=receipt.toString();receipt.put("dispatch_status","tampered");
        original.generation=9;original.closed=true;
        check(adapter.execute(request,original).toString().equals(immutable)&&original.calls==1,"identical latest duplicate retains immutable original, no effect");
        final JSONObject swapped=new JSONObject(request.toString()).put("operation","stop_acc");deny(()->adapter.execute(swapped,original));
        final Owner fresh=new Owner();
        for(String field:new String[]{"pid","runtime_generation","action_revision","acc_revision","ecg_revision"}){
            PolarProjectedStopActions policy=new PolarProjectedStopActions();JSONObject drift=command(policy,fresh,"stop_acc").put(field,99);
            deny(()->policy.execute(drift,fresh));drift.put(field,true);deny(()->policy.execute(drift,fresh));
            drift.put(field,1.0);deny(()->policy.execute(drift,fresh));
        }
        for(String field:new String[]{"selected_sensor","connected_sensor"}){
            PolarProjectedStopActions policy=new PolarProjectedStopActions();JSONObject drift=command(policy,fresh,"stop_acc").put(field,"polar-222222222222");
            deny(()->policy.execute(drift,fresh));
        }
        final PolarProjectedStopActions birth=new PolarProjectedStopActions();final JSONObject oldBirth=command(birth,fresh,"stop_acc");
        fresh.epoch="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";deny(()->birth.execute(oldBirth,fresh));
        check(fresh.calls==0,"same PID/generation but different owner lifetime denies");
        final PolarProjectedStopActions guarded=new PolarProjectedStopActions();final JSONObject valid=command(guarded,fresh,"stop_acc");
        final JSONObject extra=new JSONObject(valid.toString()).put("extra",true);deny(()->guarded.execute(extra,fresh));
        fresh.main=false;deny(()->guarded.execute(valid,fresh));fresh.main=true;fresh.closed=true;deny(()->guarded.execute(valid,fresh));fresh.closed=false;
        check(fresh.calls==0,"all denial controls precede owner effects");
        fresh.throwsBeforeIntent=true;JSONObject unknown=guarded.execute(valid,fresh);
        check(unknown.getString("dispatch_status").equals("outcome-unknown")&&fresh.acc==3,"failure before stream change is unknown, not retryable");
        guarded.execute(valid,fresh);check(fresh.calls==1,"unknown duplicate never reissued even without stream revision change");
        fresh.throwsBeforeIntent=false;
        final JSONObject next=command(guarded,fresh,"stop_ecg");guarded.execute(next,fresh);
        deny(()->guarded.execute(valid,fresh));check(fresh.calls==2,"older unknown request permanently stale after a later admitted action");
        final JSONObject forged=new JSONObject(next.toString()).put("action_revision",2);
        deny(()->guarded.execute(forged,fresh));check(fresh.calls==2,"old operation ID cannot move to a new revision");
        final Owner partial=new Owner();partial.throwsAfterStop=true;
        final PolarProjectedStopActions partialAdapter=new PolarProjectedStopActions();final JSONObject partialRequest=command(partialAdapter,partial,"stop_acc");
        check(partialAdapter.execute(partialRequest,partial).getString("dispatch_status").equals("outcome-unknown")&&partial.acc==4,"post-intent partial failure stays unknown");
        partialAdapter.execute(partialRequest,partial);check(partial.calls==1,"post-intent unknown duplicate never reissued");
        JSONObject status=new JSONObject().put("schema",PolarProjectedStopActions.SCHEMA).put("operation","status");
        check(guarded.execute(status,fresh).getString("effect_status").equals("read-only")&&fresh.calls==2,"status has no effect");
        Owner active=new Owner();PolarProjectedStopActions continuing=new PolarProjectedStopActions();
        final JSONObject first=command(continuing,active,"stop_acc");continuing.execute(first,active);
        for(int i=1;i<1024;i++)continuing.execute(command(continuing,active,"stop_acc"),active);
        check(active.calls==1024,"ordinary control remains available beyond former128 cap");
        deny(()->continuing.execute(first,active));check(active.calls==1024,"old consumed IDs denied with constant-space highwater");
        System.out.println("PASS production projected Polar scoped-ID/highwater/immutable latest/partial-unknown/no-replay controls including1024 actions");
    }
}
