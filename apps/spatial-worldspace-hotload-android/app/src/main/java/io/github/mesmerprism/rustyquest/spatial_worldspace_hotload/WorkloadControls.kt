package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload
import org.json.JSONObject
/** Low-rate transport and workload budgets; no private driver parameters. */
data class WorkloadControls(var drawMainCount: Int=2562, var drawTracerCount: Int=17934,
    var drawHands: Boolean=false, var drawGrafts: Boolean=false, var simulate: Boolean=true,
    var visualScale: Float=.70f, var graftScale: Float=.85f, var controlRevision: Int=0,
    var publicationPaused:Boolean=false) {
    fun validate() {
        require(drawMainCount in listOf(0,64,512,2562))
        require(drawTracerCount in listOf(0,2562,8967,17934))
        require(visualScale.isFinite() && visualScale in .1f..2f)
        require(graftScale.isFinite() && graftScale in .1f..2f)
        require(controlRevision>=0)
    }
    fun json(reset: Boolean=false): String {
        validate()
        return JSONObject().put("drawMainCount",drawMainCount).put("drawTracerCount",drawTracerCount)
            .put("drawHands",drawHands).put("drawGrafts",drawGrafts).put("simulate",simulate)
            .put("publicationPaused",publicationPaused)
            .put("reset",reset).put("visualScale",visualScale).put("graftScale",graftScale)
            .put("centerX",0).put("centerY",1.4).put("centerZ",-1.5).put("worldScale",.35)
            .put("controlRevision",controlRevision).toString()
    }
}
