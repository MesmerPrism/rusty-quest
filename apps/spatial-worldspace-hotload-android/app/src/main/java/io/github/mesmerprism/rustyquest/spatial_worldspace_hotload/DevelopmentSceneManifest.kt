package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import org.json.JSONObject
import java.security.MessageDigest

/** Explicit public packet; never interpreted as genuine simulation or a hand rig. */
object DevelopmentSceneManifest {
    const val SCHEMA="rusty.quest.spatial_worldspace_hotload.development_scene.v1"
    fun parse(json:JSONObject,read:(String)->ByteArray):WorkloadDescriptor {
        require(json.getString("schema")==SCHEMA)
        require(json.getString("source")=="public-procedural"&&json.getString("backend")=="none"&&json.getString("handSource")=="none")
        require(json.getInt("atlasPacketVersion")==4&&json.getInt("handRecordLayout")==1)
        require(json.getInt("initialVisibleCount")==64&&json.getString("maskAsset")=="workload/mask.png")
        val layout=WorkloadLayout(json.getInt("mainCapacity"),json.getInt("tracerCapacity"),json.getInt("particlesOffset"),
            json.getInt("particlesCount"),json.getInt("auxiliaryOffset"),json.getInt("handsOffset"),json.getInt("handsCount"),
            json.getInt("graftsOffset"),json.getInt("graftsCount"),json.getInt("retainedParticleCapacity"),true)
        layout.validate();require(json.getInt("auxiliaryCount")==layout.particlesCount&&json.getInt("totalRecords")==1028)
        val assets=json.getJSONArray("assets");require(assets.length()==1)
        val mask=assets.getJSONObject(0);require(mask.getString("path")=="workload/mask.png")
        val expected=mask.getString("sha256");require(Regex("[a-f0-9]{64}").matches(expected))
        val bytes=read("workload/mask.png");require(bytes.size.toLong()==mask.getLong("bytes"))
        require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}==expected)
        val provenance=json.getJSONObject("provenance");require(!provenance.getBoolean("privateInputs"))
        require(Regex("[a-f0-9]{64}").matches(provenance.getString("producerSha256")))
        return WorkloadDescriptor(layout,HandTopology(0,0,0,0),"")
    }
}
