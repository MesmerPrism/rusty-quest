package io.github.mesmerprism.rustyquest.media;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual capture/compositor serializers with fixed maximum-width primitive fixtures. No Android constructors. */
public final class PipelineWaitMaximumMain {
    static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        return unsafeType.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type);
    }
    static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    static void maximum(Object object) throws Exception {
        for (Class<?> type = object.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                if (field.getType() == long.class) field.setLong(object,
                        field.getName().equals("lastNs") ? Long.MAX_VALUE / 2 : Long.MAX_VALUE);
                else if (field.getType() == int.class) field.setInt(object, Integer.MAX_VALUE);
                else if (field.getType() == boolean.class) field.setBoolean(object, true);
            }
        }
    }
    static CaptureFrameTrace trace() throws Exception {
        CaptureFrameTrace trace = new CaptureFrameTrace(); maximum(trace);
        Field frameField = CaptureFrameTrace.class.getDeclaredField("frames"); frameField.setAccessible(true);
        CaptureFrameTrace.Frame[] frames = (CaptureFrameTrace.Frame[]) frameField.get(trace);
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new CaptureFrameTrace.Frame(Long.MAX_VALUE); maximum(frames[i]);
            frames[i].notificationNs = 1; frames[i].handoffNs = Long.MAX_VALUE / 2;
        }
        set(trace, "size", 12); set(trace, "next", 0); set(trace, "following", 4);
        set(trace, "processEpoch", repeated('e', 32)); set(trace, "crossingStage", "surface_notification");
        return trace;
    }
    static PipelineWaitObservation waits(String owner, String... names) throws Exception {
        PipelineWaitObservation observation = new PipelineWaitObservation(owner, () -> Long.MAX_VALUE, names);
        Field field = PipelineWaitObservation.class.getDeclaredField("rows"); field.setAccessible(true);
        for (long[] row : (long[][])field.get(observation)) Arrays.fill(row, Long.MAX_VALUE);
        return observation;
    }
    static Object telemetry(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object result = constructor.newInstance(); maximum(result); return result;
    }
    static JSONObject source() throws Exception {
        PackedStereoCaptureOwner capture = (PackedStereoCaptureOwner)allocate(PackedStereoCaptureOwner.class);
        PackedStereoGlCompositor compositor = (PackedStereoGlCompositor)allocate(PackedStereoGlCompositor.class);
        maximum(capture); maximum(compositor);
        for (Field field : PackedStereoCaptureOwner.class.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getType() == PackedStereoCaptureOwner.CameraResultCadence.class
                    || field.getType() == PackedStereoCaptureOwner.CameraFailureCadence.class
                    || field.getType() == PackedStereoGlCompositor.StageCadence.class)
                field.set(capture, telemetry(field.getType()));
        }
        for (Field field : PackedStereoGlCompositor.class.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.getType() == PackedStereoGlCompositor.StageCadence.class
                    || field.getType() == PackedStereoGlCompositor.SurfaceCallbackCadence.class
                    || field.getType() == PackedStereoGlCompositor.UpdateDurationPeak.class)
                field.set(compositor, telemetry(field.getType()));
        }
        PackedStereoFramePairer pairer = new PackedStereoFramePairer(4, 500_000_000L); maximum(pairer);
        set(compositor, "pairer", pairer);
        set(capture, "compositor", compositor);
        set(capture, "leftFrameTrace", trace()); set(capture, "rightFrameTrace", trace());
        set(capture, "waits", waits("capture", "encoder_offer"));
        set(compositor, "waits", waits("capture_compositor", "capture_iteration", "capture_make_current", "capture_pool_poll_ready"));
        Field first = PackedStereoCaptureOwner.class.getDeclaredField("firstFailure"); first.setAccessible(true);
        Constructor<?> firstConstructor = first.getType().getDeclaredConstructor(int.class,int.class,int.class,long.class);
        firstConstructor.setAccessible(true); first.set(capture,firstConstructor.newInstance(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Long.MAX_VALUE));
        Method aggregate = Class.forName("io.github.mesmerprism.rustyquest.media.PipelineSourceAggregation")
                .getDeclaredMethod("append",PackedStereoCaptureOwner.class,PipelineWaitObservation.class,PipelineWaitObservation.class,PipelineWaitObservation.class);
        JSONObject own = (JSONObject)aggregate.invoke(null,capture,
                waits("encoder_output", "encoder_drain", "encoder_dequeue_output"),
                waits("encoder_input", "encoder_input_cycle", "encoder_make_current", "encoder_egl_swap"),
                waits("packet_writer", "packet_write", "packet_flush"));
        JSONObject codec = new PackedStereoGlCompositor.StageCadence().snapshot(Long.MAX_VALUE);
        for (String key : codec.keySet()) codec.put(key, Long.MAX_VALUE);
        return new JSONObject().put("shared_app_capture", true).put("own_capture_stage_diagnostic", own)
                .put("codec_output_with_pair", codec).put("encoded_frames", Long.MAX_VALUE)
                .put("video_packet_count", Long.MAX_VALUE).put("last_packet_age_ms", Long.MAX_VALUE);
    }
    static String repeated(char value, int count) { char[] text = new char[count]; Arrays.fill(text, value); return new String(text); }
    static JSONObject qualification() throws Exception {
        JSONArray words = new JSONArray(); for (int i = 0; i < 160; i++) words.put(Long.MAX_VALUE);
        return new JSONObject().put("schema", "rusty.quest.stereo.concurrent_qualification_receipt.v1")
                .put("action", "read").put("native_snapshot", words).put("challenge", repeated('d',64))
                .put("process_epoch_id", repeated('e',32)).put("runtime_config_sha256", repeated('a',64))
                .put("feature_lock_sha256", repeated('b',64)).put("apk_sha256", repeated('c',64))
                .put("arm_generation", Long.MAX_VALUE).put("native_process_generation", Long.MAX_VALUE);
    }
    static void falseFlags(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            for (String key : object.keySet()) {
                Object child = object.get(key);
                if (child instanceof Boolean) object.put(key,false); else falseFlags(child);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            for (int i=0;i<array.length();i++) {
                Object child=array.get(i);
                if (child instanceof Boolean) array.put(i,false); else falseFlags(child);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        // Fixed leaf only: actual owner serializers observe the maximum elapsed clock.
        Class.forName("android.os.SystemClock").getField("fixedNs").setLong(null, Long.MAX_VALUE);
        Method wrap = Class.forName("io.github.mesmerprism.rustyquest.media.PipelineOwnWrapper").getDeclaredMethod("wrap", JSONObject.class);
        JSONObject source = source();
        System.out.println("maximum source bytes=" + source.toString().getBytes(StandardCharsets.UTF_8).length);
        System.out.println("maximum pipeline bytes=" + source.getJSONObject("own_capture_stage_diagnostic").getJSONObject("pipeline_waits").toString().length());
        String exact = (String)wrap.invoke(null, source);
        int bytes = exact.getBytes(StandardCharsets.UTF_8).length;
        // The unchanged wrapper reads the real host wall clock; reserve its full signed64 width too.
        if (bytes+6 > 32768 || new JSONObject(exact).length() != 19) throw new AssertionError("Own wrapper bounds/root changed");
        // Conservative width upper bound: false is one byte longer than true, without removing any old field/value.
        falseFlags(source.getJSONObject("own_capture_stage_diagnostic"));
        String maximumFlags = (String)wrap.invoke(null,source);
        int flagBytes=maximumFlags.getBytes(StandardCharsets.UTF_8).length;
        if (flagBytes+6>32768 || flagBytes<bytes) throw new AssertionError("unavailable/false flag width exceeds old cap");
        if (args.length==1) java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),maximumFlags.getBytes(StandardCharsets.UTF_8));
        source.getJSONObject("own_capture_stage_diagnostic").put("oversize_control", repeated('x',32768));
        try { wrap.invoke(null, source); throw new AssertionError("oversize diagnostic accepted"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof IllegalStateException)) throw expected;
        }
        System.out.println("PASS actual capture/compositor/source aggregation + unchanged Own wrapper maximum-width bytes="
                +bytes+" conservative_false_flags_bytes="+flagBytes+" plus_wall_width_reserve=6 <=32768; oversize refuses whole body");
    }
}
