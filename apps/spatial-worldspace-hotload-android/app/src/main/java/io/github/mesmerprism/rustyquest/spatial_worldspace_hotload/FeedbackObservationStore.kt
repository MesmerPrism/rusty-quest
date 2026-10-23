package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.json.JSONObject

/** App-owned create-once request journal. A pending request is never replayed. */
internal class FeedbackObservationStore(filesDirectory:File) {
    private val directory=File(filesDirectory,"development-feedback-observations")
    init {
        require(directory.absoluteFile==directory.canonicalFile&&!Files.isSymbolicLink(directory.toPath())){"Observation path alias"}
        Files.createDirectories(directory.toPath())
    }
    @Synchronized fun execute(requestId:String,state:()->Map<String,String>,scene:()->JSONObject,operation:()->Unit):String {
        require(requestId.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))){"Invalid request identity"}
        val completed=File(directory,"$requestId.json")
        check(!completed.exists()){"Request identity already completed"}
        // CREATE_NEW is the admission point, before any host/scene mutation.
        Files.newOutputStream(File(directory,"$requestId.pending").toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use{it.write(byteArrayOf(1))}
        var failure:Exception?=null
        try{operation()}catch(problem:Exception){failure=problem}
        val observedState=try{state()}catch(problem:Exception){failure=failure?:problem;mapOf("observation_error" to problem.javaClass.simpleName)}
        val observedScene=try{scene()}catch(problem:Exception){failure=failure?:problem;JSONObject().put("observation_error",problem.javaClass.simpleName)}
        val observation=JSONObject().put("schema","rusty.quest.hotload_observation.v1")
            .put("request_id",requestId).put("host_kind","spatial-sdk")
            .put("status",if(failure==null)"pass" else "refused")
            .put("error",failure?.let{"${it.javaClass.simpleName}: ${it.message}".take(512)}?:JSONObject.NULL)
            .put("state",JSONObject(observedState)).put("scene",observedScene).toString()
        val writing=File(directory,"$requestId.writing")
        Files.createFile(writing.toPath())
        FileOutputStream(writing).use{it.write(observation.toByteArray(Charsets.UTF_8));it.fd.sync()}
        Files.move(writing.toPath(),completed.toPath())
        return observation
    }
}
