package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NativePanelGeometryReadbackTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static JSONObject receipt()throws Exception{
        return new JSONObject().put("schema","rusty.quest.native_mesh_ui.panel_geometry.v1").put("status","applied")
            .put("generation",7).put("pose_revision",3).put("effective_pose_revision",3).put("submitted_frame",0)
            .put("reference_space","LOCAL").put("frame_panel_visible",true)
            .put("surface_width_pixels",1024).put("surface_height_pixels",768)
            .put("center",new JSONArray(new double[]{0,1.5,-1.4})).put("right",new JSONArray(new double[]{1,0,0}))
            .put("up",new JSONArray(new double[]{0,1,0})).put("width_meters",0.96).put("height_meters",0.72)
            .put("evidence","native-submitted-frame").put("visible_confirmation",false);
    }
    public static void main(String[] args)throws Exception{
        check(NativeMeshUiProducer.GeometryReadback.applied(receipt(),7),"actual frame0 rejected");
        check(NativeMeshUiProducer.GeometryReadback.applied(receipt().put("reference_space","STAGE"),7),"actual fallback STAGE lost");
        for(String field:new String[]{"generation","pose_revision","effective_pose_revision","submitted_frame"}){
            check(!NativeMeshUiProducer.GeometryReadback.applied(receipt().put(field,"3"),7),"coerced tuple identity: "+field);
            check(!NativeMeshUiProducer.GeometryReadback.applied(receipt().put(field,3.5),7),"truncated tuple identity: "+field);
        }
        for(String status:new String[]{"pending","uninitialized","unavailable","requested"})
            check(!NativeMeshUiProducer.GeometryReadback.applied(receipt().put("status",status),7),"unsubmitted geometry became effective");
        check(!NativeMeshUiProducer.GeometryReadback.applied(receipt(),8),"old producer geometry admitted");
        check(!NativeMeshUiProducer.GeometryReadback.applied(receipt().put("effective_pose_revision",2),7),"old pose geometry admitted");
        check(!NativeMeshUiProducer.GeometryReadback.applied(receipt().put("reference_space","LOCAL_FLOOR"),7),"guessed tool default reference admitted");
        check(!NativeMeshUiProducer.GeometryReadback.applied(receipt().put("submitted_frame",-1),7),"negative submitted identity admitted");
        check(!NativeMeshUiProducer.GeometryReadback.applied(receipt().put("width_meters",0),7),"zero plane dimension admitted");
        check(!NativeMeshUiProducer.GeometryReadback.applied(receipt().put("up",new JSONArray(new int[]{1,0,0})),7),"nonorthogonal panel admitted");
        check(!NativeMeshUiProducer.GeometryReadback.applied(receipt().put("center",new JSONArray(new String[]{"0","0","0"})),7),"coerced vector admitted");
        JSONObject editor=NativeMeshUiProducer.WidgetMetadata.labels("textbox","secret-value-never-exposed","Device route token");
        check(editor.length()==1&&editor.getString("hint").equals("Device route token"),"editor values leaked into geometry");
        check(NativeMeshUiProducer.WidgetMetadata.labels("text","Controller: x Key: secret",null).length()==0,"credential TextView projected");
        check(NativeMeshUiProducer.WidgetMetadata.labels("button","private-controller-key",null).length()==0,"dynamic button text projected");
        check(NativeMeshUiProducer.WidgetMetadata.labels("button","Save and exit",null).getString("static_label").equals("Save and exit"),"static action label lost");
        check(NativeMeshUiProducer.WidgetMetadata.labels("button","q",null).getString("static_label").equals("q"),"static keyboard key lost");
        System.out.println("NativePanelGeometryReadbackTest PASS exact submitted geometry identity/reference and secret-free widget metadata");
    }
}
