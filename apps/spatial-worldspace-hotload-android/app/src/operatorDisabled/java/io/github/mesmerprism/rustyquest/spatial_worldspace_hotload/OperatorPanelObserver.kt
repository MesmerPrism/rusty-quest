package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.runtime.PanelSceneObject
import org.json.JSONObject

/** No geometry, view, panel, request or pose retention in ordinary/release builds. */
@Suppress("UNUSED_PARAMETER")
internal class OperatorPanelObserver(activityInstanceId:String) {
    fun attach(view:ComposeView,panel:PanelSceneObject,entity:Entity){}
    fun clear(){}
    fun requested(id:String){}
    fun completed(id:String,status:String){}
    fun state(host:Map<String,String>):Map<String,String> = host
    fun layerLoaded():Boolean=false
    @Composable fun widgetModifier():Modifier=Modifier
    fun snapshot(host:JSONObject,active:Boolean,viewer:Pose?):JSONObject=JSONObject()
}
