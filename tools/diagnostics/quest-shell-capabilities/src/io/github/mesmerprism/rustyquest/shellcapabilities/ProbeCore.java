package io.github.mesmerprism.rustyquest.shellcapabilities;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;

final class ProbeCore {
  static final int BULK=4*1024*1024, RTT=50, MAX_LINE=512;
  static void requireToken(String s){if(s==null||!s.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("token");}
  static byte pattern(int i){return (byte)((i*31+7)&255);}
  static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
  static String digestPattern(int n)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[8192];int at=0;while(at<n){int z=Math.min(b.length,n-at);for(int i=0;i<z;i++)b[i]=pattern(at+i);d.update(b,0,z);at+=z;}return hex(d.digest());}
  static void writeLine(OutputStream out,String s)throws IOException{out.write((s+"\n").getBytes("UTF-8"));out.flush();}
  static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<MAX_LINE;i++){int c=in.read();if(c<0)throw new EOFException();if(c=='\n')return b.toString("UTF-8");if(c<' '||c>126)throw new IOException("line_char");b.write(c);}throw new IOException("line_long");}
  static void serve(Socket s,String token)throws Exception{s.setSoTimeout(30000);InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();for(;;){String q=readLine(in);String[] f=q.split("\\|",-1);if(f.length==4&&f[0].equals("ECHO")&&f[1].equals(token)){writeLine(out,q);continue;}if(f.length==4&&f[0].equals("BULK")&&f[1].equals(token)){int n=Integer.parseInt(f[2]);if(n!=BULK||!f[3].equals(digestPattern(n)))throw new IOException("bulk_header");MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[8192];int at=0;while(at<n){int z=in.read(b,0,Math.min(b.length,n-at));if(z<1)throw new EOFException();for(int i=0;i<z;i++)if(b[i]!=pattern(at+i))throw new IOException("bulk_pattern");d.update(b,0,z);at+=z;}writeLine(out,"BULK_OK|"+token+"|"+n+"|"+hex(d.digest()));return;}throw new IOException("frame");}}
  static String client(Socket s,String token)throws Exception{s.setSoTimeout(30000);InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();long sum=0;for(int i=0;i<RTT;i++){long a=System.nanoTime();String q="ECHO|"+token+"|"+i+"|p";writeLine(out,q);if(!readLine(in).equals(q))throw new IOException("echo");sum+=System.nanoTime()-a;}String hash=digestPattern(BULK);writeLine(out,"BULK|"+token+"|"+BULK+"|"+hash);byte[] b=new byte[8192];int at=0;while(at<BULK){int z=Math.min(b.length,BULK-at);for(int i=0;i<z;i++)b[i]=pattern(at+i);out.write(b,0,z);at+=z;}out.flush();String want="BULK_OK|"+token+"|"+BULK+"|"+hash;if(!readLine(in).equals(want))throw new IOException("bulk_ack");return "{\"ok\":true,\"rtt_samples\":50,\"rtt_mean_us\":"+(sum/RTT/1000)+",\"bytes\":"+BULK+",\"sha256\":\""+hash+"\"}";}
  static String quote(String value){
    if(value==null)return "null";
    StringBuilder out=new StringBuilder(value.length()+2).append('"');
    for(int i=0;i<value.length();i++){
      char c=value.charAt(i);
      if(c=='"'||c=='\\')out.append('\\').append(c);
      else if(c=='\b')out.append("\\b");
      else if(c=='\f')out.append("\\f");
      else if(c=='\n')out.append("\\n");
      else if(c=='\r')out.append("\\r");
      else if(c=='\t')out.append("\\t");
      else if(c<0x20)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
      else out.append(c);
    }
    return out.append('"').toString();
  }
}
