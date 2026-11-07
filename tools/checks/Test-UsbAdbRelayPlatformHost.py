"""Actual capability and numeric endpoint adapter bodies; Android/socket leaves modeled.

Does not grant route authority, open sockets, build an APK or contact a device.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def block(text, marker):
    start = text.index(marker)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--java-home', type=Path, required=True)
    a = p.parse_args()
    root, out = a.root.resolve(), a.output.resolve()
    out.relative_to(root / 'target')
    out.mkdir(parents=True, exist_ok=False)
    java = root / 'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex'
    files = {name:(java/(name+'.java')).read_bytes() for name in ('FrozenEffectiveInputs','EmbeddedDuplexPlatform','EmbeddedDuplexResources')}
    capability = block(files['FrozenEffectiveInputs'].decode('utf-8'), 'static boolean developmentUsbRelayCapability(')
    platform = files['EmbeddedDuplexPlatform'].decode('utf-8')
    resources = files['EmbeddedDuplexResources'].decode('utf-8')
    platform = '\n'.join(block(platform, m) for m in ('private static InetAddress numericIpv4(String address)', 'private static InetAddress numericIpv4(String address, boolean developmentUsbRelay)'))
    resources = '\n'.join(block(resources, m) for m in ('private static String numericIpv4(String host)', 'private static String numericIpv4(String host, boolean usbRelay)'))
    harness = out/'UsbRelayHost.java'
    harness.write_text('''import java.net.InetAddress;
import android.content.Context;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.BuildConfig;
public final class UsbRelayHost {
CAPABILITY
static final class Control {CONTROL}
static final class Media {MEDIA}
interface Attempt {void run() throws Exception;}
static void refuses(Attempt a)throws Exception{try{a.run();}catch(Exception expected){return;}throw new AssertionError("unexpected endpoint acceptance");}
static void check(boolean v){if(!v)throw new AssertionError("capability mismatch");}
public static void main(String[] args)throws Exception {
 Context c=new Context();
 BuildConfig.DEBUG=true;BuildConfig.EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED=true;
 c.resources.id=1;c.resources.value=1;check(developmentUsbRelayCapability(c));
 BuildConfig.DEBUG=false;check(!developmentUsbRelayCapability(c));
 BuildConfig.DEBUG=true;BuildConfig.EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED=false;check(!developmentUsbRelayCapability(c));
 BuildConfig.EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED=true;
 c.resources.id=0;check(!developmentUsbRelayCapability(c));c.resources.id=1;
 for(int v:new int[]{-1,0,2}){c.resources.value=v;check(!developmentUsbRelayCapability(c));}
 c.resources.value=1;c.resources.fail=true;check(!developmentUsbRelayCapability(c));c.resources.fail=false;
 check(!developmentUsbRelayCapability(null));
 check(Control.numericIpv4("127.0.0.1",true).isLoopbackAddress());check(Media.numericIpv4("127.0.0.1",true).equals("127.0.0.1"));
 refuses(()->Control.numericIpv4("127.0.0.1"));refuses(()->Media.numericIpv4("127.0.0.1"));
 for(String bad:new String[]{"127.0.0.2","127.1","localhost","0177.0.0.1","127.0.0.1 ","0.0.0.0","10.0.0.1","224.0.0.1","255.255.255.255","::1"}){
  refuses(()->Control.numericIpv4(bad,true));refuses(()->Media.numericIpv4(bad,true));
 }
 check(Control.numericIpv4("10.20.0.11").getHostAddress().equals("10.20.0.11"));check(Media.numericIpv4("10.20.0.11").equals("10.20.0.11"));
 System.out.println("PASS actual capability and control/media endpoint bodies; release/missing/wrong/throwing resource fail closed; LAN defaults and literal USB mode; modeled Android leaves, zero sockets");
}}
'''.replace('CAPABILITY',capability).replace('CONTROL',platform).replace('MEDIA',resources), encoding='utf-8')
    context = out/'Context.java'
    context.write_text('''package android.content;
public final class Context {
 public final Resources resources=new Resources();public Resources getResources(){return resources;}public String getPackageName(){return "host.fixture";}
 public static final class Resources{public int id,value;public boolean fail;
 public int getIdentifier(String name,String kind,String pkg){if(!name.equals("rusty_quest_embedded_duplex_usb_adb_relay_enabled")||!kind.equals("integer")||!pkg.equals("host.fixture"))throw new AssertionError("wrong capability resource");return id;}
 public int getInteger(int ignored){if(fail)throw new IllegalArgumentException("wrong resource type");return value;}}
}
''',encoding='utf-8')
    config = out/'BuildConfig.java'
    config.write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel;public final class BuildConfig {public static boolean DEBUG,EMBEDDED_DUPLEX_PRODUCT_INPUTS_ENABLED;}',encoding='utf-8')
    runs = []
    for name, argv in [('compile',[str(a.java_home/'bin/javac.exe'),'--release','17','-d',str(out),str(harness),str(context),str(config)]), ('controls',[str(a.java_home/'bin/java.exe'),'-cp',str(out),'UsbRelayHost'])]:
        r = subprocess.run(argv,capture_output=True,timeout=60)
        (out/(name+'.stdout')).write_bytes(r.stdout)
        (out/(name+'.stderr')).write_bytes(r.stderr)
        runs.append(dict(name=name,exit_code=r.returncode))
        if r.returncode: break
    unchanged = all((java/(name+'.java')).read_bytes()==contents for name,contents in files.items())
    report = dict(passed=unchanged and len(runs)==2 and all(r['exit_code']==0 for r in runs),runs=runs,source_unchanged=unchanged,
        source_pins=[dict(path=str(java/(name+'.java')),sha256=hashlib.sha256(contents).hexdigest()) for name,contents in files.items()],limits=__doc__)
    (out/'RESULT.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report))
    raise SystemExit(0 if report['passed'] else 1)


if __name__=='__main__':main()
