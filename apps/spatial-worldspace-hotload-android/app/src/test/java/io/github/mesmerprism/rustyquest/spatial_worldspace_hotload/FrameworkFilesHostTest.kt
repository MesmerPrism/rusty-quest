package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.io.File
import org.json.JSONObject
import org.rusty.quest.feedback.HostUrlLoader
import org.rusty.quest.feedback.host.ResidentFeedbackHost

/** Actual filesystem alias plus the actual strict shared host; no Context or loader double. */
object FrameworkFilesHostTest {
    @JvmStatic fun main(args:Array<String>) {
        val fixture=File(args[0]).parentFile.resolve("framework-files-fixture")
        val physical=fixture.resolve("physical").canonicalFile
        val link=fixture.resolve("framework-alias").absoluteFile
        // A normalizable spelling also exercises the strict File guard on Windows,
        // whose canonicalFile does not resolve an NTFS junction's target.
        val alias=File(link,".").absoluteFile
        check(alias.isDirectory&&alias!=alias.canonicalFile){"Actual filesystem alias required"}
        check(link.toPath().toRealPath().toFile().canonicalFile==physical)
        try{ResidentFeedbackHost(alias,HostUrlLoader(),false,"alias-test");error("Raw host alias admitted")}
        catch(_:java.io.IOException){}
        try{FeedbackObservationStore(alias);error("Raw observation alias admitted")}
        catch(_:IllegalArgumentException){}
        val boundary=FrameworkFilesRoot(alias)
        check(boundary.directory==physical)
        val owner=ResidentFeedbackHost(boundary.directory,HostUrlLoader(),false,"actual-canonical-caller")
        val first=owner.snapshot()
        check(first.getValue("native_state")=="uninitialized")
        check(owner.execute("status",emptyMap())==first)
        val observed=JSONObject(FeedbackObservationStore(boundary.directory).execute("canonical-status",{owner.snapshot()},{JSONObject()}){})
        check(observed.getString("status")=="pass")
        check(observed.getJSONObject("state").getString("host_session_id")==first.getValue("host_session_id"))
        check(alias.resolve("development-feedback-observations/canonical-status.json").isFile)
        check(physical.resolve("development-feedback/${first.getValue("host_session_id")}").isDirectory)
        check(boundary.directory===boundary.directory)
        println("PASS actual framework filesystem alias refused by strict owners; canonical SDK boundary admits same physical root")
    }
}
