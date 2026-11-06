package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.toolkit.AppSystemActivity
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Closed packaged shader batches. API invocation is deliberately not GPU/pixel completion. */
@OptIn(SpatialSDKExperimentalAPI::class)
internal class SdkHotloadController(private val activity:AppSystemActivity) {
    private val session=UUID.randomUUID().toString()
    private var generation=0L
    private var request=0L
    private var selected="packaged_baseline"
    private var result="not_requested"
    private var digest=""
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun apply(action:String):String {
        request++
        val expected=if(action=="sdk-stale")generation-1 else generation
        if(expected!=generation){result="rejected:stale_generation";return result}
        val name=when(action){"sdk-baseline","sdk-revert"->"baseline";"sdk-tint"->"tint";"sdk-offset"->"offset";else->return "rejected:unknown"}
        var apiStarted=false
        try {
            val catalogBytes=activity.assets.open("hotload/catalog.json").use{it.readBytes()}
            val catalog=JSONObject(String(catalogBytes,Charsets.UTF_8))
            require(catalog.getString("schema")=="rusty.quest.sdk_hotload.catalog.v1"&&catalog.getString("sdkVersion")=="0.14.0")
            val batches=catalog.getJSONArray("batches");require(batches.length()==3)
            val byName=(0 until batches.length()).map{batches.getJSONObject(it)}.associateBy{it.getString("name")}
            require(byName.keys==setOf("baseline","tint","offset"))
            val batch=requireNotNull(byName[name]);require(batch.getString("abiSha256")==requireNotNull(byName["baseline"]).getString("abiSha256"))
            val vertices=batch.getJSONArray("vertexPaths").let{a->(0 until a.length()).map{a.getString(it)}}
            val fragments=batch.getJSONArray("fragmentPaths").let{a->(0 until a.length()).map{a.getString(it)}}
            val suffixes=listOf("",".mv",".2uv",".2uv.mv",".tangent",".tangent.mv",".skin",".skin.mv")
            require(vertices==suffixes.map{"workload.vert$it.spv"})
            require(fragments==listOf("",".mv",".tangent",".tangent.mv").map{"workload.frag$it.spv"})
            val root=File(activity.filesDir,"sdk-hotload/$session/$name")
            val files=batch.getJSONArray("files");require(files.length()==12)
            val validated=mutableMapOf<String,ByteArray>()
            for(i in 0 until files.length()) {
                val entry=files.getJSONObject(i);val path=entry.getString("path")
                require(path in vertices+fragments&&!validated.containsKey(path))
                val bytes=activity.assets.open("hotload/$name/$path").use{it.readBytes()}
                require(bytes.size in 20..1048576&&bytes.size.toLong()==entry.getLong("bytes")&&hash(bytes)==entry.getString("sha256"))
                require(entry.getString("abiSha256")==requireNotNull(byName["baseline"]).getJSONArray("files").let{a->(0 until a.length()).map{a.getJSONObject(it)}.single{it.getString("path")==path}.getString("abiSha256")})
                validated[path]=bytes
            }
            // All assets validate before writing. Per session/name directories are immutable after preparation.
            validated.forEach{(path,bytes)->val file=File(root,path);if(file.exists())require(file.readBytes().contentEquals(bytes)) else {file.parentFile!!.mkdirs();file.writeBytes(bytes);file.setReadOnly()}}
            apiStarted=true
            activity.scene.reloadShaders(root.absolutePath,vertices,fragments)
            generation++;selected=name;digest=hash(catalogBytes);result="api_invoked"
        } catch(e:Exception){result=if(apiStarted)"api_result_unknown:${e.javaClass.simpleName}" else "rejected:${e.javaClass.simpleName}"}
        return result
    }
    fun status():String=JSONObject().put("schema","rusty.quest.sdk_hotload.receipt.v1").put("app",activity.packageName)
        .put("session",session).put("generation",generation).put("requestId",request).put("batch",selected)
        .put("catalogSha256",digest).put("outcome",result).put("gpuCompletionProof",false).put("gpuRetirementProof",false).put("sdkSampledProof",false).toString()
}
