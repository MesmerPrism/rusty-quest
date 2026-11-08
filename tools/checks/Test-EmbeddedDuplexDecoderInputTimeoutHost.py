"""Compile the real bounded debug selector with only generated BuildConfig modeled.

Host evidence only: no Android, decoder, network, or headset effects.
"""
import argparse,json,pathlib,subprocess

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repo-root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[2])
parser.add_argument('--java-home',type=pathlib.Path,required=True)
parser.add_argument('--output-root',type=pathlib.Path,required=True)
args=parser.parse_args();out=args.output_root.resolve();out.mkdir(parents=True,exist_ok=False)
source=args.repo_root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexDecoderInputTimeout.java'
stub=out/'BuildConfig.java';stub.write_text('package io.github.mesmerprism.rustyquest.spatial_camera_panel; public final class BuildConfig { public static final boolean DEBUG=false; }\n')
context=out/'Context.java';context.write_text('''package android.content;
public final class Context { private final android.content.res.Resources resources;
public Context(android.content.res.Resources r){resources=r;} public android.content.res.Resources getResources(){return resources;}
public String getPackageName(){return "test.app";} }''')
resources=out/'Resources.java';resources.write_text('''package android.content.res;
public final class Resources { private final int id,value; public Resources(int i,int v){id=i;value=v;}
public int getIdentifier(String name,String type,String pkg){if(!name.equals("rusty_quest_embedded_decoder_input_timeout_ms")||!type.equals("integer")||!pkg.equals("test.app"))throw new AssertionError("foreign resource");return id;}
public int getInteger(int actual){if(actual!=id)throw new IllegalArgumentException("wrong id");return value;} }''')
harness=out/'InputTimeoutHost.java';harness.write_text('''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
public final class InputTimeoutHost {
  static void need(boolean value,String message) { if(!value)throw new AssertionError(message); }
  public static void main(String[] args) {
    final int[] reads={0};
    need(EmbeddedDuplexDecoderInputTimeout.select(false,name->{reads[0]++;throw new AssertionError("release read");})==100,"release default");
    need(reads[0]==0,"release must never read property");
    need(EmbeddedDuplexDecoderInputTimeout.readForCurrentBuild()==100,"actual release entrypoint default");
    for(String value:new String[]{"100","250","500","1000"}) {
      need(EmbeddedDuplexDecoderInputTimeout.select(true,name->{need(name.equals(EmbeddedDuplexDecoderInputTimeout.PROPERTY),"closed property");return value;})==Integer.parseInt(value),"admitted budget");
    }
    for(String value:new String[]{"","0","-1","99","101","1001","60000","1e3","0x3e8","999999999999999999999999","unexpected"}) {
      need(EmbeddedDuplexDecoderInputTimeout.select(true,name->value)==100,"unknown input must retain default: "+value);
    }
    need(EmbeddedDuplexDecoderInputTimeout.select(true,name->null)==100,"missing default");
    need(EmbeddedDuplexDecoderInputTimeout.select(true,name->{throw new IllegalStateException("unavailable");})==100,"unavailable property default");
    need(EmbeddedDuplexDecoderInputTimeout.select(true,name->" 500 ")==500,"trim bounded value");
    need(EmbeddedDuplexDecoderInputTimeout.declaredDefault(null)==100,"absent context default");
    need(EmbeddedDuplexDecoderInputTimeout.declaredDefault(new android.content.Context(new android.content.res.Resources(0,1000)))==100,"missing resource default");
    need(EmbeddedDuplexDecoderInputTimeout.declaredDefault(new android.content.Context(new android.content.res.Resources(77,1000)))==1000,"declared resource baseline");
    need(EmbeddedDuplexDecoderInputTimeout.declaredDefault(new android.content.Context(new android.content.res.Resources(77,999)))==100,"invalid declared resource default");
    need(EmbeddedDuplexDecoderInputTimeout.select(false,1000,name->{throw new AssertionError("release override read");})==1000,"release declared baseline");
    need(EmbeddedDuplexDecoderInputTimeout.readForCurrentBuild(1000)==1000,"actual release declared baseline");
    need(EmbeddedDuplexDecoderInputTimeout.select(true,1000,name->"")==1000,"debug keeps declared baseline");
    need(EmbeddedDuplexDecoderInputTimeout.select(true,1000,name->"100")==100,"explicit debug override");
    need(EmbeddedDuplexDecoderInputTimeout.select(true,1000,name->"1001")==1000,"unknown override keeps declared baseline");
    need(EmbeddedDuplexDecoderInputTimeout.select(false,999,name->{throw new AssertionError("release read");})==100,"invalid direct baseline default");
    System.out.println("PASS release read inertness, exact debug budgets, unknown and unavailable default");
  }
}''')
classes=out/'classes';classes.mkdir()
commands=[['javac','-encoding','UTF-8','-d',str(classes),str(stub),str(context),str(resources),str(source),str(harness)],['java','-cp',str(classes),'io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.InputTimeoutHost']]
for index,command in enumerate(commands):
    command[0]=str(args.java_home/'bin'/(command[0]+'.exe'))
    result=subprocess.run(command,capture_output=True,text=True)
    (out/f'{index}-stdout.txt').write_text(result.stdout);(out/f'{index}-stderr.txt').write_text(result.stderr)
    (out/f'{index}-result.json').write_text(json.dumps(dict(argv=command,exit_code=result.returncode,host_only=True),indent=2))
    if result.returncode:print(result.stderr);raise SystemExit(result.returncode)
    print(result.stdout,end='')
