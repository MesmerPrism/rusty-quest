package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import kotlin.math.abs
import kotlin.math.sqrt

/** Measured layout revision owner, extracted from the callback's counter. */
internal class OperatorLayoutRevision {
    private data class Measurement(val owner:Any,val root:Any,val width:Int,val height:Int,val bounds:List<Double>,val clipped:List<Double>,val enabled:Boolean)
    private var prior:Measurement?=null
    var revision=0L
        private set
    fun observe(owner:Any,root:Any,width:Int,height:Int,bounds:DoubleArray,clipped:DoubleArray,enabled:Boolean):Long {
        val next=Measurement(owner,root,width,height,bounds.toList(),clipped.toList(),enabled)
        val old=prior
        if(old==null||old.owner!==owner||old.root!==root||old.copy(owner=owner,root=root)!=next){prior=next;revision++}
        return revision
    }
    fun invalidate(){if(prior!=null){prior=null;revision++}}
}

/** Activity identity and observed ECS-pose revision; no submitted-frame semantics. */
internal class OperatorPoseEvidence(private val ownerToken:String) {
    data class Evidence(val ownerToken:String,val poseRevision:Long,val effectivePoseRevision:Long)
    private var prior:List<Double>?=null
    private var revision=0L
    init{require(ownerToken.isNotBlank())}
    fun observe(t:DoubleArray,q:DoubleArray,width:Double,height:Double):Evidence {
        val next=t.toList()+q.toList()+listOf(width,height)
        if(prior!=next){prior=next;revision++}
        return Evidence(ownerToken,revision,revision)
    }
}

/** SDK metric pose -> OpenXR metric pose in the SAME LOCAL_FLOOR reference space. */
internal object SdkOperatorGeometry {
    fun operatorLayerMapped(bytes:ByteArray):Boolean {
        if(bytes.size>2*1024*1024)return false
        return bytes.toString(Charsets.UTF_8).lineSequence().any{line->
            val fields=line.trim().split(Regex("\\s+"),limit=6)
            fields.size==6&&fields[5].startsWith("/")&&fields[5].substringAfterLast('/')=="libXrApiLayer_METAX_operator.so"
        }
    }
    data class Projected(val center:DoubleArray,val orientation:DoubleArray,val right:DoubleArray,val up:DoubleArray,val normal:DoubleArray)
    fun project(t:DoubleArray,q:DoubleArray):Projected {
        require(t.size==3&&q.size==4&&t.all{it.isFinite()}&&q.all{it.isFinite()})
        val length=sqrt(q.sumOf{it*it});require(length>1e-8&&abs(length-1.0)<.01)
        val x=q[0]/length;val y=q[1]/length;val z=q[2]/length;val w=q[3]/length
        // R*M*R reflection: q' = (-x,-y,z,w). Reflect each SDK basis explicitly.
        val right=doubleArrayOf(1-2*(y*y+z*z),2*(x*y+z*w),-2*(x*z-y*w))
        val up=doubleArrayOf(2*(x*y-z*w),1-2*(x*x+z*z),-2*(y*z+x*w))
        // SDK quad front is local -Z; do not infer it from a reflected cross product.
        val normal=doubleArrayOf(-2*(x*z+y*w),-2*(y*z-x*w),1-2*(x*x+y*y))
        return Projected(doubleArrayOf(t[0],t[1],-t[2]),doubleArrayOf(-x,-y,z,w),right,up,normal)
    }
    fun widget(width:Int,height:Int,bounds:DoubleArray,clipped:DoubleArray,panelWidth:Double,panelHeight:Double):DoubleArray {
        require(width>0&&height>0&&panelWidth.isFinite()&&panelHeight.isFinite()&&panelWidth>0&&panelHeight>0)
        require(bounds.size==4&&clipped.size==4&&bounds.all{it.isFinite()}&&clipped.all{it.isFinite()})
        require(bounds[0]<bounds[2]&&bounds[1]<bounds[3]&&clipped[0]<clipped[2]&&clipped[1]<clipped[3])
        require(clipped[0]>=0&&clipped[1]>=0&&clipped[2]<=width&&clipped[3]<=height)
        require(clipped[0]>=bounds[0]&&clipped[1]>=bounds[1]&&clipped[2]<=bounds[2]&&clipped[3]<=bounds[3])
        return doubleArrayOf(((clipped[0]+clipped[2])/2/width-.5)*panelWidth,(.5-(clipped[1]+clipped[3])/2/height)*panelHeight)
    }
}
