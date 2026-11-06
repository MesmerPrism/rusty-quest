package io.github.mesmerprism.rustyquest.native_renderer;
/** Tests the actual maintained producer's selected attachment admission. */
public final class ShowcaseAttachmentAdmissionTest {
    public static void main(String[]args){
        if(NativeMeshUiProducer.AttachmentAdmission.allowNeutralFallback(true))throw new AssertionError("missing facade/method exposed diagnostic controls");
        if(!NativeMeshUiProducer.AttachmentAdmission.allowNeutralFallback(false))throw new AssertionError("old neutral fallback changed");
        for(boolean typed:new boolean[]{false,true})for(boolean owner:new boolean[]{false,true})for(boolean active:new boolean[]{false,true}){
            if(NativeMeshUiProducer.AttachmentAdmission.valid(true,typed,owner,active)!=(typed&&owner&&active))throw new AssertionError("selected return/owner/currentness admission");
        }
        if(!NativeMeshUiProducer.AttachmentAdmission.valid(false,true,false,false))throw new AssertionError("old retained cross-owner presentation changed");
        System.out.println("PASS production attachment: selected missing facade/method cannot fallback; wrong type/owner/retired generation rejected; default-off neutral behavior retained");
    }
}
