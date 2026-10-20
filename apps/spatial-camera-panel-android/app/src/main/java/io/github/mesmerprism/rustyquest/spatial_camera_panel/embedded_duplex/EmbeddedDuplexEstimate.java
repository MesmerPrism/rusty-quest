package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Diagnostic estimates never cancel or replace an owned original. */
public final class EmbeddedDuplexEstimate {
    private EmbeddedDuplexEstimate() {}
    public static void warn(String message) {
        try { android.util.Log.w("EmbeddedDuplex", message); }
        catch (Throwable publicationFailure) { /* A diagnostic sink grants no control result. */ }
    }
    public static <T> T await(java.util.concurrent.Future<T> original,long estimate,TimeUnit unit,String label) throws Exception {
        try {
            try{return original.get(estimate,unit);}
            catch(TimeoutException pending){warn(label+" remains Pending on the same original");return original.get();}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
    }
}
