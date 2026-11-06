package io.github.mesmerprism.rustyquest.native_renderer;

import com.meta.spatial.toolkit.AppSystemActivity;

/** OFF selection: no native owner, recording, audio or load dependency. */
public abstract class SessionOwnerActivity extends AppSystemActivity {
    private static final String OFF="{\"selected\":false,\"status\":\"disabled\"}";
    public final boolean ownerSelected(){return false;}
    public final String ownerBind(long epoch,long instance,long session){return OFF;}
    public final String ownerRevoke(long epoch,boolean terminal){return OFF;}
    public final void ownerPoll(){}
    public final String ownerStatus(){return OFF;}
    public final String ownerAction(String action,int biasPct){return OFF;}
    public final void ownerController(long epoch,long entityId,long sequence,long requestedElapsedNs,long acquiredElapsedNs,
        long flags,boolean active,boolean tracked,boolean rightSecondaryPressed,double x,double y,double z,double qx,double qy,double qz,double qw){}
    protected void onOwnerPresentationRequested(String route){}
}
