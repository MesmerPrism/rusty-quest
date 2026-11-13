// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.packedinput;

import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Parcel;
import android.view.Surface;
import io.github.mesmerprism.questcompositor.capturebridge.SinkAdmission;
import io.github.mesmerprism.questcompositor.capturebridge.SinkPublisherOwner;
import io.github.mesmerprism.questcompositor.capturebridge.StereoSinkDescriptor;

/** Instance-owned packed reader. No vendor API, native pointer transport, recording or witness writer. */
public final class PackedInputReader {
    public static final String ACQUISITION_SYNC="JAVA_IMAGEREADER_SYNC_AOSP_ANDROID_12_AND_14";
    public static final boolean RETURNS_ACQUIRE_FENCE_FD=false;
    public static final String TIMESTAMP_DOMAIN="producer_unverified";
    public static final class OwnedOpenFailure extends Exception {
        private static final long serialVersionUID=1L;
        public final PackedInputReader original;
        private OwnedOpenFailure(PackedInputReader original,Exception cause) { super("original packed reader initialization failed; retain its custody",cause);this.original=original; }
    }
    private final long processGeneration;
    private final StereoSinkDescriptor descriptor;
    private final SinkPublisherOwner owner;
    private ImageReader reader;
    private Surface originalSurface;
    private HandlerThread worker;
    private Handler handler;
    private PackedInputLeaseQueue<HardwareBuffer> queue;
    private PackedReaderRetirement retirement;
    private volatile boolean initialized,listenerAttempted;
    private boolean attached,partialCloseRequested,partialCloseAttempted,partialClosed;
    private volatile Throwable startupFailure;
    private long actualUsage=Long.MIN_VALUE;

