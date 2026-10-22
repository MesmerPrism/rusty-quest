// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.nativecapture;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;
import android.util.Base64;
import android.util.Base64OutputStream;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Own square LEFT native capture -> MediaCodec input Surface -> binary stdout.
 * Root must own sole capture scope: native Stop is global, not session fenced.
 * Unknown Stop or local retirement remains alive, with no false terminal ACK.
 */
public final class NativeBrowserCapture {
    static final String PACKAGE="com.oculus.systemdriver",SERVICE="com.oculus.vrapi.ScreenCaptureService";
    static final String PERMISSION="horizonos.permission.METACAM_SCREEN_CAPTURE",DESCRIPTOR="com.oculus.aidl.IScreenCaptureService";
    static NativeBrowserCapture own;
    final NativeBrowserOptions options;
    final VideoRequestOwner20 video=new VideoRequestOwner20();
    final NativeVideoWire wire;
    Handler main,control;HandlerThread controlThread;Thread encoderThread,stdinThread;
    Application app;Class<?> requestType,stopType,privacyType,errorType;Method startCapture,stopCapture;
    Object originalProxy;IBinder originalBinder;IBinder.DeathRecipient deathRecipient;
    MediaCodec codec;Surface input;
    boolean codecStarted,deathLinkAttempted,deathLinked,deathUnlinkAttempted,deathRetired,callbackBarrier,controlJoined,encoderJoined,codecRetired,surfaceRetired,clientCleanupAttempted;
    volatile boolean stopping,held,failed,terminal,retirementStarted,eosRequested,encoderExited,stopNonceMatched;
    volatile long lastVideoElapsed,startElapsed=-1;
    volatile int version=-1,interfaceVersion=-1;
    volatile Throwable firstFailure;
    long eosRequestElapsed=-1;String stopReason="NONE";

