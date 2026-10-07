package io.github.mesmerprism.rustyquest.ble_control;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
public final class HubBleFramesTest {
    private static int cases;
    interface Action {void run()throws Exception;}
    static void denied(Action action)throws Exception{try{action.run();throw new AssertionError("accepted damaged carrier");}catch(IOException expected){cases++;}}
    static void check(boolean value){if(!value)throw new AssertionError();cases++;}
    public static void main(String[] args)throws Exception{
        byte[] actual="{\"state\":\"Own + Peer\",\"label\":\"münchen\"}".getBytes(StandardCharsets.UTF_8);
        for(int mtu:new int[]{23,247,517}){
            HubBleFrames assembly=new HubBleFrames();byte[] result=null;int offset=0;
            while(offset<actual.length){byte[] chunk=HubBleFrames.chunk(actual,1,offset,mtu);check(chunk.length<=Math.min(mtu-3,244));result=assembly.accept(chunk,mtu,1000+offset);offset+=chunk.length-HubBleFrames.HEADER;}
            check(Arrays.equals(actual,result));check(HubBleFrames.text(result).contains("münchen"));
            final HubBleFrames retained=assembly;final byte[] replay=HubBleFrames.chunk(actual,1,0,mtu);final int observedMtu=mtu;denied(new Action(){public void run()throws Exception{retained.accept(replay,observedMtu,1100);}});
            denied(new Action(){public void run()throws Exception{retained.accept(HubBleFrames.chunk(actual,2,0,observedMtu),observedMtu,1100);}});
        }
        for(final int field:new int[]{0,1,2,3,4,5,6,7}){final byte[] damage=HubBleFrames.chunk(actual,1,0,23);damage[field]^=1;denied(new Action(){public void run()throws Exception{HubBleFrames assembler=new HubBleFrames();assembler.accept(damage,23,1000);assembler.accept(HubBleFrames.chunk(actual,1,12,23),23,1001);}});}
        final HubBleFrames timed=new HubBleFrames();timed.accept(HubBleFrames.chunk(actual,1,0,23),23,1000);
        denied(new Action(){public void run()throws Exception{timed.accept(HubBleFrames.chunk(actual,1,12,23),23,11_000);}});
        final HubBleFrames rollback=new HubBleFrames();rollback.accept(HubBleFrames.chunk(actual,1,0,23),23,1000);
        denied(new Action(){public void run()throws Exception{rollback.accept(HubBleFrames.chunk(actual,1,12,23),23,999);}});
        denied(new Action(){public void run()throws Exception{HubBleFrames.chunk(new byte[16_385],1,0,247);}});
        denied(new Action(){public void run()throws Exception{HubBleFrames.text(new byte[]{(byte)0xc3,0});}});
        final HubBleFrames closed=new HubBleFrames();closed.close();denied(new Action(){public void run()throws Exception{closed.accept(HubBleFrames.chunk(actual,1,0,23),23,1000);}});
        System.out.println("HubBleFramesTest PASS "+cases+" production cases; no device/authority proof");
    }
}

