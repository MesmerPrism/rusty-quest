package com.meta.spatial.core

@RequiresOptIn annotation class SpatialSDKExperimentalAPI
data class Vector3(val x:Float,val y:Float,val z:Float)
data class Pose(val t:Vector3)
class Entity private constructor(val id:Long,val components:MutableList<Any>) {
    fun setComponent(value:Any){components.clear();components.add(value)}
    companion object {
        private var next=1L
        val created=mutableListOf<Entity>()
        fun create(components:List<Any>)=Entity(next++,components.toMutableList()).also{created.add(it)}
    }
}
