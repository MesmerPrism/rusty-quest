package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh

object BuildConfig {var DEBUG=true}
object WorkloadGeometry {
    val allocations=mutableListOf<SceneMesh>()
    fun records(material:SceneMaterial,records:IntArray,billboards:Boolean):SceneMesh =
        SceneMesh(material,records.copyOf()).also{allocations.add(it)}
}
