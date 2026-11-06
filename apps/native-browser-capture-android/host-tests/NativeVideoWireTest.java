// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.nativecapture;
import java.io.*;
import java.util.*;

/** Synthetic protocol/ownership tests; no Android execution or H264 decode claim. */
public final class NativeVideoWireTest {
    static final String ID="0123456789abcdef0123456789abcdef",GEN="abcdef0123456789abcdef0123456789";
    static void yes(boolean v){if(!v)throw new AssertionError();}
    static void rejects(Runnable r){try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError("expected-refusal");}
    static byte[] b(int...v){byte[] r=new byte[v.length];for(int i=0;i<v.length;i++)r[i]=(byte)v[i];return r;}
    public static void main(String[] args)throws Exception {
        ByteArrayOutputStream stream=new ByteArrayOutputStream();NativeVideoWire w=new NativeVideoWire(stream);w.header(ID,GEN);
        rejects(()->{try{w.header(ID,GEN);}catch(IOException e){throw new RuntimeException(e);}});
        byte[] sps=b(0,0,0,1,0x67,0x42,0,30),pps=b(0,0,1,0x68,1),key=b(0,0,0,1,0x65,1,2),delta=b(0,0,1,0x41,2,3);
        w.csd(sps);w.csd(pps);yes(!w.accessUnit(9,delta,false));yes(w.accessUnit(10,key,false));yes(!w.accessUnit(10,delta,false));yes(w.accessUnit(11,delta,false));
        yes(w.configPackets==1&&w.videoPackets==2&&w.duplicatePtsDropped==1&&w.preKeyDropped==1);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(stream.toByteArray()));byte[] magic=new byte[8];in.readFully(magic);yes(Arrays.equals(magic,"RMANVID1".getBytes("US-ASCII")));
        yes(in.readInt()==1&&in.readInt()==1&&in.readInt()==1280&&in.readInt()==1280&&in.readInt()==0);
        byte[] meta=new byte[in.readInt()];in.readFully(meta);String json=new String(meta,"UTF-8");yes(json.equals("{\"invocation_id\":\""+ID+"\",\"generation\":\""+GEN+"\"}"));
        long[] pts={0,10,11};int[] flags={2,1,0};
        for(int i=0;i<3;i++){yes(in.readLong()==pts[i]&&in.readInt()==flags[i]);int n=in.readInt();yes(n>0&&n<=NativeVideoWire.MAX_PAYLOAD);yes(in.readLong()==0&&in.readLong()==0);byte[] packet=new byte[n];in.readFully(packet);yes(NativeVideoWire.start(packet,0)==4);if(i==0){List<byte[]> ns=NativeVideoWire.nals(packet);yes(ns.size()==2&&(ns.get(0)[0]&31)==7&&(ns.get(1)[0]&31)==8);}}
        yes(in.read()==-1);System.out.println("PASS big-endian-header/config-before-key/monotone-PTS/reserved-zero");
        yes(Arrays.equals(NativeVideoWire.annexB(b(0,0,0,3,0x65,1,2)),key));
        byte[] avcc=b(1,0x42,0,30,255,225,0,4,0x67,0x42,0,30,1,0,2,0x68,1);yes(NativeVideoWire.nals(NativeVideoWire.annexB(avcc)).size()==2);
        rejects(()->NativeVideoWire.annexB(b(0,0,0,8,0x65,1)));rejects(()->NativeVideoWire.annexB(b(0,0,0,1,0)));rejects(()->NativeVideoWire.annexB(b()));System.out.println("PASS AnnexB/AVCC/length-prefix-conversion-and-malformed-refusals");
        NativeVideoWire noConfig=new NativeVideoWire(new ByteArrayOutputStream());noConfig.header(ID,GEN);
        rejects(()->{try{noConfig.accessUnit(1,key,false);}catch(IOException e){throw new RuntimeException(e);}});
        rejects(()->{try{w.accessUnit(-1,key,false);}catch(IOException e){throw new RuntimeException(e);}});System.out.println("PASS no-fabricated-config/negative-PTS-refusal");
        VideoRequestOwner20 owner=new VideoRequestOwner20();owner.beforeCall();yes(!owner.safeToClose(0));owner.callReturned(true);owner.beforeStop();yes(!owner.safeToClose(100000));owner.stopReturned(true,100);yes(!owner.safeToClose(2099)&&owner.safeToClose(2100));rejects(owner::beforeStop);
        VideoRequestOwner20 unknown=new VideoRequestOwner20();unknown.beforeCall();unknown.beforeStop();yes(!unknown.safeToClose(Long.MAX_VALUE));unknown.originalRemoteBinderDied();yes(unknown.safeToClose(0));System.out.println("PASS one-global-stop/grace/unknown-held/original-death");
        String[] argv={"--driver-apk",new File("driver.apk").getCanonicalPath(),"--driver-sha256",NativeBrowserOptions.DRIVER_SHA,"--invocation",ID,"--generation",GEN,"--stop-nonce",ID};NativeBrowserOptions options=new NativeBrowserOptions(argv);yes(options.durationMs==0&&options.stopLine("STOP "+ID)&&!options.stopLine("STOP "+GEN));
        rejects(()->NativeBrowserOptions.hex("ABC"));String[] extra=Arrays.copyOf(argv,argv.length+2);extra[argv.length]="--arbitrary";extra[argv.length+1]="x";try{new NativeBrowserOptions(extra);throw new AssertionError();}catch(IllegalArgumentException expected){}
        yes(options.wireEncoding.equals("raw"));
        String[] encodedArgs=Arrays.copyOf(argv,argv.length+2);encodedArgs[argv.length]="--wire-encoding";encodedArgs[argv.length+1]="base64";
        yes(new NativeBrowserOptions(encodedArgs).wireEncoding.equals("base64"));
        String[] finite=Arrays.copyOf(encodedArgs,encodedArgs.length+2);finite[encodedArgs.length]="--duration-ms";finite[encodedArgs.length+1]="2000";yes(new NativeBrowserOptions(finite).durationMs==2000);
        encodedArgs[argv.length+1]="base64url";try{new NativeBrowserOptions(encodedArgs);throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("PASS closed-hex32-args/default-await-stop/exact-nonce");
        yes(options.driverSha.equals(NativeBrowserOptions.DRIVER_SHA)&&options.driverBytes==37386220);
        String[] qArgs=argv.clone();qArgs[3]=NativeBrowserOptions.Q_DRIVER_SHA;NativeBrowserOptions qProfile=new NativeBrowserOptions(qArgs);
        yes(qProfile.driverSha.equals(NativeBrowserOptions.Q_DRIVER_SHA)&&qProfile.driverBytes==36415479);
        qArgs[3]="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        try{new NativeBrowserOptions(qArgs);throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("PASS exact-two-Driver-SHA-and-size-profiles/no-third-profile");
        // Execute the production wire through the JDK's streaming standard
        // Base64 encoder. Android's NO_WRAP adapter is API34-compiled separately;
        // this is a host transport model, not an Android runtime claim.
        Set<Integer> residues=new HashSet<>();byte[] encodedFixture=null;
        for(int extraBytes=0;extraBytes<4;extraBytes++){
            ByteArrayOutputStream plainOut=new ByteArrayOutputStream(),encodedOut=new ByteArrayOutputStream();
            NativeVideoWire plain=new NativeVideoWire(plainOut),encoded=new NativeVideoWire(Base64.getEncoder().wrap(encodedOut));
            byte[] frame=Arrays.copyOf(key,key.length+extraBytes);for(int i=key.length;i<frame.length;i++)frame[i]=2;
            for(NativeVideoWire target:new NativeVideoWire[]{plain,encoded}){target.header(ID,GEN);target.csd(sps);target.csd(pps);yes(target.accessUnit(10,frame,false));yes(target.accessUnit(11,delta,false));target.finish();yes(target.finished);rejects(()->{try{target.finish();}catch(IOException e){throw new RuntimeException(e);}});rejects(()->{try{target.packet(12,0,delta);}catch(IOException e){throw new RuntimeException(e);}});}
            byte[] text=encodedOut.toByteArray();residues.add(plainOut.size()%3);yes(Arrays.equals(Base64.getDecoder().decode(text),plainOut.toByteArray()));
            for(byte character:text)yes(character!='\r'&&character!='\n');
            String wrapped=" \r\n"+new String(text,"US-ASCII").replace("A","A\r\n")+"\r\n ";yes(Arrays.equals(Base64.getMimeDecoder().decode(wrapped),plainOut.toByteArray()));
            if(extraBytes==0)encodedFixture=text;
        }
        yes(residues.size()==3);yes(Arrays.equals(Base64.getDecoder().decode(encodedFixture),stream.toByteArray()));System.out.println("PASS streaming-Base64/no-wrap/all-padding-residues/whitespace/finish-before-terminal");
        if(args.length>=1)try(FileOutputStream f=new FileOutputStream(args[0])){f.write(stream.toByteArray());}
        if(args.length>=2)try(FileOutputStream f=new FileOutputStream(args[1])){f.write(encodedFixture);}
    }
}
