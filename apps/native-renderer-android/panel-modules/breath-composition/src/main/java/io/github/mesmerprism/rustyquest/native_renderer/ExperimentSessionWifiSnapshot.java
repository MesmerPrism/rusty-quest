package io.github.mesmerprism.rustyquest.native_renderer;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Full Wi-Fi session observation. It has no command or transport authority. */
final class ExperimentSessionWifiSnapshot {
    static final int MAX_BYTES = 16 * 1024;
    private final ExperimentSessionPanelCoordinator coordinator = new ExperimentSessionPanelCoordinator();
    private final ExperimentSessionJsonCodec codec = new ExperimentSessionJsonCodec(
        new java.util.function.Supplier<ExperimentSessionPanelCoordinator>() {
            @Override public ExperimentSessionPanelCoordinator get() { return coordinator; }
        });

    static final class Read {
        final JSONObject status, nativeStatus;
        private final long completedNanos, epoch;
        private long lastNowNanos;
        Read(JSONObject status, JSONObject nativeStatus, long completedNanos, long epoch) {
            this.status=status; this.nativeStatus=nativeStatus;
            this.completedNanos=completedNanos; this.epoch=epoch;this.lastNowNanos=completedNanos;
        }
        synchronized JSONObject statusAt(long nowNanos, long currentEpoch) throws Exception {
            if (currentEpoch!=epoch || completedNanos<=0 || nowNanos<lastNowNanos
                    || nowNanos-completedNanos>5000000000L)
                throw new IllegalStateException("wifi-native-observation-stale");
            lastNowNanos=nowNanos;
            // Age the original observation; never replace its completion witness.
            status.getJSONObject("o").put("a",(nowNanos-completedNanos)/1000000L);
            return status;
        }
    }

    synchronized Read observe(JSONObject response, long completedNanos, long nowNanos, long epoch) throws Exception {
        Object rawEpoch=response.getJSONObject("readback").opt("runtime_epoch");
        // Owner epochs retain positive int64 range; the shared codec may coerce other JSON types.
        if (!(rawEpoch instanceof Integer) && !(rawEpoch instanceof Long)
                || ((Number)rawEpoch).longValue()<=0 || ((Number)rawEpoch).longValue()!=epoch)
            throw new IllegalStateException("wifi-native-epoch-invalid");
        ExperimentSessionAndroidShell.SessionReadback parsed=codec.parse(response.toString(),null,false);
        if (!"accepted".equals(parsed.commandStatus) || !"ready".equals(parsed.initializationStatus)
                || parsed.runtimeEpoch!=epoch || epoch<=0)
            throw new IllegalStateException("wifi-native-observation-unavailable");
        JSONObject nativeStatus=new JSONObject(response.getJSONObject("readback").toString());
        // Do not turn parser defaults or normalization into a complete full snapshot.
        for (String field:new String[]{"generation","revision","active_time_ms"})
            PolarStopBleCapability.integer(nativeStatus,field,true);
        JSONObject input=nativeStatus.optJSONObject("polar_input");
        if (input==null || !(input.opt("required") instanceof Boolean)
                || !(input.opt("fresh") instanceof Boolean) || !(input.opt("pause_latched") instanceof Boolean))
            throw new IllegalStateException("wifi-polar-input-unavailable");
        coordinator.acceptNativeReadback(epoch,parsed.receipt,completedNanos,nowNanos,true);
        ExperimentSessionPanelCoordinator.NativeStatusSnapshot observed=coordinator.nativeStatusSnapshot(nowNanos);
        ExperimentSessionPanelState state=observed.state;
        if (!observed.observed || observed.readId!=completedNanos || observed.runtimeEpoch!=epoch
                || state.generation!=nativeStatus.getLong("generation")
                || state.revision!=nativeStatus.getLong("revision")
                || state.activeTimeMs!=nativeStatus.getLong("active_time_ms"))
            throw new IllegalStateException("wifi-native-observation-mismatched");
        JSONObject status=new JSONObject().put("o",new JSONObject(observed.observationJson))
            .put("p",state.phase.name()).put("g",state.generation).put("r",state.revision)
            .put("c",state.activeCondition).put("rec",state.recording).put("co",state.completion.name())
            .put("ms",state.activeTimeMs).put("st",state.storageStatus)
            .put("done",state.counts.totalsAvailable?state.counts.completedTotal():JSONObject.NULL)
            .put("early",state.counts.totalsAvailable?state.counts.stoppedEarlyTotal():JSONObject.NULL)
            .put("pending",false).put("h","unknown");
        ExperimentSessionBleProtocol.projectPolarInput(status,nativeStatus);
        status.put("pd",new JSONObject().put("v",1).put("native",nativeDiagnostics(input)).put("java",JSONObject.NULL));
        Read read=new Read(status,nativeStatus,completedNanos,epoch);
        read.statusAt(nowNanos,epoch);
        return read;
    }

    /** Closed field projection: full Wi-Fi diagnostics never copy private raw owner objects. */
    private static JSONObject fields(JSONObject source,String[] names) throws Exception {
        JSONObject out=new JSONObject();
        for(String name:names) {
            Object value=source==null?null:source.opt(name);
            if(value instanceof String && ((String)value).length()<=64 || value instanceof Boolean
                    || (value instanceof Integer || value instanceof Long) && ((Number)value).longValue()>=0)
                out.put(name,value);
            else out.put(name,JSONObject.NULL);
        }
        return out;
    }
    private static JSONObject nativeDiagnostics(JSONObject input) throws Exception {
        JSONObject out=fields(input,new String[]{"clock","diagnostics_observed","reason","timeout_ms","accepted_age_ms","ingress_age_ms",
            "commit_age_ms","ingress_to_commit_ms","ingress_count","accepted_count","rejected_count"});
        JSONObject pause=input.optJSONObject("last_pause");
        out.put("last_pause",pause==null?JSONObject.NULL:fields(pause,new String[]{"reason","interruption_revision",
            "deadline_elapsed_realtime_ns","detected_elapsed_realtime_ns","fresh_at_detection","accepted_age_ms_at_detection"}));
        return out;
    }
    static void projectJavaDiagnostics(JSONObject status,JSONObject polar) throws Exception {
        JSONObject diagnostic=status.optJSONObject("pd");
        if(diagnostic==null)return;
        if(polar==null){diagnostic.put("java",JSONObject.NULL);return;}
        JSONObject out=fields(polar.optJSONObject("diagnostics"),new String[]{"clock","observed_at_monotonic_ns",
            "callback_count","acc_callback_count","decode_count","jni_submit_count","malformed_acc_count",
            "callback_age_ms","decode_age_ms","jni_submit_age_ms","decode_duration_ms","gatt_state","gatt_status",
            "gatt_event_age_ms","disconnect_count"});
        JSONObject link=fields(polar,new String[]{"connected","connection_generation","acc_requested","ecg_requested",
            "acc_pmd_ack_running","ecg_pmd_ack_running","acc_data_receiving_observed","ecg_data_receiving_observed"});
        for(java.util.Iterator<String> names=link.keys();names.hasNext();) {
            String name=names.next();out.put(name,link.get(name));
        }
        diagnostic.put("java",out);
    }

    static JSONObject bounded(JSONObject status) throws Exception {
        for (String field:new String[]{"v","m","wifi_snapshot","control_mode","o","p","g","r","c","rec","co","ms","st","pending","h","pi"})
            if (!status.has(field)) throw new IllegalStateException("wifi-required-field-unavailable");
        if (status.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw new IllegalStateException("wifi-status-budget-exceeded");
        return status;
    }
}
