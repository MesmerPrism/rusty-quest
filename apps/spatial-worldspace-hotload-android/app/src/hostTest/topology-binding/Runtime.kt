package com.meta.spatial.runtime
import com.meta.spatial.core.Entity

class Scene
class SceneMaterial
class SceneMesh(val material:SceneMaterial,val records:IntArray) {
    val handle=next++
    fun computeCombinedBounds()="host-test-bounds"
    companion object {private var next=100L}
}
/** SDK call recorder only. It does not model rendering or the native setter defect. */
class SceneObject(val scene:Scene,val initialMesh:SceneMesh,val name:String,val entity:Entity) {
    init {check(!failConstruction);created.add(this)}
    val handle=next++
    var mesh=initialMesh;private set
    var visible=true;private set
    var cull=true;private set
    var position=listOf(0f,0f,0f);private set
    var meshSetters=0;private set
    fun setSceneMesh(value:SceneMesh,name:String){mesh=value;meshSetters++}
    fun setIsVisible(value:Boolean){check(!(value&&failShow));visible=value}
    fun shouldFrustumCull(value:Boolean){cull=value}
    fun setPosition(x:Float,y:Float,z:Float){position=listOf(x,y,z)}
    companion object {
        private var next=1000L
        val created=mutableListOf<SceneObject>()
        var failConstruction=false
        var failShow=false
    }
}
