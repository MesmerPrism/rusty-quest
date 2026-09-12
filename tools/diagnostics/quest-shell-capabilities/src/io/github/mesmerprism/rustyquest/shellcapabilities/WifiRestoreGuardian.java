package io.github.mesmerprism.rustyquest.shellcapabilities;
public final class WifiRestoreGuardian{
 public static void main(String[]a)throws Exception{if(android.os.Process.myUid()!=2000||a.length!=2||!"30000".equals(a[1]))System.exit(2);ProbeCore.requireToken(a[0]);Thread.sleep(30000);Process p=new ProcessBuilder("/system/bin/cmd","wifi","set-wifi-enabled","enabled").redirectErrorStream(true).start();try{p.getOutputStream().close();if(!p.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();System.exit(4);}System.exit(p.exitValue()==0?0:3);}finally{try{p.getInputStream().close();}catch(Exception ignored){}try{p.getErrorStream().close();}catch(Exception ignored){}}}
}
