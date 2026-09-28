package com.meta.spatial.core
annotation class SpatialSDKExperimentalAPI
class Vector2(val x:Float,val y:Float)
class Vector3(val x:Float,val y:Float,val z:Float){operator fun plus(o:Vector3)=Vector3(x+o.x,y+o.y,z+o.z);operator fun times(v:Float)=Vector3(x*v,y*v,z*v)}
class Quaternion{companion object{fun fromDirection(a:Vector3,b:Vector3)=Quaternion()}}
class Pose(val t:Vector3=Vector3(0f,0f,0f),val q:Quaternion=Quaternion()){fun forward()=Vector3(0f,0f,-1f);fun up()=Vector3(0f,1f,0f)}
class Entity{var pose:Pose?=null;fun setComponent(o:Any){if(o is com.meta.spatial.toolkit.Transform)pose=o.transform}}
