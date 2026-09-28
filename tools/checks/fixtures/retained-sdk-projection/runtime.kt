package com.meta.spatial.runtime
import com.meta.spatial.core.*
enum class StereoMode{LeftRight}
class Scene{fun destroyObject(o:SceneObject){if(o.registered){o.registered=false;o.destroy()}};fun getViewerPose()=Pose()}
class SceneMaterial{var destroyed=false;var reject=false;var calls=0;fun destroy(){if(!destroyed){destroyed=true;calls++;if(reject)throw IllegalStateException("fixture opaque native removal rejection")}}}
class SceneMesh{var destroyed=false;var reject=false;var calls=0;fun destroy(){if(!destroyed){destroyed=true;calls++;if(reject)throw IllegalStateException("fixture opaque native removal rejection")}}}
class SceneObject{var registered=true;var reject=false;var calls=0;fun destroy(){calls++;check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper());if(reject)throw IllegalStateException("fixture object rejection")}}
class SceneQuadLayer{var visible=true;var destroyed=false;var reject=false;var calls=0;fun destroy(){if(!destroyed){destroyed=true;calls++;check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper());if(reject)throw IllegalStateException("fixture layer rejection");visible=false}};fun updateLayer(a:Float,b:Float,c:Float,d:Float,e:Int){};fun setZIndex(v:Int){}}
class SceneSwapchain{var destroyed=false;var reject=false;var calls=0;fun destroy(){if(!destroyed){destroyed=true;calls++;if(reject)throw IllegalStateException("fixture opaque native removal rejection")}}}
