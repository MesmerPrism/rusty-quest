package io.github.mesmerprism.rustyquest.native_renderer;

import org.json.JSONObject;

public final class NativePanelVisibilityReadbackTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static JSONObject receipt()throws Exception{return new JSONObject().put("schema","rusty.quest.native_mesh_ui.panel_visibility.v1")
        .put("generation",7).put("request_revision",3).put("effective_revision",3)
        .put("requested_visible",false).put("effective_visible",false).put("status","applied")
        .put("evidence","native-submitted-frame").put("visible_confirmation",false);}
    public static void main(String[] args)throws Exception{
        check(Boolean.FALSE.equals(NativeMeshUiProducer.VisibilityReadback.applied(receipt(),7,3)),"exact hidden native frame lost");
        check(Boolean.FALSE.equals(NativeMeshUiProducer.VisibilityReadback.applied(receipt(),7,0)),"latest native recall observation denied");
        for(String status:new String[]{"pending","stale","unavailable","queued"})check(NativeMeshUiProducer.VisibilityReadback.applied(receipt().put("status",status),7,3)==null,"non-effective observation accepted: "+status);
        for(String field:new String[]{"generation","request_revision","effective_revision"}){
            check(NativeMeshUiProducer.VisibilityReadback.applied(receipt().put(field,99),7,3)==null,"wrong identity accepted: "+field);
            check(NativeMeshUiProducer.VisibilityReadback.applied(receipt().put(field,"3"),7,3)==null,"text identity coerced: "+field);
            check(NativeMeshUiProducer.VisibilityReadback.applied(receipt().put(field,3.5),7,3)==null,"fractional identity truncated: "+field);
        }
        for(String field:new String[]{"schema","evidence","visible_confirmation","requested_visible","effective_visible"})
            check(NativeMeshUiProducer.VisibilityReadback.applied(receipt().put(field,"wrong"),7,3)==null,"invalid receipt accepted: "+field);
        check(NativeMeshUiProducer.VisibilityReadback.applied(receipt().put("effective_visible",true),7,3)==null,"requested and effective visibility disagree");
        check(NativeMeshUiProducer.VisibilityReadback.applied(null,7,3)==null,"missing frame receipt accepted");
        System.out.println("NativePanelVisibilityReadbackTest PASS exact submitted-frame tuple; admission/stale/wrong identity unavailable");
    }
}
