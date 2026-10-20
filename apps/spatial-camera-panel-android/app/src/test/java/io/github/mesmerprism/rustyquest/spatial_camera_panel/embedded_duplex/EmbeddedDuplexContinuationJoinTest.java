package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.os.Bundle;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;

/** Causal tests of the actual producer's original/command/callback join predicate. */
public final class EmbeddedDuplexContinuationJoinTest {
    private EmbeddedDuplexOriginals.Record record()throws Exception{
        EmbeddedDuplexOriginals.Record r=new EmbeddedDuplexOriginals.Record(new JSONObject());
        r.original=new CompletableFuture<Bundle>();return r;
    }
    @Test public void completedPromiseDoesNotJoinStillEnteredCommandFinally()throws Exception{
        EmbeddedDuplexOriginals.Record r=record();EmbeddedDuplexOriginals.commandStageEntered(r);
        r.original.complete(null);r.callbackJoined.complete(null);
        assertFalse(r.continuationWorkerJoined());
        EmbeddedDuplexOriginals.commandStageReturned(r);assertTrue(r.continuationWorkerJoined());
    }
    @Test public void actualDependentCallbackMustReturnToo()throws Exception{
        EmbeddedDuplexOriginals.Record r=record();r.original.completeExceptionally(new IllegalStateException("actual native failure"));
        assertFalse(r.continuationWorkerJoined());r.callbackJoined.complete(null);assertTrue(r.continuationWorkerJoined());
    }
    @Test public void everyEnteredCommandContinuationMustReturn()throws Exception{
        EmbeddedDuplexOriginals.Record r=record();r.original.complete(null);r.callbackJoined.complete(null);
        EmbeddedDuplexOriginals.commandStageEntered(r);EmbeddedDuplexOriginals.commandStageEntered(r);
        EmbeddedDuplexOriginals.commandStageReturned(r);assertFalse(r.continuationWorkerJoined());
        EmbeddedDuplexOriginals.commandStageReturned(r);assertTrue(r.continuationWorkerJoined());
    }
    @Test public void cancelledPromiseDoesNotProveUnderlyingWorkerJoined()throws Exception{
        EmbeddedDuplexOriginals.Record r=record();r.original.cancel(false);r.callbackJoined.complete(null);
        assertFalse(r.continuationWorkerJoined());
    }
    @Test public void failedDependentCallbackCannotProduceJoin()throws Exception{
        EmbeddedDuplexOriginals.Record r=record();r.original.complete(null);
        r.callbackJoined.completeExceptionally(new IllegalStateException("publication callback not joined"));
        assertFalse(r.continuationWorkerJoined());
    }
}
