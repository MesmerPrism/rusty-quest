"""Compile actual codec-start catch/classifier and journal parser with host platform doubles."""
import argparse,json,pathlib,subprocess,hashlib
def member(text,needle):
 start=text.index(needle);opening=text.index('{',start);depth=1;i=opening+1
 while depth:
  if text[i]=='{':depth+=1
  elif text[i]=='}':depth-=1
  i+=1
 return text[start:i]
def main():
 p=argparse.ArgumentParser();p.add_argument('--repo-root',required=True);p.add_argument('--output-root',required=True);p.add_argument('--java-home',required=True);a=p.parse_args()
 root=pathlib.Path(a.repo_root);out=pathlib.Path(a.output_root);out.mkdir(parents=True,exist_ok=False)
 receiver=root/'crates/rusty-quest-media-stream-android/android/library/src/main/java/io/github/mesmerprism/rustyquest/media/PackedStereoMediaReceiver.java'
 resources=root/'apps/spatial-camera-panel-android/app/src/main/java/io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex/EmbeddedDuplexResources.java'
 r=receiver.read_text();s=resources.read_text()
 classifier=member(r,'    static final class CodecStartFailure {')
 catch_begin=r.index('        try { codec.start(); }');catch_end=r.index('        connectionState = "decoder_configured";',catch_begin)
 catch=r[catch_begin:catch_end]
 parser=member(s,'    private static boolean closedActivationRecord(')
 helpers=member(s,'    private static boolean nonnegativeLong(')+member(s,'    private static boolean canonicalSignedLong(')
 code='''import java.io.*;
public final class ReceiverCodecStartDiagnosticHost {
 static final class MediaCodec {
  RuntimeException failure; MediaCodec(RuntimeException value){failure=value;}
  void start(){if(failure!=null)throw failure;}
  static final class CodecException extends IllegalStateException {
   private static final long serialVersionUID=1L;final int code;final boolean recoverable,transientFailure;
   CodecException(int c,boolean r,boolean t){super("private exception text");code=c;recoverable=r;transientFailure=t;}
   int getErrorCode(){return code;}boolean isRecoverable(){return recoverable;}boolean isTransient(){return transientFailure;}
  }
 }
 final Object lock=new Object();CodecStartFailure firstCodecStartFailure=CodecStartFailure.none(),finalCodecStartFailure=CodecStartFailure.none();
 static IOException closed(String code,Throwable cause){return new IOException(code,cause);}
 void start(MediaCodec codec)throws IOException { CATCH }
 static final int MAX_ACTIVATION_RECORD_CHARS=2048;
 CLASSIFIER PARSER HELPERS
 static void require(boolean value){if(!value)throw new AssertionError();}
 static String record(String detail){return "stage=FIRST_SURFACE_IMAGE cause=IO elapsedMs=4 code=ACTIVATION_EFFECT_UNCERTAIN receiverState=STOPPED connection=STOPPED packets=0 frames=0 reconnects=1"+detail;}
 public static void main(String[] args)throws Exception {
  ReceiverCodecStartDiagnosticHost h=new ReceiverCodecStartDiagnosticHost();h.start(new MediaCodec(null));require(h.firstCodecStartFailure.kind.equals("NONE"));
  try{h.start(new MediaCodec(new MediaCodec.CodecException(-1100,true,false)));throw new AssertionError();}catch(IOException expected){require(expected.getCause() instanceof MediaCodec.CodecException);}
  require(h.firstCodecStartFailure.kind.equals("CODEC")&&h.firstCodecStartFailure.errorCode==-1100&&h.firstCodecStartFailure.recoverable&&!h.firstCodecStartFailure.transientFailure);
  try{h.start(new MediaCodec(new SecurityException("secret private text")));throw new AssertionError();}catch(IOException expected){}
  require(h.firstCodecStartFailure.errorCode==-1100&&h.finalCodecStartFailure.kind.equals("SECURITY"));
  require(CodecStartFailure.from(new IllegalStateException("secret")).kind.equals("ILLEGAL_STATE"));
  require(CodecStartFailure.from(new IllegalArgumentException("secret")).kind.equals("OTHER_RUNTIME"));
  CodecStartFailure transientCodec=CodecStartFailure.from(new MediaCodec.CodecException(1101,false,true));require(!transientCodec.recoverable&&transientCodec.transientFailure);
  String projection=h.firstCodecStartFailure.fields("codecStartFirst")+h.finalCodecStartFailure.fields("codecStartFinal");
  require(!projection.contains("secret")&&!projection.contains("private"));require(closedActivationRecord(record(projection),false));
  require(closedActivationRecord(record(""),false));
  require(!closedActivationRecord(record(projection.replace("Kind=CODEC","Kind=PRIVATE_MESSAGE")),false));
  require(!closedActivationRecord(record(projection.replace("ErrorCode=-1100","ErrorCode=2147483648")),false));
  require(!closedActivationRecord(record(projection.replace("Recoverable=1","Recoverable=true")),false));
  require(!closedActivationRecord(record(projection+" codecStartFirstKind=CODEC"),false));
  System.out.println("actual codec-start catch/classifier/journal controls passed");
 }
}'''.replace('CATCH',catch).replace('CLASSIFIER',classifier).replace('PARSER',parser).replace('HELPERS',helpers)
 source=out/'ReceiverCodecStartDiagnosticHost.java';source.write_text(code)
 jdk=pathlib.Path(a.java_home)
 for name,cmd in [('compile',[str(jdk/'bin/javac.exe'),'--release','8','-Xlint:all','-Werror',str(source)]),('controls',[str(jdk/'bin/java.exe'),'-cp',str(out),'ReceiverCodecStartDiagnosticHost'])]:
  result=subprocess.run(cmd,capture_output=True,text=True);(out/(name+'.stdout')).write_text(result.stdout);(out/(name+'.stderr')).write_text(result.stderr)
  if result.returncode:raise RuntimeError(name+' failed: '+result.stderr)
 pins=[dict(path=str(x),sha256=hashlib.sha256(x.read_bytes()).hexdigest(),bytes=x.stat().st_size) for x in (receiver,resources,pathlib.Path(__file__),source)]
 (out/'RESULT.json').write_text(json.dumps(dict(status='passed',source_pins=pins,extracted_actual_catch_before_wrapper=True,hardware_qualification=False,device_calls=0),indent=2))
 print(json.dumps(dict(status='passed',output=str(out))))
if __name__=='__main__':main()
