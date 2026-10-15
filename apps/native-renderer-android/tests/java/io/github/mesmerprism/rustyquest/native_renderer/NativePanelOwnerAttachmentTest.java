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
        // Quest can retain both Activities RESUMED throughout the bootstrap.
        NativeMeshUiProducer.OwnerAttachmentState multi=new NativeMeshUiProducer.OwnerAttachmentState();
        check(multi.bootstrap());multi.resumed(true);check(multi.ready(actual));
        check(multi.returnToImmersive()); // Old resumed-as-focus rule rejects this real obligation.
        check(!multi.returnToImmersive());
        NativeMeshUiProducer.OwnerAttachmentState callbacks=new NativeMeshUiProducer.OwnerAttachmentState();
        check(callbacks.bootstrap());callbacks.resumed(true);
        check(callbacks.ready(actual)&&callbacks.visibility(actual,false)); // Earlier explicit request survives.
        final int[] originalCalls={0},attachments={0};final boolean[] attached={false};
        final Runnable attach=new Runnable(){public void run(){if(!attached[0]){attached[0]=true;attachments[0]++;}}};
        callbacks.ownerReady(actual,new Runnable(){public void run(){
            originalCalls[0]++;
            NativeMeshUiProducer.BootstrapReturnScope scope=new NativeMeshUiProducer.BootstrapReturnScope(actual);
            check(!scope.bypass(other,false)&&!scope.bypass(actual,true));
            check(scope.bypass(actual,false)); // Only the automatic facade shortcut falls through.
            check(!scope.bypass(actual,false)); // A later external false remains a real request.
            check(Boolean.FALSE.equals(callbacks.takeVisibility(actual))); // No blanket-clear or overwrite.
            // Actual application callbacks can attach before the original call returns.
            callbacks.ownerReady(actual,new Runnable(){public void run(){throw new AssertionError("handoff replay");}},attach);
        }},attach);
        check(originalCalls[0]==1&&attachments[0]==1&&attached[0]);
        callbacks.resumed(false);callbacks.resumed(true);
        callbacks.ownerReady(actual,new Runnable(){public void run(){throw new AssertionError("late handoff replay");}},attach);
        check(originalCalls[0]==1&&attachments[0]==1);
        callbacks.ownerReady(other,new Runnable(){public void run(){throw new AssertionError("foreign owner");}},new Runnable(){public void run(){throw new AssertionError("foreign attach");}});
        callbacks.retire();
        callbacks.ownerReady(actual,new Runnable(){public void run(){throw new AssertionError("retired return");}},attach);
        System.out.println("NativePanelOwnerAttachmentTest passed: late real owner, single bootstrap/return, pause/resume, exact visibility owner and generation retirement");
    }
}