    NativeBrowserCapture(NativeBrowserOptions options){
        this.options=options;
        OutputStream originalStdout=new FileOutputStream(FileDescriptor.out);
        wire=new NativeVideoWire("base64".equals(options.wireEncoding)
            ?new Base64OutputStream(originalStdout,Base64.NO_WRAP):originalStdout);
    }
    public static void main(String[] argv){
        try{
            own=new NativeBrowserCapture(new NativeBrowserOptions(argv));
            JSONObject identity=new JSONObject();Application app=ShellApplicationContext.create(identity);
            own.initialize(app,identity);Looper.loop();
        }catch(Throwable e){
            if(own==null){System.err.println("{\"event\":\"INVALID_ARGS_NO_RESOURCES\",\"state\":\"failed_before_start\"}");System.err.flush();System.exit(1);}
            else if(own.main==null){own.fail(e);own.emitTerminal();}
            else{own.fail(e);own.requestStop("initialization-failure");if(!own.terminal)Looper.loop();}
        }
    }
    void initialize(Application app,JSONObject identity)throws Exception {
        this.app=app;main=new Handler(Looper.getMainLooper());
        if(Process.myUid()!=2000||app.getApplicationInfo().uid!=2000||!"com.android.shell".equals(app.getPackageName())
            ||app.getAttributionSource().getUid()!=2000||!"com.android.shell".equals(app.getAttributionSource().getPackageName()))throw new SecurityException("genuine-shell-context-required");
        event("IDENTITY",new JSONObject().put("context",identity).put("service_start_or_bind_requested",false));
        controlThread=new HandlerThread("native-browser-original-control");controlThread.start();control=new Handler(controlThread.getLooper());
        startStdin();control.post(()->startOriginal());
        main.postDelayed(()->{if(!stopping&&startElapsed<0){fail(new IllegalStateException("original-startup-deadline"));requestStop("startup-deadline");}},15000);
        main.postDelayed(new Runnable(){public void run(){
            if(terminal||stopping)return;
            long now=SystemClock.elapsedRealtime();
            if(startElapsed>0&&((wire.videoPackets==0&&now-startElapsed>15000)||(wire.videoPackets>0&&now-lastVideoElapsed>10000))){
                fail(new IllegalStateException("encoder-video-start-or-live-stall"));requestStop("video-stall");return;
            }
            main.postDelayed(this,500);
        }},500);
    }
    void startStdin(){
        stdinThread=new Thread(()->{
            try{
                StringBuilder line=new StringBuilder();int ch;
                while((ch=System.in.read())!=-1){
                    if(ch=='\n'){
                        String actual=line.toString();if(actual.endsWith("\r"))actual=actual.substring(0,actual.length()-1);
                        if(options.stopLine(actual)){stopNonceMatched=true;main.post(()->requestStop("explicit-stop"));return;}
                        throw new IllegalArgumentException("closed-stop-control-mismatch");
                    }
                    if(ch<32&&ch!='\r'||ch>126||line.length()>=128)throw new IllegalArgumentException("bounded-ascii-stop-line");line.append((char)ch);
                }
                main.post(()->requestStop("stdin-eof"));
            }catch(Throwable e){fail(e);main.post(()->requestStop("stdin-control-failure"));}
        },"native-browser-closed-stop-ingress");
        // A blocked read cannot be cancelled portably. No join claim is emitted;
        // final original-process exit/EOF proves this daemon is gone.
        stdinThread.setDaemon(true);stdinThread.start();
    }
    void startOriginal(){
        try{
            if(stopping)return;
            ComponentName component=new ComponentName(PACKAGE,SERVICE);
            ServiceInfo service=app.getPackageManager().getServiceInfo(component,0);
            ApplicationInfo info=app.getPackageManager().getApplicationInfo(PACKAGE,0);
            if(!PACKAGE.equals(service.packageName)||!SERVICE.equals(service.name)||!service.exported||!service.enabled||!service.applicationInfo.enabled||!PERMISSION.equals(service.permission)||app.checkSelfPermission(PERMISSION)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("installed-manifest-or-shell-permission");
            if(!options.driver.equals(new File(info.sourceDir).getCanonicalFile())||!options.driver.isFile()||options.driver.length()!=options.driverBytes||!options.driverSha.equals(hash(options.driver)))throw new IllegalStateException("actual-installed-driver-source-pin");
            PathClassLoader loader=new PathClassLoader(options.driver.getPath(),ClassLoader.getSystemClassLoader());
            Class<?> iface=Class.forName(DESCRIPTOR,false,loader),stub=Class.forName(DESCRIPTOR+"$Stub",true,loader);
            Method asInterface=stub.getMethod("asInterface",IBinder.class),getVersion=iface.getMethod("getVersion"),getInterfaceVersion=iface.getMethod("getInterfaceVersion");
            if(iface.getClassLoader()!=loader||stub.getClassLoader()!=loader||!Modifier.isStatic(asInterface.getModifiers())||asInterface.getReturnType()!=iface||getVersion.getReturnType()!=int.class||getInterfaceVersion.getReturnType()!=int.class)throw new IllegalStateException("exact-code-only-aidl");
            requestType=Class.forName("com.oculus.aidl.ScreenCaptureStartRequest",true,loader);stopType=Class.forName("com.oculus.aidl.ScreenCaptureStopRequest",true,loader);
            privacyType=Class.forName("com.oculus.aidl.PrivacyParameters",true,loader);errorType=Class.forName("com.oculus.aidl.ScreenCaptureError",false,loader);
            startCapture=iface.getMethod("startScreenCapture",requestType);stopCapture=iface.getMethod("stopScreenCapture",stopType);
            if(requestType.getClassLoader()!=loader||stopType.getClassLoader()!=loader||privacyType.getClassLoader()!=loader||errorType.getClassLoader()!=loader||startCapture.getReturnType()!=errorType||stopCapture.getReturnType()!=errorType)throw new IllegalStateException("exact-typed-native-video-contract");
            if(stopping)return;
            BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){}};
            IBinder actual=receiver.peekService(app,new Intent().setComponent(component));
            if(actual==null)throw new IllegalStateException("public-peek-null-no-bind-or-start");
            if(!DESCRIPTOR.equals(actual.getInterfaceDescriptor()))throw new IllegalStateException("actual-binder-descriptor");
            Object proxy=asInterface.invoke(null,actual);
            if(!iface.isInstance(proxy)||!(proxy instanceof IInterface)||((IInterface)proxy).asBinder()!=actual)throw new IllegalStateException("same-original-typed-binder");
            originalBinder=actual;originalProxy=proxy;deathRecipient=()->observeDeath(actual,"death-recipient");
            deathLinkAttempted=true;
            try{actual.linkToDeath(deathRecipient,0);deathLinked=true;}catch(Throwable e){if(!actual.isBinderAlive())observeDeath(actual,"link-failure-not-alive");throw e;}
            if(stopping)return;
            version=(Integer)getVersion.invoke(proxy);interfaceVersion=(Integer)getInterfaceVersion.invoke(proxy);
            if(version!=10||interfaceVersion!=10)throw new IllegalStateException("actual-v10-v10-required");
            event("SERVICE_VERSION",new JSONObject().put("service_version",version).put("interface_version",interfaceVersion).put("descriptor",DESCRIPTOR).put("public_peek_only",true));
            if(stopping)return;prepareEncoder();if(stopping)return;
            Object request=requestType.getConstructor().newInstance();verifyDefaults(request);
            Object privacy=privacyType.getConstructor().newInstance();
            if(privacyType.getField("liftInhibit").getBoolean(privacy)||privacyType.getField("hideCaptureIndicator").getBoolean(privacy))throw new IllegalStateException("normal-privacy-defaults");
            requestType.getField("surface").set(request,input);requestType.getField("captureSessionId").set(request,options.invocation);requestType.getField("privacyParameters").set(request,privacy);
            requestType.getField("eyeSelection").setByte(request,(byte)0);requestType.getField("videoCaptureAspectRatioFovAdjust").setFloat(request,0f);
            if(!input.isValid()||!options.invocation.equals(requestType.getField("captureSessionId").get(request))||requestType.getField("surface").get(request)!=input||requestType.getField("eyeSelection").getByte(request)!=0||requestType.getField("videoCaptureAspectRatioFovAdjust").getFloat(request)!=0f)throw new IllegalStateException("own-square-left-native-request");
            if(stopping)return;
            event("NATIVE_START_INTENT",new JSONObject().put("capture_session_id",options.invocation).put("eye_selection",0).put("fov_adjust",0).put("width",1280).put("height",1280).put("lift_inhibit",false).put("hide_capture_indicator",false).put("global_stop_sole_capture_scope_required",true));
            if(stopping)return;video.beforeCall();Object error=startCapture.invoke(originalProxy,request);video.callReturned(error==null);
            if(error!=null)throw new IllegalStateException("native-start-error-may-have-partial-effect:"+errorMessage(error));
            startElapsed=SystemClock.elapsedRealtime();lastVideoElapsed=startElapsed;
            event("READY",new JSONObject().put("native_start_accepted",true).put("service_version",version).put("interface_version",interfaceVersion).put("width",1280).put("height",1280).put("eye_selection",0).put("fov_adjust",0));
            if(options.durationMs>0)main.postDelayed(()->requestStop("finite-duration"),options.durationMs);
        }catch(Throwable e){fail(e);checkOriginalDeath("start-rpc-failure");main.post(()->requestStop("startup-failure"));}
    }
    void verifyDefaults(Object request)throws Exception {
        if(requestType.getField("surface").get(request)!=null||!"".equals(requestType.getField("captureSessionId").get(request))||requestType.getField("privacyParameters").get(request)!=null
            ||requestType.getField("stabilizationParameters").get(request)!=null||requestType.getField("frameRateParameters").get(request)!=null||requestType.getField("ahbPool").get(request)!=null||requestType.getField("ahbPipeWriteFd").get(request)!=null
            ||requestType.getField("eyeSelection").getByte(request)!=0||requestType.getField("videoCaptureAspectRatioFovAdjust").getFloat(request)!=0f
            ||requestType.getField("isAsyncBufferSwapEnabled").getBoolean(request)||requestType.getField("isRgb2YuvEnabled").getBoolean(request)||requestType.getField("yOffset").getFloat(request)!=0f
            ||requestType.getField("rgb2YuvWidth").getInt(request)!=0||requestType.getField("rgb2YuvHeight").getInt(request)!=0)throw new IllegalStateException("exact-current-default-video-fields");
    }
    void prepareEncoder()throws Exception {
        MediaFormat f=MediaFormat.createVideoFormat("video/avc",1280,1280);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE,8000000);f.setInteger(MediaFormat.KEY_FRAME_RATE,30);f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
        f.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);f.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
        codec=MediaCodec.createEncoderByType("video/avc");codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        input=codec.createInputSurface();codec.start();codecStarted=true;
        wire.header(options.invocation,options.generation);
        encoderThread=new Thread(()->drainEncoder(),"native-browser-codec-wire");encoderThread.start();
    }
    void drainEncoder(){
        boolean eosSent=false;long eosDeadline=0;
        try{
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(true){
                if(eosRequested&&!eosSent){codec.signalEndOfInputStream();eosSent=true;eosDeadline=SystemClock.elapsedRealtime()+5000;}
                int index=codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat f=codec.getOutputFormat();
                    if(f.getInteger(MediaFormat.KEY_WIDTH)!=1280||f.getInteger(MediaFormat.KEY_HEIGHT)!=1280||!"video/avc".equals(f.getString(MediaFormat.KEY_MIME)))throw new IllegalStateException("actual-codec-format-differs");
                    for(int i=0;i<2;i++)if(f.containsKey("csd-"+i))wire.csd(NativeVideoWire.bytes(f.getByteBuffer("csd-"+i)));
                    event("CODEC_FORMAT",new JSONObject().put("format",String.valueOf(f)));
                }else if(index>=0){
                    try{
                        if(info.size>0){ByteBuffer b=codec.getOutputBuffer(index);if(b==null||info.offset<0||info.size>NativeVideoWire.MAX_PAYLOAD||info.offset+info.size>b.capacity())throw new IllegalStateException("codec-output-bounds");
                            b.position(info.offset);b.limit(info.offset+info.size);
                            if(wire.accessUnit(info.presentationTimeUs,NativeVideoWire.bytes(b),(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0))lastVideoElapsed=SystemClock.elapsedRealtime();
                        }
                    }finally{codec.releaseOutputBuffer(index,false);}
                    if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)break;
                }
                if(eosSent&&SystemClock.elapsedRealtime()>eosDeadline)throw new IllegalStateException("codec-eos-drain-deadline");
            }
            if(!eosRequested)throw new IllegalStateException("unexpected-codec-eos");
        }catch(Throwable e){fail(e);main.post(()->requestStop("codec-or-wire-failure"));}
        finally{encoderExited=true;if(control!=null)control.post(()->{if(retirementStarted)finishEncoderRetirement();});}
    }
    void requestStop(String reason){
        if(stopping)return;stopping=true;stopReason=reason;
        event("STOP_LATCHED",new JSONObject());
        if(control==null){emitTerminal();return;}
        control.post(()->stopOriginal());
        main.postDelayed(()->{if(!terminal&&!held)hold(new IllegalStateException("original-control-retirement-pending"));},15000);
    }
    void stopOriginal(){
        try{
            if(!video.attempted()||video.remoteBinderDead()){beginEncoderRetirement();return;}
            Object request=stopType.getConstructor().newInstance();stopType.getField("captureSessionId").set(request,options.invocation);
            if(!options.invocation.equals(stopType.getField("captureSessionId").get(request)))throw new IllegalStateException("nonempty-exact-stop-session");
            event("GLOBAL_NATIVE_STOP_INTENT",new JSONObject().put("session_is_not_ownership_fence",true));
            video.beforeStop();Object error=stopCapture.invoke(originalProxy,request);video.stopReturned(error==null,SystemClock.elapsedRealtime());
            event("GLOBAL_NATIVE_STOP_RETURNED",new JSONObject().put("accepted",error==null).put("native_server_acknowledged",false));
            if(error!=null)throw new IllegalStateException("native-stop-rejected-no-replay:"+errorMessage(error));
            // Keep draining the encoder while the asynchronous native Stop queue
            // progresses. This is the source-qualified grace, NOT server ACK.
            control.postDelayed(()->beginEncoderRetirement(),2000);
        }catch(Throwable e){fail(e);checkOriginalDeath("stop-rpc-failure");if(video.remoteBinderDead())beginEncoderRetirement();else hold(e);}
    }
    void beginEncoderRetirement(){
        if(retirementStarted)return;
        if(!video.safeToClose(SystemClock.elapsedRealtime())){hold(new IllegalStateException("native-stop-or-grace-unresolved"));return;}
        retirementStarted=true;held=false;eosRequested=true;eosRequestElapsed=SystemClock.elapsedRealtime();
        if(encoderThread==null||encoderExited)finishEncoderRetirement();
        else main.postDelayed(()->{if(!terminal&&!encoderExited)hold(new IllegalStateException("original-encoder-wire-thread-still-alive-no-release"));},7000);
    }
    void finishEncoderRetirement(){
        if(clientCleanupAttempted)return;clientCleanupAttempted=true;
        try{
            if(!retirementStarted||!video.safeToClose(SystemClock.elapsedRealtime()))throw new IllegalStateException("retirement-authority");
            if(encoderThread!=null){encoderThread.join(1000);if(encoderThread.isAlive())throw new IllegalStateException("encoder-thread-not-joined");}encoderJoined=true;
            if(codec!=null){if(codecStarted)codec.stop();codec.release();codec=null;}codecRetired=true;
            if(input!=null){input.release();input=null;}surfaceRetired=true;
            retireDeathMonitor();
            control.post(()->{callbackBarrier=true;main.post(()->finish());});
        }catch(Throwable e){hold(e);}
    }
    void retireDeathMonitor(){
        if(!deathLinkAttempted){deathRetired=true;return;}
        if(deathUnlinkAttempted)throw new IllegalStateException("death-unlink-no-replay");deathUnlinkAttempted=true;
        if(!deathLinked){if(video.remoteBinderDead()){deathRetired=true;return;}throw new IllegalStateException("death-link-unknown");}
        boolean removed;
        try{removed=originalBinder.unlinkToDeath(deathRecipient,0);}
        catch(java.util.NoSuchElementException e){if(!video.remoteBinderDead())throw e;removed=false;}
        if(!removed&&!video.remoteBinderDead())throw new IllegalStateException("live-death-monitor-unknown");deathRetired=true;
    }
    void observeDeath(IBinder actual,String kind){
        if(actual==null||actual!=originalBinder)throw new IllegalStateException("original-binder-identity");
        synchronized(video){if(video.remoteBinderDead())return;video.originalRemoteBinderDied();}
        fail(new IllegalStateException("original-native-binder-death:"+kind));event("ORIGINAL_BINDER_DEATH",new JSONObject());
        main.post(()->{if(!stopping)requestStop("original-binder-death");else if(!retirementStarted&&control!=null)control.post(()->beginEncoderRetirement());});
    }
    void checkOriginalDeath(String kind){if(originalBinder!=null&&!originalBinder.isBinderAlive())observeDeath(originalBinder,kind);}
    void finish(){
        try{
            if(terminal)return;
            if(controlThread!=null){controlThread.quitSafely();controlThread.join(3000);if(controlThread.isAlive())throw new IllegalStateException("control-worker-not-joined");}controlJoined=true;
            if(!encoderJoined||!codecRetired||!surfaceRetired||!deathRetired||!callbackBarrier)throw new IllegalStateException("client-retirement-incomplete");
            emitTerminal();
        }catch(Throwable e){hold(e);}
    }
    void emitTerminal(){
        if(terminal)return;
        boolean retired=(codec==null&&input==null&&(encoderThread==null||encoderJoined)&&(controlThread==null||controlJoined)&&(!deathLinkAttempted||deathRetired));
        if(!retired){hold(new IllegalStateException("no-false-terminal"));return;}
        // No writer/codec thread can still touch stdout at this point. Finish
        // Base64 before the stderr TERMINAL, including the final partial triple.
        // A known transport failure after client retirement is failed_retired,
        // never a successful media stream or a fabricated native-server ACK.
        try{wire.finish();}catch(Throwable e){fail(e);event("WIRE_FINALIZATION_FAILED",new JSONObject());}
        terminal=true;event("TERMINAL",new JSONObject());System.exit(failed?1:0);
    }
    void fail(Throwable e){synchronized(this){failed=true;if(firstFailure==null)firstFailure=e;}}
    void hold(Throwable e){fail(e);held=true;event("CLEANUP_UNKNOWN_HELD",new JSONObject());}
    String errorMessage(Object error)throws Exception{return String.valueOf(errorType.getField("message").get(error));}
    synchronized void event(String name,JSONObject data){
        try{
            data.put("schema","rusty.quest.native_browser_capture.event.v1").put("event",name).put("invocation",options.invocation).put("generation",options.generation).put("stop_nonce",options.stopNonce)
                .put("pid",Process.myPid()).put("uid",Process.myUid()).put("elapsed_ms",SystemClock.elapsedRealtime()).put("state",terminal?(failed?"failed_retired":"retired"):(held?"held":(stopping?"stopping":"running")))
                .put("stop_nonce_matched",stopNonceMatched).put("stop_reason",stopReason).put("native_start_attempted",video.attempted()).put("native_start_returned",video.returned()).put("native_start_accepted",video.accepted())
                .put("native_stop_attempted",video.stopAttempted()).put("native_stop_returned",video.stopReturned()).put("native_stop_accepted",video.stopAccepted()).put("native_server_acknowledged",false)
                .put("original_binder_death_observed",video.remoteBinderDead()).put("own_resources_retired",terminal).put("codec_retired",codecRetired||codec==null).put("surface_retired",surfaceRetired||input==null)
                .put("encoder_thread_joined_or_not_started",encoderThread==null||encoderJoined).put("control_worker_joined_or_not_started",controlThread==null||controlJoined).put("stdin_reader_daemon_join_claimed",false)
                .put("service_version",version).put("interface_version",interfaceVersion).put("video_packets",wire.videoPackets).put("config_packets",wire.configPackets).put("duplicate_pts_dropped",wire.duplicatePtsDropped)
                .put("wire_encoding",options.wireEncoding).put("wire_finished",wire.finished).put("wire_finish_attempted",wire.finishAttempted)
                .put("selected_driver_sha256",options.driverSha).put("selected_driver_bytes",options.driverBytes);
            if(firstFailure!=null)data.put("failure",firstFailure.toString().substring(0,Math.min(1024,firstFailure.toString().length())));
            System.err.println(data.toString());System.err.flush();
        }catch(Throwable ignored){System.err.println("{\"event\":\"DIAGNOSTIC_SERIALIZATION_FAILED\"}");System.err.flush();}
    }
    static String hash(File file)throws Exception{
        MessageDigest d=MessageDigest.getInstance("SHA-256");try(FileInputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b&255));return s.toString();
    }
}
