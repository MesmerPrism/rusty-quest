// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.nativecapture;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** RMANVID1v1 protocol implementation, not copied into the MIT browser host. */
final class NativeVideoWire {
    static final int MAX_PAYLOAD=4*1024*1024-12,MAX_CONFIG=256*1024-12;
    final DataOutputStream out;
    byte[] sps,pps,lastConfig;
    boolean header,configured,key,finishAttempted,finished;
    long lastPts=-1;
    volatile long videoPackets,configPackets,duplicatePtsDropped,preKeyDropped;
    NativeVideoWire(OutputStream out){this.out=new DataOutputStream(out);}
    void header(String invocation,String generation)throws IOException {
        if(header||finishAttempted)throw new IllegalStateException("one-header-before-finish");
        NativeBrowserOptions.hex(invocation);NativeBrowserOptions.hex(generation);
        byte[] metadata=("{\"invocation_id\":\""+invocation+"\",\"generation\":\""+generation+"\"}").getBytes("UTF-8");
        out.writeBytes("RMANVID1");out.writeInt(1);out.writeInt(1);out.writeInt(1280);out.writeInt(1280);out.writeInt(0);out.writeInt(metadata.length);out.write(metadata);out.flush();header=true;
    }
    static byte[] bytes(ByteBuffer b){ByteBuffer c=b.duplicate();byte[] v=new byte[c.remaining()];c.get(v);return v;}
    static int start(byte[] b,int i){if(i+3<=b.length&&b[i]==0&&b[i+1]==0&&b[i+2]==1)return 3;if(i+4<=b.length&&b[i]==0&&b[i+1]==0&&b[i+2]==0&&b[i+3]==1)return 4;return 0;}
    static void nal(ByteArrayOutputStream out,byte[] b,int offset,int length){
        if(length<1||length>MAX_PAYLOAD||offset<0||offset+length>b.length||(b[offset]&128)!=0||(b[offset]&31)==0||(b[offset]&31)>23)throw new IllegalArgumentException("bounded-avc-nal");
        out.write(0);out.write(0);out.write(0);out.write(1);out.write(b,offset,length);
    }
    static int u16(byte[] b,int i){if(i+2>b.length)throw new IllegalArgumentException("short-avcc");return((b[i]&255)<<8)|(b[i+1]&255);}
    static byte[] annexB(byte[] b){
        if(b.length==0||b.length>MAX_PAYLOAD)throw new IllegalArgumentException("bounded-avc-data");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(start(b,0)>0){
            int p=0;
            while(p<b.length){int n=start(b,p);if(n==0)throw new IllegalArgumentException("annexb-start");int a=p+n,z=a;while(z<b.length&&start(b,z)==0)z++;nal(out,b,a,z-a);p=z;}
        }else if((b[0]&255)==1&&b.length>=7){
            // AVCDecoderConfigurationRecord CSD; actual SPS/PPS only.
            int p=6,count=b[5]&31;
            if(count==0)throw new IllegalArgumentException("no-avcc-sps");
            for(int i=0;i<count;i++){int n=u16(b,p);p+=2;nal(out,b,p,n);p+=n;}
            if(p>=b.length)throw new IllegalArgumentException("no-avcc-pps");count=b[p++]&255;
            for(int i=0;i<count;i++){int n=u16(b,p);p+=2;nal(out,b,p,n);p+=n;}
            if(p!=b.length)throw new IllegalArgumentException("avcc-trailing-unsupported");
        }else{
            int p=0;while(p<b.length){if(p+4>b.length)throw new IllegalArgumentException("short-avc-length");long n=((long)(b[p]&255)<<24)|((long)(b[p+1]&255)<<16)|((long)(b[p+2]&255)<<8)|(b[p+3]&255);p+=4;if(n<1||n>b.length-p)throw new IllegalArgumentException("avc-length");nal(out,b,p,(int)n);p+=(int)n;}
        }
        byte[] value=out.toByteArray();if(value.length>MAX_PAYLOAD)throw new IllegalArgumentException("annexb-overflow");return value;
    }
    static List<byte[]> nals(byte[] b){
        List<byte[]> list=new ArrayList<>();int p=0;while(p<b.length){int n=start(b,p);if(n==0)throw new IllegalArgumentException("canonical-nal-start");int z=p+n;while(z<b.length&&start(b,z)==0)z++;list.add(Arrays.copyOfRange(b,p+n,z));p=z;}return list;
    }
    void learn(byte[] b){for(byte[] n:nals(b)){int type=n[0]&31;if(type==7)sps=n;else if(type==8)pps=n;}}
    void csd(byte[] b){learn(annexB(b));}
    boolean accessUnit(long pts,byte[] bytes,boolean configOnly)throws IOException {
        if(!header)throw new IllegalStateException("header-first");
        byte[] b=annexB(bytes);learn(b);if(configOnly)return false;
        boolean idr=false,vcl=false;for(byte[] n:nals(b)){int t=n[0]&31;idr|=t==5;vcl|=t==1||t==5;}
        if(!vcl)return false;
        if(pts<0||pts>9007199254740991L)throw new IllegalArgumentException("pts-bounds");
        if(pts<=lastPts){duplicatePtsDropped++;return false;}
        if(sps==null||pps==null){if(idr)throw new IllegalStateException("real-sps-pps-required-before-key");preKeyDropped++;return false;}
        ByteArrayOutputStream c=new ByteArrayOutputStream();nal(c,sps,0,sps.length);nal(c,pps,0,pps.length);byte[] config=c.toByteArray();
        boolean changed=!Arrays.equals(lastConfig,config);
        if((!key||changed)&&!idr){preKeyDropped++;return false;}
        if(changed||!configured){packet(0,2,config);lastConfig=config;configured=true;key=false;configPackets++;}
        if(!key&&!idr)throw new IllegalStateException("key-required");
        packet(pts,idr?1:0,b);key|=idr;lastPts=pts;videoPackets++;return true;
    }
    void packet(long pts,int flags,byte[] data)throws IOException {
        if(finishAttempted)throw new IllegalStateException("no-writes-after-wire-finish");
        if(data.length<1||data.length>MAX_PAYLOAD||((flags&2)!=0&&data.length>MAX_CONFIG))throw new IllegalArgumentException("wire-payload-bound");
        out.writeLong(pts);out.writeInt(flags);out.writeInt(data.length);out.writeLong(0);out.writeLong(0);out.write(data);out.flush();
    }
    void finish()throws IOException {
        if(finishAttempted)throw new IllegalStateException("wire-finish-no-replay");
        finishAttempted=true;
        // All codec/writer work must already be joined. Closing the transport
        // flushes residual Base64 padding and then the owned stdout descriptor.
        out.close();finished=true;
    }
}
