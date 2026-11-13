// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.packedinput;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Physical acquisition ownership. App-side lifecycle accounting is injected from the existing sink owner. */
public final class PackedInputLeaseQueue<B> {
    public interface Resource<B> { long timestampNs(); B borrowBuffer(); void close(); }
    public interface Acquirer<B> { Resource<B> acquireLatest() throws Exception; }
    /** The original copy's actual fence AND same-context import teardown; never a timeout/caller completion flag. */
    public interface CopyCompletion { boolean pollRetired(); }
    public static final class ImageAdmission {
        public final long token;public final boolean publishable;
        public ImageAdmission(long token,boolean publishable) { if(token<=0)throw new IllegalArgumentException("image ticket");this.token=token;this.publishable=publishable; }
    }
    public interface Accounting {
        long beginCallback();ImageAdmission imageAcquired(long callback);
        void publishCurrent(long image,Runnable boundedPublication);long beginFence(long image);
        void finishCallback(long callback);void fenceCompleted(long fence);void imageReleased(long image);void invalidate();
    }
    private final long processGeneration,readerGeneration;
    private final Accounting accounting;
    private final AtomicBoolean publishing=new AtomicBoolean(true);
    private final Map<Long,Acquisition> held=new HashMap<>();
    private final List<Resource<B>> unregistered=new ArrayList<>();
    private Acquisition latest;
    private long sequence,lastTimestamp;
    private boolean sealed;
    private Throwable failure;

