package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.nio.file.Files
import org.json.JSONObject

object FeedbackObservationHostTest {
    @JvmStatic fun main(args:Array<String>) {
        val root=Files.createTempDirectory("sdk-feedback-observations-").toFile()
        var count=0
        val store=FeedbackObservationStore(root)
        val state={mapOf("counter" to count.toString(),"application_token" to "retained")}
        val scene={JSONObject().put("visualRevision",count).put("textureUpdateCompleted",true).put("pixelsProven",false)}
        val first=JSONObject(store.execute("request-a",state,scene){count++})
        check(first.getString("schema")=="rusty.quest.hotload_observation.v1")
        check(first.getString("request_id")=="request-a"&&first.getString("host_kind")=="spatial-sdk")
        check(first.getString("status")=="pass"&&first.isNull("error"))
        check(first.getJSONObject("state").getString("counter")=="1")
        val file=root.resolve("development-feedback-observations/request-a.json")
        check(file.readText()==first.toString())
        try {store.execute("request-a",state,scene){count++};error("Duplicate admitted")}catch(_:IllegalStateException){}
        check(count==1&&file.readText()==first.toString())
        try {store.execute("../request",state,scene){count++};error("Path admitted")}catch(_:IllegalArgumentException){}
        check(count==1)
        val failed=JSONObject(store.execute("request-b",state,scene){throw IllegalArgumentException("closed operation")})
        check(failed.getString("status")=="refused"&&failed.getString("error").isNotEmpty())
        check(failed.getJSONObject("state").getString("counter")=="1")
        check(!failed.getJSONObject("scene").getBoolean("pixelsProven"))
        root.resolve("development-feedback-observations/request-c.pending").createNewFile()
        try {store.execute("request-c",state,scene){count++};error("Pending request replayed")}catch(_:java.nio.file.FileAlreadyExistsException){}
        check(count==1)
        val recreated=FeedbackObservationStore(root)
        try {recreated.execute("request-a",state,scene){count++};error("Recreated owner replayed")}catch(_:IllegalStateException){}
        check(count==1)
        println("PASS durable observation, duplicate/pending/path refusal, retained readback and recreated owner")
    }
}
