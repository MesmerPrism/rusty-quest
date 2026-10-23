package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload
import android.content.res.AssetManager
import org.json.JSONObject
import java.security.MessageDigest

object WorkloadManifest {
    fun load(assets:AssetManager):WorkloadLayout = loadDescriptor(assets).layout
    fun loadDescriptor(assets:AssetManager,developmentFeedback:Boolean=false):WorkloadDescriptor {
        val json=JSONObject(assets.open("workload/manifest.json").bufferedReader().use{it.readText()})
        if(developmentFeedback)return DevelopmentSceneManifest.parse(json){path->assets.open(path).use{it.readBytes()}}
        require(json.getString("schema")=="rusty.quest.spatial_worldspace_hotload.asset_manifest.v1")
        require(json.getInt("atlasPacketVersion")==AtlasPacket.VERSION)
        require(json.getInt("handRecordLayout")==AtlasPacket.HAND_RECORD_LAYOUT)
        require(json.getString("maskAsset")=="workload/mask.png")
        val layout=WorkloadLayout(json.getInt("mainCapacity"),json.getInt("tracerCapacity"),json.getInt("particlesOffset"),
            json.getInt("particlesCount"),json.getInt("auxiliaryOffset"),json.getInt("handsOffset"),json.getInt("handsCount"),
            json.getInt("graftsOffset"),json.getInt("graftsCount"),json.optInt("retainedParticleCapacity",0))
        layout.validate();require(json.getInt("auxiliaryCount")==layout.particlesCount)
        require(json.getInt("totalRecords")==layout.graftsOffset+layout.graftsCount+4)
        val files=json.getJSONArray("assets");require(files.length()>0)
        val names=mutableSetOf<String>()
        val lengths=mutableMapOf<String,Long>()
        var metadata:ByteArray?=null
        var metadataHash=""
        for(i in 0 until files.length()) {
            val file=files.getJSONObject(i);val path=file.getString("path")
            require(path.startsWith("workload/") && !path.contains("..") && !path.contains("\\") && names.add(path))
            val expected=file.getString("sha256");require(expected.matches(Regex("[0-9a-f]{64}")))
            var actualBytes=0L
            val hash=MessageDigest.getInstance("SHA-256")
            assets.open(path).use { input -> val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n);actualBytes+=n} }
            require(actualBytes==file.getLong("bytes"))
            require(hash.digest().joinToString(""){"%02x".format(it)}==expected) { "Workload asset hash mismatch" }
            lengths[path]=actualBytes
            if(path=="workload/hands/metadata.bin"){
                require(actualBytes==48L)
                metadata=assets.open(path).use{it.readBytes()};metadataHash=expected
                require(MessageDigest.getInstance("SHA-256").digest(requireNotNull(metadata)).joinToString(""){"%02x".format(it)}==expected)
            }
        }
        require("workload/mask.png" in names && "workload/hands/metadata.bin" in names)
        val hands=HandTopology.decode(requireNotNull(metadata),layout)
        for((side,counts) in listOf("left" to (hands.leftVertices to hands.leftTriangles),"right" to (hands.rightVertices to hands.rightTriangles))){
            val prefix="workload/hands/$side/"
            require(lengths[prefix+"vertices.bin"]==counts.first.toLong()*48)
            require(lengths[prefix+"triangles.bin"]==counts.second.toLong()*16)
            require(lengths[prefix+"bind_poses.bin"]==26L*32)
            require(lengths[prefix+"bind_sources.bin"]==26L*16)
        }
        return WorkloadDescriptor(layout,hands,metadataHash)
    }
}
