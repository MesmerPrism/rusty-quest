package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import android.content.Context
import android.os.Process
import org.rusty.quest.feedback.AndroidDexLoader
import org.rusty.quest.feedback.host.ResidentFeedbackHost
import java.util.UUID
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Process-owned shared host. No Activity, Scene, texture or SDK resource is retained here. */
internal object SdkFeedbackSession {
    private var resident:ResidentFeedbackHost?=null
    private var visualLane="none"
    private var visualDelta:Long?=null
    @Synchronized private fun host(context:Context):ResidentFeedbackHost {
        check(BuildConfig.DEBUG&&BuildConfig.DEVELOPMENT_FEEDBACK){"Development feedback unselected"}
        return resident?:ResidentFeedbackHost(context.applicationContext.filesDir,AndroidDexLoader(),
            "${Process.myPid()}:${UUID.randomUUID()}").also{created->
                val state=created.snapshot()
                val descriptor=JSONObject().put("schema","rusty.quest.hotload_current_session.v1")
                    .put("host_kind","spatial-sdk").put("pid",Process.myPid())
                    .put("session_id",state.getValue("host_session_id"))
                    .put("host_session_id",state.getValue("host_session_id"))
                    .put("native_session_id",state.getValue("native_session_id"))
                val directory=File(context.applicationContext.filesDir,"development-feedback")
                val temporary=File.createTempFile("current-session-",".writing",directory)
                FileOutputStream(temporary).use{it.write(descriptor.toString().toByteArray(Charsets.UTF_8));it.fd.sync()}
                Files.move(temporary.toPath(),File(directory,"current-session.json").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                resident=created
            }
    }
    @Synchronized fun execute(context:Context,method:String,arguments:Map<String,String>):Map<String,String> {
        val owner=host(context)
        val before=if(method=="native_compute"||method=="compute")owner.snapshot()else null
        val result=owner.execute(method,arguments)
        if(method=="native_compute"){
            visualDelta=CpuFeedbackProjection.nativeDelta(requireNotNull(before).getValue("native_value").toLong(),result.getValue("native_value").toLong())
            visualLane="native"
        }
        if(method=="compute"){
            visualDelta=CpuFeedbackProjection.dexDelta(arguments.getValue("input").toLong(),requireNotNull(before).getValue("counter").toLong(),result.getValue("result").toLong())
            visualLane="dex"
        }
        return result
    }
    @Synchronized fun snapshot(context:Context):Map<String,String> =host(context).snapshot()+mapOf("visual_lane" to visualLane,"visual_delta" to (visualDelta?.toString()?:"none"))
    @Synchronized fun visualValue(context:Context):Long? {host(context);return if(visualLane=="none")null else visualDelta}
}
