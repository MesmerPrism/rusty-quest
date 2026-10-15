package io.github.mesmerprism.rustyquest.native_renderer;

/** Production rendezvous identity/lifecycle state; no Android lifecycle simulation claim. */
public final class NativePanelOwnerAttachmentTest {
    private static void check(boolean value) { if(!value)throw new AssertionError(); }
    public static void main(String[] args) {
        NativeMeshUiProducer.OwnerAttachmentState state=new NativeMeshUiProducer.OwnerAttachmentState();
        Object actual=new Object(),other=new Object();
        check(!state.resumed());
        check(state.bootstrap()&&!state.bootstrap());
        check(!state.returnToImmersive());
        check(!state.visibility(actual,false));
        check(state.ready(actual)&&!state.ready(other));
        check(state.returnToImmersive()&&!state.returnToImmersive());
        check(!state.visibility(other,false));
        check(state.visibility(actual,false));
        state.resumed(true);check(state.resumed()&&!state.returnToImmersive());
        check(Boolean.FALSE.equals(state.takeVisibility(actual))&&state.takeVisibility(actual)==null);
        check(state.visibility(actual,true));
        state.destroyed(other);check(Boolean.TRUE.equals(state.takeVisibility(actual)));
        check(state.visibility(actual,false));state.destroyed(actual);
        check(state.ready(other)&&state.takeVisibility(other)==null); // No retired owner's intent crosses owners.
        state.resumed(false);check(state.returnToImmersive());
        state.retire();state.resumed(true);
        check(!state.resumed()&&!state.bootstrap()&&!state.ready(actual)&&!state.returnToImmersive());
        check(!state.visibility(other,false)&&state.takeVisibility(other)==null);
        System.out.println("NativePanelOwnerAttachmentTest passed: late real owner, single bootstrap/return, pause/resume, exact visibility owner and generation retirement");
    }
}
