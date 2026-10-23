package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import android.content.res.AssetManager
import com.meta.spatial.core.Vector4
import org.json.JSONObject

/** Generic material inputs. The private prepared manifest supplies their selected values. */
data class WorkloadRenderStyle(val row0:Vector4,val row1:Vector4,val row2:Vector4) {
    companion object {
        fun load(assets:AssetManager):WorkloadRenderStyle {
            val manifest=JSONObject(assets.open("workload/manifest.json").bufferedReader().use{it.readText()})
            require(manifest.getString("maskFiltering")=="linear-within-tile-nearest-layer")
            val style=manifest.getJSONObject("renderStyle")
            val bounds=mapOf("projectionOpacity" to (0.0..4.0),"outputAlphaScale" to (0.0..4.0),
                "depthSuppressionStrength" to (0.0..8.0),"rgbAlphaCoupling" to (0.0..1.0),
                "facingStrength" to (0.0..1.0),"maskAlphaCutoff" to (0.0..1.0),
                "depthNearMeters" to (0.0..16.0),"depthSpanMeters" to (0.0..1000.0),
                "maskMode" to (1.0..1.0),"maskDiscardMode" to (0.0..0.0),"premultiplied" to (1.0..1.0))
            require(style.keys().asSequence().toSet()==bounds.keys)
            val values=bounds.mapValues{(key,range)->
                val raw=style.get(key);require(raw is Number)
                val value=raw.toDouble();require(value.isFinite()&&value in range)
                value.toFloat()
            }
            require(values.getValue("depthSpanMeters")>0f)
            fun row(a:String,b:String,c:String,d:String)=Vector4(values.getValue(a),values.getValue(b),values.getValue(c),values.getValue(d))
            return WorkloadRenderStyle(row("projectionOpacity","outputAlphaScale","depthSuppressionStrength","rgbAlphaCoupling"),
                row("facingStrength","maskAlphaCutoff","depthNearMeters","depthSpanMeters"),
                Vector4(values.getValue("maskMode"),values.getValue("maskDiscardMode"),values.getValue("premultiplied"),0f))
        }
    }
}
