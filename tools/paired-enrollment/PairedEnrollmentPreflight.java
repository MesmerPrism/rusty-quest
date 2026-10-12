import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexEnrollmentDraft;
/** Inert selected pair only. This process cannot connect to devices or confirm enrollment. */
public final class PairedEnrollmentPreflight {
    static EmbeddedDuplexEnrollmentDraft selected(String[] args,int i) {
        return new EmbeddedDuplexEnrollmentDraft(args[i],args[i+1],args[i+2],args[i+3],
                args[i+4],args[i+5],args[i+6],Long.parseLong(args[i+7]),false);
    }
    public static void main(String[] args) {
        if(args.length!=16) throw new IllegalArgumentException("two closed draft selections required");
        EmbeddedDuplexEnrollmentDraft.requirePairedCleanupIdentity(selected(args,0),selected(args,8));
        System.out.println("paired cleanup identity preflight passed; no enrollment authority or device effects");
    }
}
