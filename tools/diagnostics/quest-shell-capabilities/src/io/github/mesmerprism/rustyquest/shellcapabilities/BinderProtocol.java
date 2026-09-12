package io.github.mesmerprism.rustyquest.shellcapabilities;

import android.os.ParcelFileDescriptor;
import java.io.*;
import java.security.MessageDigest;

final class BinderProtocol {
    static final String DESCRIPTOR = "rusty.quest.shell.capabilities.lease";
    static final int IDENTIFY=1, APP_TO_SHELL_FD=2, SHELL_TO_APP_FD=3, STATUS=4, STOP=5, BLE_FRAME=6, BLE_READY=7;
    static final int SMALL=4096, LARGE=4*1024*1024;
    static String readPattern(ParcelFileDescriptor p,int size)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(p)){byte[]b=new byte[8192];int at=0;while(at<size){int n=in.read(b,0,Math.min(b.length,size-at));if(n<1)throw new EOFException("pattern_short");for(int i=0;i<n;i++)if(b[i]!=ProbeCore.pattern(at+i))throw new Exception("pattern_mismatch");d.update(b,0,n);at+=n;}if(in.read()!=-1)throw new Exception("pattern_trailing");}return ProbeCore.hex(d.digest());}
    static void writePattern(ParcelFileDescriptor p,int size)throws Exception{try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(p)){byte[]b=new byte[8192];int at=0;while(at<size){int n=Math.min(b.length,size-at);for(int i=0;i<n;i++)b[i]=ProbeCore.pattern(at+i);out.write(b,0,n);at+=n;}out.flush();}}
}
