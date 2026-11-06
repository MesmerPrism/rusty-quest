package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import android.os.Process
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.PanelShapeType
import com.meta.spatial.toolkit.Transform
import org.json.JSONArray
import org.json.JSONObject

/** Read-only observation of the original Compose control. No input injection or dispatch. */
internal class OperatorPanelObserver(private val activityInstanceId:String) {
    private var view:ComposeView?=null
    private var panel:PanelSceneObject?=null
    private var entity:Entity?=null
    private var generation=0L
    private val layoutEvidence=OperatorLayoutRevision()
    private val poseEvidence=OperatorPoseEvidence(activityInstanceId)
    private val layoutRevision:Long get()=layoutEvidence.revision
    private var bounds:DoubleArray?=null
    private var clipped:DoubleArray?=null
    private var rootWidth=0
    private var rootHeight=0
    private var widgetView:View?=null
    private var requestedId:String?=null
    private var completedId:String?=null
    private var completedStatus:String?=null
    private var completedCount=0L
    fun attach(v:ComposeView,p:PanelSceneObject,e:Entity){view=v;panel=p;entity=e;generation++}
    fun clear(){view=null;panel=null;entity=null;widgetView=null;bounds=null;clipped=null;layoutEvidence.invalidate()}
    fun requested(id:String){requestedId=id}
    fun completed(id:String,status:String){completedId=id;completedStatus=status;if(status=="pass")completedCount++}
    fun state(host:Map<String,String>):Map<String,String> = host+mapOf("original_ui_queued_request_id" to (requestedId?:"none"),
        "original_ui_completed_request_id" to (completedId?:"none"),"original_ui_completed_count" to completedCount.toString())
    fun layerLoaded():Boolean=try{
        java.io.FileInputStream("/proc/self/maps").use{input->
            val bytes=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
            while(true){val count=input.read(chunk);if(count<0)break;if(bytes.size()+count>2*1024*1024)return false;bytes.write(chunk,0,count)}
            SdkOperatorGeometry.operatorLayerMapped(bytes.toByteArray())
        }
    }catch(_:java.io.IOException){false}catch(_:SecurityException){false}
    @Composable fun widgetModifier():Modifier {
        val owner=LocalView.current
        return Modifier.onGloballyPositioned{coordinates->
        if(coordinates.isAttached){
            widgetView=owner
            val position=coordinates.localToRoot(Offset.Zero);val size=coordinates.size;val clip=coordinates.boundsInRoot()
            bounds=doubleArrayOf(position.x.toDouble(),position.y.toDouble(),(position.x+size.width).toDouble(),(position.y+size.height).toDouble())
            clipped=doubleArrayOf(clip.left.toDouble(),clip.top.toDouble(),clip.right.toDouble(),clip.bottom.toDouble())
            val root=coordinates.findRootCoordinates().size;rootWidth=root.width;rootHeight=root.height
            layoutEvidence.observe(owner,owner.rootView,rootWidth,rootHeight,requireNotNull(bounds),requireNotNull(clipped),owner.isEnabled)
        }
        }
    }
    fun snapshot(host:JSONObject,active:Boolean,viewer:Pose?):JSONObject {
        val result=JSONObject().put("schema","rusty.quest.development_input_geometry.v1").put("status","pending")
            .put("reason","awaiting-panel-layout")
            .put("host_kind","spatial-sdk").put("process_id",Process.myPid()).put("host_session_id",host.optString("host_session_id"))
            .put("owner_token",activityInstanceId).put("panel_generation",generation).put("layout_revision",layoutRevision)
            .put("evidence","sdk-ecs-panel-transform").put("source_coordinate_system","sdk-left-handed-z-forward")
            .put("coordinate_conversion","reflect-z").put("coordinate_system","openxr-right-handed").put("reference_space","LOCAL_FLOOR")
            .put("submitted_frame",JSONObject.NULL).put("submitted_pose_proven",false).put("pixels_proven",false)
            .put("observed_elapsed_ns",SystemClock.elapsedRealtimeNanos()).put("tracking_valid",active).put("tracking_evidence","scene-ready-and-resumed")
            .put("panel_visible",false).put("ui_request_id",requestedId?:JSONObject.NULL).put("ui_completed_request_id",completedId?:JSONObject.NULL)
            .put("ui_completed_status",completedStatus?:JSONObject.NULL)
        val v=view?:return result;val p=panel?:return result;val e=entity?:return result
        val b=bounds?:return result;val c=clipped?:return result
        result.put("surface_width_pixels",v.width).put("surface_height_pixels",v.height)
            .put("view_attached_to_window",v.isAttachedToWindow).put("view_shown",v.isShown)
            .put("view_visibility",v.visibility).put("view_window_visibility",v.windowVisibility)
            .put("sdk_root_view_matches",p.rootView===v)
        val widget=JSONObject().put("id","compute").put("enabled",active).put("visible",false)
            .put("bounds_pixels",JSONArray(b.toList())).put("clipped_bounds_pixels",JSONArray(c.toList()))
        result.put("widget",widget)
        if(!active)return result.put("status","unavailable").put("reason","scene-inactive")
        if(!v.isAttachedToWindow)return result.put("status","unavailable").put("reason","view-not-attached")
        if(!v.isShown)return result.put("status","unavailable").put("reason","view-not-shown")
        var current:View?=widgetView
        while(current!=null&&current!==v)current=current.parent as? View
        if(current!==v||v.width!=rootWidth||v.height!=rootHeight)return result.put("status","stale").put("reason","layout-owner-or-size-changed")
        var phase="panel-shape"
        try{
            val shape=requireNotNull(p.panelShapeConfig)
            result.put("panel_shape",shape.panelShapeType.name).put("pivot_offset_width",shape.pivotOffsetWidth)
                .put("pivot_offset_height",shape.pivotOffsetHeight)
            SdkOperatorGeometry.requireCenteredQuad(shape.panelShapeType==PanelShapeType.QUAD,shape.pivotOffsetWidth,shape.pivotOffsetHeight)
            phase="ecs-pose"
            val pose=e.getComponent<Transform>().transform
            val rawT=doubleArrayOf(pose.t.x.toDouble(),pose.t.y.toDouble(),pose.t.z.toDouble())
            val rawQ=doubleArrayOf(pose.q.x.toDouble(),pose.q.y.toDouble(),pose.q.z.toDouble(),pose.q.w.toDouble())
            val mapped=SdkOperatorGeometry.project(rawT,rawQ)
            val width=shape.width.toDouble();val height=shape.height.toDouble()
            phase="widget-bounds"
            val center=SdkOperatorGeometry.widget(v.width,v.height,b,c,width,height)
            phase="viewer-facing"
            requireNotNull(viewer)
            val eye=doubleArrayOf(viewer.t.x.toDouble(),viewer.t.y.toDouble(),-viewer.t.z.toDouble())
            require(eye.all{it.isFinite()}&&mapped.normal.indices.sumOf{(eye[it]-mapped.center[it])*mapped.normal[it]}>0){"Panel front is not viewer-facing"}
            val observedPose=poseEvidence.observe(rawT,rawQ,width,height)
            result.put("raw_sdk_pose",JSONObject().put("position",JSONArray(rawT.toList())).put("orientation_xyzw",JSONArray(rawQ.toList())))
                .put("owner_token",observedPose.ownerToken)
                .put("pose_revision",observedPose.poseRevision).put("effective_pose_revision",observedPose.effectivePoseRevision)
                .put("center",JSONArray(mapped.center.toList())).put("orientation_xyzw",JSONArray(mapped.orientation.toList()))
                .put("right",JSONArray(mapped.right.toList())).put("up",JSONArray(mapped.up.toList())).put("normal",JSONArray(mapped.normal.toList()))
                .put("width_meters",width).put("height_meters",height).put("panel_visible",true).put("status","applied").put("reason","qualified")
            widget.put("visible",true).put("center_panel_meters",JSONArray(center.toList()))
        }catch(_:Exception){result.put("status","unavailable").put("reason","invalid-$phase")}
        return result
    }
}
