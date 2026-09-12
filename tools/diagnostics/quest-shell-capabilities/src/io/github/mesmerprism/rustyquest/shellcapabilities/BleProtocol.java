package io.github.mesmerprism.rustyquest.shellcapabilities;
import javax.crypto.Mac;import javax.crypto.spec.SecretKeySpec;import java.security.MessageDigest;
final class BleProtocol{
 static final int FRAME=20,VERSION=1,NOOP=1,OFF=2,RESUME=3,READY=0x10,BAD_MAC=0xe1,REPLAY=0xe2,PHASE=0xe3,BASELINE=0xe4,EXPIRED=0xe5,INTERNAL=0xef;
 static byte[] token(String hex){return decode(hex,16);}static byte[] secret(String hex){return decode(hex,32);}static int seq(byte[]f){return((f[2]&255)<<8)|(f[3]&255);}
 static boolean valid(byte[]f,byte[]token,byte[]secret,char direction)throws Exception{if(f==null||f.length!=FRAME||f[0]!=VERSION)return false;byte[]want=tag(f,token,secret,direction);return MessageDigest.isEqual(java.util.Arrays.copyOfRange(f,4,20),want);}
 static byte[] reply(int outcome,int seq,byte[]token,byte[]secret)throws Exception{byte[]f=new byte[FRAME];f[0]=VERSION;f[1]=(byte)outcome;f[2]=(byte)(seq>>>8);f[3]=(byte)seq;byte[]tag=tag(f,token,secret,'R');System.arraycopy(tag,0,f,4,16);return f;}
 static byte[] tag(byte[]f,byte[]token,byte[]secret,char direction)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(secret,"HmacSHA256"));m.update((byte)direction);m.update(token);m.update(f,0,4);return java.util.Arrays.copyOf(m.doFinal(),16);}
 static byte[] decode(String h,int n){if(h==null||!h.matches("[0-9a-f]{"+(n*2)+"}"))throw new IllegalArgumentException("hex");byte[]b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16);return b;}
}