    private PackedInputReader(long processGeneration,StereoSinkDescriptor descriptor,SinkPublisherOwner owner) {
        if(processGeneration<=0||descriptor==null||owner==null||descriptor.width!=2560||descriptor.height!=1280||
            descriptor.origin!=StereoSinkDescriptor.Origin.TOP_LEFT||descriptor.quarterTurns!=0||descriptor.reflectX||descriptor.reflectY)
            throw new IllegalArgumentException("initial packed2560x1280 identity SBS reader/instance required");
        this.processGeneration=processGeneration;this.descriptor=descriptor;this.owner=owner;
    }
    /** Android API29+ typing; sync semantics source-qualified only for the documented AOSP12/14 paths. */
    public static PackedInputReader open(long processGeneration,StereoSinkDescriptor descriptor,SinkPublisherOwner sharedOwner) throws OwnedOpenFailure {
        PackedInputReader result=new PackedInputReader(processGeneration,descriptor,sharedOwner);
        try { result.initialize();return result; }
        catch(Exception error) { result.startupFailure=error;if(result.queue!=null)result.queue.stopPublishing();throw new OwnedOpenFailure(result,error); }
    }
    private void initialize() {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("open on owned control worker, not Activity main");
        synchronized(owner) {
            SinkAdmission prior=owner.snapshot();
            if(prior.phase!=SinkAdmission.Phase.UNBOUND&&prior.phase!=SinkAdmission.Phase.RETIRED)
                throw new IllegalStateException("previous sink not retired");
            if(prior.descriptor!=null&&descriptor.generation<=prior.descriptor.generation)
                throw new IllegalStateException("reader generation must increase");
            reader=ImageReader.newInstance(descriptor.width,descriptor.height,descriptor.imageFormat,descriptor.maxImages,descriptor.usage);
            originalSurface=reader.getSurface();
            if(originalSurface==null||!originalSurface.isValid())throw new IllegalStateException("actual reader Surface unavailable");
            owner.attach(descriptor,expected->ownedSurface(expected.generation));attached=true;
        }
        queue=new PackedInputLeaseQueue<>(processGeneration,descriptor.generation,new AccountingAdapter());
        retirement=new PackedReaderRetirement(queue,new OriginalResources());
        worker=new HandlerThread("packed-input-acquire");worker.start();
        Looper looper=worker.getLooper();if(looper==null)throw new IllegalStateException("original worker Looper unavailable");
        handler=new Handler(looper);listenerAttempted=true;
        reader.setOnImageAvailableListener(observed->{
            if(observed!=reader) { queue.stopPublishing();return; }
            queue.onAvailable(()->{
                if(queue.heldCount()>=descriptor.maxImages-1)return null; // Preserve acquisition margin; never steal a claimed/copy-held Image.
                Image image=reader.acquireLatestImage(); // Original synchronous call; may block on its real producer fence.
                return image==null?null:new ImageResource(image);
            });
        },handler);
        initialized=true;
    }
    public long processGeneration() { return processGeneration; }
    public StereoSinkDescriptor descriptor() { return descriptor; }
    public PackedInputLeaseQueue<HardwareBuffer>.Acquisition takeLatest(long generation) {
        if(!initialized)throw new IllegalStateException("original reader initialization incomplete");return queue.takeLatest(generation);
    }
    /** Fresh separately owned wrapper, synchronously serialized with the existing owner invalidation. */
    public Surface ownedSurface(long generation) {
        synchronized(owner) {
            if(!initialized||!attached||generation!=descriptor.generation||originalSurface==null||!queue.publishing())throw new IllegalStateException("exact current reader generation required");
            owner.snapshot().requireCurrent(descriptor);
            Parcel parcel=Parcel.obtain();Surface copy=null;
            try {
                originalSurface.writeToParcel(parcel,0);parcel.setDataPosition(0);copy=Surface.CREATOR.createFromParcel(parcel);
                owner.snapshot().requireCurrent(descriptor);
                if(copy==null||!copy.isValid())throw new IllegalStateException("owned Surface wrapper invalid");
                return copy;
            } catch(RuntimeException error) { if(copy!=null)copy.release();owner.invalidate(descriptor.generation);throw error; }
            finally { parcel.recycle(); }
        }
    }
    /** Stops publication immediately; acquisition remains alive to drain the producer while its Stop is pending. */
    public void stopPublishing() { if(queue!=null)queue.stopPublishing(); }
    public void invalidatePublication() { stopPublishing();synchronized(owner){if(owner.snapshot().isCurrent(descriptor))owner.invalidate(descriptor.generation);} }
    /** External producer retirement is independently owned. This method neither stops nor acknowledges it. */
    public synchronized void requestReaderCloseAfterProducerRetired() {
        stopPublishing();
        if(retirement!=null)retirement.request();else partialCloseRequested=true;
    }
    public synchronized boolean pollClosed() {
        if(retirement!=null)return retirement.pollClosed();
        if(partialClosed)return true;
        if(!partialCloseRequested||partialCloseAttempted)return false;
        partialCloseAttempted=true;
        try {
            // No queue/worker/listener existed on this partial initialization path; no wrapper was returned.
            if(reader!=null)reader.close();
            if(attached) { owner.invalidate(descriptor.generation);owner.readerDestroyed(descriptor.generation);owner.observeDrained();owner.retire(); }
            partialClosed=true;return true;
        } catch(RuntimeException error) { startupFailure=error;return false; }
    }
    public Throwable failure() {
        if(startupFailure!=null)return startupFailure;
        if(retirement!=null&&retirement.failure()!=null)return retirement.failure();
        return queue==null?null:queue.failure();
    }
    private final class ImageResource implements PackedInputLeaseQueue.Resource<HardwareBuffer> {
        private final Image image;
        private HardwareBuffer buffer;
        private boolean bufferClosed;
        ImageResource(Image image) { this.image=image; }
        public long timestampNs() {
            buffer=image.getHardwareBuffer();
            if(buffer==null||buffer.isClosed()||image.getWidth()!=descriptor.width||image.getHeight()!=descriptor.height||
                image.getFormat()!=descriptor.imageFormat||buffer.getWidth()!=descriptor.width||buffer.getHeight()!=descriptor.height||
                buffer.getFormat()!=HardwareBuffer.RGBA_8888||buffer.getLayers()!=1||
                (buffer.getUsage()&descriptor.usage)!=descriptor.usage)
                throw new IllegalStateException("actual Image/AHB descriptor differs");
            android.graphics.Rect crop=image.getCropRect();
            if(crop.left!=0||crop.top!=0||crop.right!=descriptor.width||crop.bottom!=descriptor.height)
                throw new IllegalStateException("unexpected packed Image crop");
            if(actualUsage==Long.MIN_VALUE)actualUsage=buffer.getUsage();
            else if(actualUsage!=buffer.getUsage())throw new IllegalStateException("immutable actual AHB usage changed");
            return image.getTimestamp();
        }
        public HardwareBuffer borrowBuffer() { if(buffer==null||buffer.isClosed())throw new IllegalStateException("original AHB unavailable");return buffer; }
        public void close() {
            if(buffer!=null&&!bufferClosed) { buffer.close();bufferClosed=true; }
            image.close();
        }
    }
    private final class AccountingAdapter implements PackedInputLeaseQueue.Accounting {
        public long beginCallback() { return owner.beginCallback(descriptor); }
        public PackedInputLeaseQueue.ImageAdmission imageAcquired(long callback) { SinkPublisherOwner.ImageAdmission value=owner.imageAcquired(callback);return new PackedInputLeaseQueue.ImageAdmission(value.token,value.publishable); }
        public void publishCurrent(long image,Runnable action) { owner.publishCurrent(descriptor,image,action); }
        public long beginFence(long image) { return owner.beginFence(image); }
        public void finishCallback(long callback) { owner.finishCallback(callback); }
        public void fenceCompleted(long fence) { owner.fenceCompleted(fence); }
        public void imageReleased(long image) { owner.imageReleased(image); }
        public void invalidate() { owner.invalidate(descriptor.generation); }
    }
    private final class OriginalResources implements PackedReaderRetirement.Resources {
        public boolean postSeal(Runnable action) {
            if(handler!=null)return handler.post(action);
            if(listenerAttempted)throw new IllegalStateException("original listener dispatch unknown");
            action.run();return true; // Proven pre-listener partial initialization, not a replacement worker.
        }
        public void detachListener() { if(reader!=null)reader.setOnImageAvailableListener(null,null); }
        public boolean quitOriginalWorkerSafely() { return worker==null||!worker.isAlive()||worker.quitSafely(); }
        public boolean originalWorkerAlive() { return worker!=null&&worker.isAlive(); }
        public void joinTerminatedOriginalWorker() throws InterruptedException { if(worker!=null)worker.join(); }
        public void closeOriginalReader() { reader.close(); }
        public void observeReaderDestroyedAndRetired() { owner.readerDestroyed(descriptor.generation);owner.observeDrained();owner.retire(); }
    }
}