    public PackedInputLeaseQueue(long processGeneration,long readerGeneration,Accounting accounting) {
        if(processGeneration<=0||readerGeneration<=0||accounting==null)throw new IllegalArgumentException("actual instance/accounting required");
        this.processGeneration=processGeneration;this.readerGeneration=readerGeneration;this.accounting=accounting;
    }
    /** Actual acquisition body and seal share this gate. Called only by the owned callback worker. */
    public synchronized void onAvailable(Acquirer<B> acquirer) {
        if(sealed)return;
        final long callback;
        try { callback=accounting.beginCallback(); }
        catch(RuntimeException error) { recordFailure(error);return; }
        Acquisition acquired=null;
        try {
            Resource<B> resource=acquirer.acquireLatest();
            if(resource==null)return;
            unregistered.add(resource);
            ImageAdmission admitted=accounting.imageAcquired(callback);
            acquired=new Acquisition(admitted.token,resource);held.put(acquired.token,acquired);
            unregistered.remove(resource);
            long timestamp=resource.timestampNs();
            if(timestamp<=0||timestamp<=lastTimestamp||sequence==Long.MAX_VALUE)throw new IllegalStateException("actual source timestamp/sequence invalid or regressed");
            acquired.timestampNs=timestamp;acquired.sequence=++sequence;lastTimestamp=timestamp;
            if(!publishing.get()||!admitted.publishable) { release(acquired);return; }
            final Acquisition next=acquired;
            Acquisition displaced=latest;
            accounting.publishCurrent(acquired.token,()->{latest=next;});
            if(displaced!=null)release(displaced);
        } catch(Exception error) {
            recordFailure(error);
            if(acquired!=null&&acquired.state==0&&!acquired.closeAttempted) {
                try { release(acquired); } catch(RuntimeException retained) { recordFailure(retained); }
            }
        } finally { accounting.finishCallback(callback); }
    }
    public synchronized Acquisition takeLatest(long expectedGeneration) {
        requireGeneration(expectedGeneration);
        if(!publishing.get()||sealed)return null;
        Acquisition result=latest;latest=null;
        if(result!=null) { requireHeld(result);result.state=1; }
        return result;
    }
    /** Nonblocking control latch. Keep callback acquisition/drain alive until actual producer retirement. */
    public void stopPublishing() { publishing.set(false); }
    /** Worker/control lane only: joins entered acquisition bodies; does not assert external capture retirement. */
    public synchronized void seal() {
        stopPublishing();
        if(sealed)return;
        sealed=true;accounting.invalidate();
        Acquisition displaced=latest;latest=null;
        if(displaced!=null)release(displaced);
    }
    public synchronized boolean drained() { return sealed&&held.isEmpty()&&unregistered.isEmpty(); }
    public synchronized Throwable failure() { return failure; }
    public synchronized int heldCount() { return held.size(); }
    public boolean publishing() { return publishing.get(); }
    private void requireGeneration(long expected) { if(expected!=readerGeneration)throw new IllegalStateException("stale reader generation"); }
    private void requireHeld(Acquisition acquisition) { if(held.get(acquisition.token)!=acquisition)throw new IllegalStateException("unknown/replayed acquisition"); }
    private void recordFailure(Throwable error) { if(failure==null)failure=error;stopPublishing(); }
    private void release(Acquisition acquisition) {
        requireHeld(acquisition);
        if(acquisition.closeAttempted||acquisition.state==2)throw new IllegalStateException("original image close/copy unresolved");
        acquisition.closeAttempted=true;
        try { acquisition.resource.close(); }
        catch(RuntimeException error) { recordFailure(error);throw error; }
        accounting.imageReleased(acquisition.token);held.remove(acquisition.token);acquisition.state=3;
    }
    public final class Acquisition {
        public final long token,processGeneration=PackedInputLeaseQueue.this.processGeneration,readerGeneration=PackedInputLeaseQueue.this.readerGeneration;
        private long sequence,timestampNs;
        private final Resource<B> resource;
        private int state; // queued, claimed, original-copy-owned, released
        private boolean closeAttempted;
        private Acquisition(long token,Resource<B> resource) { this.token=token;this.resource=resource; }
        public long sequence() { return sequence; }
        public long timestampNs() { return timestampNs; }
        public void releaseUnsubmitted() {
            synchronized(PackedInputLeaseQueue.this) {
                requireHeld(this);if(state!=1)throw new IllegalStateException("only unsubmitted claim can release");release(this);
            }
        }
        /** Register before any buffer access/GPU submission. Completion port stays bound to this original copy. */
        public Copy beginCopy(CopyCompletion completion) {
            synchronized(PackedInputLeaseQueue.this) {
                requireHeld(this);
                if(state!=1||sealed||!publishing.get()||completion==null)throw new IllegalStateException("current single copy required");
                long fence=accounting.beginFence(token);state=2;return new Copy(this,fence,completion);
            }
        }
    }
    public final class Copy {
        private final Acquisition acquisition;
        private final long fence;
        private final CopyCompletion completion;
        private final Thread actor=Thread.currentThread();
        private boolean borrowed,retired,quarantined;
        private Copy(Acquisition acquisition,long fence,CopyCompletion completion) { this.acquisition=acquisition;this.fence=fence;this.completion=completion; }
        private void onActor() { if(Thread.currentThread()!=actor)throw new IllegalStateException("wrong original copy actor"); }
        /** Borrowed buffer: caller must not close, replace or outlive this copy's original retirement. */
        public B borrowBuffer() {
            onActor();if(borrowed||retired||quarantined)throw new IllegalStateException("duplicate/retired buffer borrow");borrowed=true;
            try { return acquisition.resource.borrowBuffer(); }
            catch(RuntimeException error) { quarantined=true;synchronized(PackedInputLeaseQueue.this){recordFailure(error);}throw error; }
        }
        public boolean pollRetired() {
            onActor();if(retired)return true;
            if(!borrowed||quarantined)throw new IllegalStateException("original copy custody unresolved");
            final boolean complete;
            try { complete=completion.pollRetired(); }
            catch(RuntimeException error) { quarantined=true;synchronized(PackedInputLeaseQueue.this){recordFailure(error);}throw error; }
            if(!complete)return false;
            synchronized(PackedInputLeaseQueue.this) {
                requireHeld(acquisition);accounting.fenceCompleted(fence);acquisition.state=1;
                try { release(acquisition);retired=true;return true; }
                catch(RuntimeException error) { quarantined=true;throw error; }
            }
        }
    }
}
