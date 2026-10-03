package io.github.mesmerprism.rustyquest.directp2p;
import java.util.*;
public final class FormationObservationHostTest {
    static int cases;static void check(boolean v){if(!v)throw new AssertionError();cases++;}interface Action{void run()throws Exception;}static void deny(Action a)throws Exception{try{a.run();throw new AssertionError("accepted");}catch(SecurityException expected){cases++;}}
    static Map<String,String> fact(){Map<String,String> v=new HashMap<>();v.put("run_id","test-run");v.put("run_token","1234567890abcdef1234567890abcdef");v.put("boot_id","12345678-1234-1234-1234-123456789012");v.put("pid","1234");v.put("pid_start_ticks","4567");v.put("network","DIRECT-rp-1234567890abcdef1234");v.put("owner_mac","02:12:34:56:78:9a");v.put("local_owner","true");v.put("observed_elapsed_ms","1000");return v;}
    public static void main(String[] args)throws Exception{
        Object actor=new Object();String run="test-run",token="1234567890abcdef1234567890abcdef";FormationObservationState.publish(actor,()->fact());
        check(FormationObservationState.read(2000,run,token,()->1001).equals(fact()));
        deny(()->FormationObservationState.read(1234,run,token,()->1001));deny(()->FormationObservationState.read(2000,"foreign",token,()->1001));deny(()->FormationObservationState.read(2000,run,"abcdef1234567890abcdef1234567890",()->1001));deny(()->FormationObservationState.read(2000,run,token,()->3001));deny(()->FormationObservationState.read(2000,run,token,()->999));
        FormationObservationState.clear(new Object());check(FormationObservationState.read(2000,run,token,()->1001).equals(fact()));FormationObservationState.clear(actor);deny(()->FormationObservationState.read(2000,run,token,()->1001));
        for(String field:Arrays.asList("boot_id","pid","pid_start_ticks","network","owner_mac","local_owner")){FormationObservationState.publish(actor,()->{Map<String,String> v=fact();v.put(field,"foreign");return v;});deny(()->FormationObservationState.read(2000,run,token,()->1001));}
        FormationObservationState.publish(actor,()->{FormationObservationState.clear(actor);return fact();});deny(()->FormationObservationState.read(2000,run,token,()->1001));
        FormationObservationState.publish(actor,()->{FormationObservationState.publish(new Object(),()->fact());return fact();});deny(()->FormationObservationState.read(2000,run,token,()->1001));
        FormationObservationState.publish(actor,()->null);deny(()->FormationObservationState.read(2000,run,token,()->1001));
        FormationObservationState.publish(actor,()->{Map<String,String> v=fact();v.put("extra","true");return v;});deny(()->FormationObservationState.read(2000,run,token,()->1001));
        System.out.println("Formation observation production state cases PASS "+cases);
    }
}
