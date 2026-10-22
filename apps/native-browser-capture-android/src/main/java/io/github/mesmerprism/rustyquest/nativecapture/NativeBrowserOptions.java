// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.nativecapture;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

final class NativeBrowserOptions {
    static final String DRIVER_SHA="ac164cf7d9394cc07006bb3977cc2090287231f019c24be006959ee8a537237e";
    static final String Q_DRIVER_SHA="01b4a76ae81f11d824499d4830e4cc8788c58062c29015c50816c61530b910a5";
    final File driver;
    final String invocation,generation,stopNonce,wireEncoding,driverSha;
    final long durationMs,driverBytes;
    NativeBrowserOptions(String[] argv)throws Exception {
        Map<String,String> m=new HashMap<>();
        for(int i=0;i<argv.length;i+=2) {
            if(i+1>=argv.length||!argv[i].startsWith("--")||m.put(argv[i].substring(2),argv[i+1])!=null)throw new IllegalArgumentException("closed-paired-args");
        }
        String[] keys={"driver-apk","driver-sha256","invocation","generation","stop-nonce"};
        for(String k:keys)if(!m.containsKey(k))throw new IllegalArgumentException("missing-"+k);
        if(m.size()!=5+(m.containsKey("duration-ms")?1:0)+(m.containsKey("wire-encoding")?1:0))throw new IllegalArgumentException("exact-closed-source-args");
        driverSha=m.get("driver-sha256");
        if(DRIVER_SHA.equals(driverSha))driverBytes=37386220;
        else if(Q_DRIVER_SHA.equals(driverSha))driverBytes=36415479;
        else throw new IllegalArgumentException("exact-two-driver-profiles-only");
        invocation=hex(m.get("invocation"));generation=hex(m.get("generation"));stopNonce=hex(m.get("stop-nonce"));
        wireEncoding=m.containsKey("wire-encoding")?m.get("wire-encoding"):"raw";
        if(!"raw".equals(wireEncoding)&&!"base64".equals(wireEncoding))throw new IllegalArgumentException("wire-encoding-raw-or-base64");
        File f=new File(m.get("driver-apk"));
        if(!f.isAbsolute()||!f.getAbsoluteFile().equals(f.getCanonicalFile()))throw new IllegalArgumentException("canonical-absolute-installed-driver");
        driver=f;
        durationMs=m.containsKey("duration-ms")?Long.parseLong(m.get("duration-ms")):0;
        if(m.containsKey("duration-ms")&&(durationMs<2000||durationMs>60000))throw new IllegalArgumentException("duration-ms-2000-through-60000");
    }
    static String hex(String s){if(s==null||!s.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("hex32-identity");return s;}
    boolean stopLine(String line){return ("STOP "+stopNonce).equals(line);}
}
