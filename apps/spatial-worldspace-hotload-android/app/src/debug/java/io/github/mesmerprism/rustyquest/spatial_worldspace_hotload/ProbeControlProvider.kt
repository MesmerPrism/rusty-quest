package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Debug-only DUMP + shell-UID control. Fixed typed actions; no caller paths or byte payloads. */
class ProbeControlProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())
    private val requests = AtomicLong()
    override fun onCreate(): Boolean = context != null
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if(!BuildConfig.DEBUG || Binder.getCallingUid() != Process.SHELL_UID) closed()
        requireNotNull(context).enforceCallingPermission("android.permission.DUMP", "Probe control requires DUMP")
        val feedback=method in setOf("feedback_status","feedback_native_status","feedback_native_init","feedback_native_activate","feedback_native_compute","feedback_activate","feedback_compute","feedback_select")
        val feedbackArguments=linkedMapOf<String,String>()
        var feedbackRequestId:String?=null
        if(feedback){
            if(!BuildConfig.DEVELOPMENT_FEEDBACK||arg!=null||(extras?.size()?:0)>9)closed()
            extras?.keySet()?.forEach{key->val value=extras.get(key);if(value !is String||value.length>128)closed();if(key=="request_id")feedbackRequestId=value else feedbackArguments[key]=value}
            if(feedbackRequestId?.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))!=true)closed()
        }else if(extras != null && !extras.isEmpty) closed()
        val hotload=method in setOf("sdk-baseline","sdk-tint","sdk-offset","sdk-revert","sdk-stale","texture-rebind","texture-revert","compute-a","compute-b","compute-revert","compute-bad-abi","compute-malformed","compute-stale","compute-rapid")
        if(hotload&&arg!=null)closed()
        val external=method in setOf("compute-info","compute-stage","compute-activate","compute-discard","compute-rollback")
        if(external){
            if(method in setOf("compute-activate","compute-discard")){if(arg==null||!Regex("[a-f0-9]{64}").matches(arg))closed()}
            else if(arg!=null)closed()
        }
        val resident=method in setOf("workload-info","workload-stage","workload-activate","workload-discard","workload-revert")
        if(resident){
            if(!BuildConfig.EXTERNAL_WORKLOAD_HOTLOAD)closed()
            if(method in setOf("workload-activate","workload-discard")){if(arg==null||!Regex("[a-f0-9]{64}").matches(arg))closed()}
            else if(arg!=null)closed()
        }
        val workload=hotload || external || resident || method in setOf("topology-binding","diagnostic-isolation","particle-bounds","particle-cohort","cohort-fixture","prepare-scale","topology","bounds-probe","publicationPaused","diag-page","diag-profile","mainCount","tracerCount","hands","grafts","simulate","reset","visualScale","graftScale","culling","occluder","measurementStart","measurementStop")
        if(workload) when(method){
            "topology-binding"->if(arg !in setOf("retained","fresh","off"))closed()
            "diagnostic-isolation"->if(arg !in setOf("on","off"))closed()
            "cohort-fixture"->if(arg !in CohortFixturePacket.commands)closed()
            "particle-cohort"->if(arg !in ParticleCohortFixturePacket.commands)closed()
            "particle-bounds"->if(arg !in ParticleBoundsCalibration.commands)closed()
            "diag-page"->if(arg !in setOf("0","1","2","3","4","5","6","7"))closed()
            "diag-profile"->if(arg !in AtlasDiagnosticProtocol.profiles)closed()
            "mainCount"->if(arg !in setOf("0","64","512","2562"))closed()
            "tracerCount"->if(arg !in setOf("0","2562","8967","17934"))closed()
            "hands","grafts","simulate","culling","occluder","publicationPaused"->if(arg !in setOf("true","false"))closed()
            "prepare-scale"->if(arg !in WorkloadTopology.ladders.keys+"release")closed()
            "topology"->if(arg !in WorkloadTopology.profiles)closed()
            "bounds-probe"->if(arg !in setOf("behind-off","behind-on","restore"))closed()
            "visualScale","graftScale"->{val scale=arg?.toFloatOrNull();if(scale==null||!scale.isFinite()||scale !in .1f..2f)closed()}
            "reset","measurementStart","measurementStop"->if(arg!=null)closed()
        }
        val command = if(workload) ProbeControl.STATUS else when(method) {
            "cpu" -> ProbeControl.CPU; "gpu" -> ProbeControl.GPU; "stop" -> ProbeControl.STOP
            "restoreSrgb" -> ProbeControl.RESTORE_SRGB; "flipRows" -> ProbeControl.FLIP_ROWS
            "hostGeometry" -> ProbeControl.HOST_GEOMETRY; "status" -> ProbeControl.STATUS
            else -> if(feedback)ProbeControl.STATUS else closed()
        }
        val booleanCommand = command in setOf(ProbeControl.RESTORE_SRGB, ProbeControl.FLIP_ROWS, ProbeControl.HOST_GEOMETRY)
        val value: Boolean? = if(booleanCommand) when(arg) { "true" -> true; "false" -> false; else -> closed() }
            else { if(!workload && arg != null) closed(); null }
        val response = Bundle().apply {
            putString("schema", "rusty.quest.spatial_worldspace_hotload.debug_operator.v1")
            putLong("requestId", requests.incrementAndGet()); putString("command", method)
            putBoolean("admitted", true); putBoolean("observed", false)
            putBoolean("sdkSampledProof", false); putBoolean("controllerInputProof", false)
        }
        val receipt = arrayOfNulls<String>(2)
        val done = CountDownLatch(1)
        // 0 pending, 1 executing, 2 cancelled, 3 finished. Cancel queued work on timeout;
        // once execution began, a timeout honestly leaves its result unknown to this caller.
        val state = AtomicInteger(0)
        val work = Runnable {
            if(!state.compareAndSet(0,1)) return@Runnable
            try {
                val target = ProbeDebugFeatures.target()
                if(target == null) receipt[0] = "blocked:no_activity"
                else { receipt[0] = if(feedback)target.applyFeedbackRequest(requireNotNull(feedbackRequestId),method.removePrefix("feedback_"),feedbackArguments)else if(workload)target.applyWorkloadControl(method,arg)else target.applyProbeControl(command,value); receipt[1] = target.probeControlStatus() }
            } catch(e: Exception) { receipt[0] = "failed:${e.javaClass.simpleName}" }
            finally { state.set(3); done.countDown() }
        }
        if(Looper.myLooper() == Looper.getMainLooper()) work.run()
        else if(!main.post(work)) return response.apply { putString("outcome", "dispatch_rejected") }
        val completed = try { done.await(1000,TimeUnit.MILLISECONDS) }
            catch(_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if(!completed) {
            val cancelled = state.compareAndSet(0,2)
            if(cancelled) main.removeCallbacks(work)
            return response.apply {
                putString("outcome", if(cancelled) "timeout_cancelled_before_execution" else "timeout_execution_result_unknown")
            }
        }
        return response.apply {
            putBoolean("observed", true); putString("outcome", receipt[0]); putString("snapshot", receipt[1])
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = closed()
    override fun getType(uri: Uri): String = closed()
    override fun insert(uri: Uri, values: ContentValues?): Uri = closed()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = closed()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = closed()
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = closed()
    private fun closed(): Nothing = throw SecurityException("Probe control route closed")
}
