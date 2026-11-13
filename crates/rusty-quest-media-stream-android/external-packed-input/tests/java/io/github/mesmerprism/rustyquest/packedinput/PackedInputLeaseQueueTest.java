// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.packedinput;

import io.github.mesmerprism.questcompositor.capturebridge.SinkAdmission;
import io.github.mesmerprism.questcompositor.capturebridge.StereoSinkDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PackedInputLeaseQueueTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void rejects(Runnable action) { try { action.run(); } catch (IllegalStateException expected) { return; } throw new AssertionError("accepted invalid action"); }
    static StereoSinkDescriptor descriptor(long generation) {
        return new StereoSinkDescriptor(generation, 2560, 1280, 1, 768, 6,
            new StereoSinkDescriptor.Rect(0,0,1280,1280),new StereoSinkDescriptor.Rect(1280,0,1280,1280),
            StereoSinkDescriptor.Origin.TOP_LEFT,0,false,false);
    }
    static final class Accounting implements PackedInputLeaseQueue.Accounting {
        SinkAdmission state=SinkAdmission.empty().attach(descriptor(7));
        public synchronized long beginCallback() { SinkAdmission.Transition t=state.beginCallback(descriptor(7));state=t.state;return t.token; }
        public synchronized PackedInputLeaseQueue.ImageAdmission imageAcquired(long callback) { SinkAdmission.Transition t=state.imageAcquired(callback);state=t.state;return new PackedInputLeaseQueue.ImageAdmission(t.token,t.publishable); }
        public synchronized void publishCurrent(long image,Runnable action) { state.requireImageCurrent(descriptor(7),image);action.run(); }
        public synchronized long beginFence(long image) { SinkAdmission.Transition t=state.beginFence(image);state=t.state;return t.token; }
        public synchronized void finishCallback(long callback) { state=state.finishCallback(callback); }
        public synchronized void fenceCompleted(long fence) { state=state.fenceCompleted(fence); }
        public synchronized void imageReleased(long image) { state=state.releaseImage(image); }
        public synchronized void invalidate() { state=state.invalidate(7); }
    }
    static final class Resource implements PackedInputLeaseQueue.Resource<String> {
        final long timestamp;int closes;boolean failClose;
        Resource(long timestamp) { this.timestamp=timestamp; }
        public long timestampNs() { return timestamp; }
        public String borrowBuffer() { return "actual-buffer"; }
        public void close() { closes++;if(failClose)throw new IllegalStateException("unknown close"); }
    }
    static final class Fixture {
        final Accounting accounting=new Accounting();
        final PackedInputLeaseQueue<String> queue=new PackedInputLeaseQueue<>(31,7,accounting);
        Resource offer(long timestamp) { Resource r=new Resource(timestamp);queue.onAvailable(()->r);return r; }
    }
    public static void main(String[] args) throws Exception {
        List<String> passed=new ArrayList<>();
        Fixture f=new Fixture();Resource first=f.offer(100);Resource second=f.offer(200);
        check(first.closes==1&&second.closes==0&&f.accounting.state.imageCount()==1);passed.add("bounded latest drops only unclaimed image");
        PackedInputLeaseQueue<String>.Acquisition a=f.queue.takeLatest(7);check(a.sequence()==2&&a.timestampNs()==200&&a.processGeneration==31&&a.readerGeneration==7);
        Resource third=f.offer(300);check(second.closes==0);a.releaseUnsubmitted();check(second.closes==1);passed.add("claimed image survives latest replacement");
        rejects(()->f.queue.takeLatest(8));passed.add("stale generation rejected");
        PackedInputLeaseQueue<String>.Acquisition held=f.queue.takeLatest(7);final boolean[] nativeDone={false};
        PackedInputLeaseQueue<String>.Copy copy=held.beginCopy(()->nativeDone[0]);check(f.accounting.state.fenceCount()==1);
        check(copy.borrowBuffer().equals("actual-buffer"));rejects(copy::borrowBuffer);rejects(held::releaseUnsubmitted);
        check(!copy.pollRetired()&&third.closes==0);passed.add("original Image and buffer held through pending copy; duplicate borrow denied");
        f.queue.stopPublishing();check(f.queue.takeLatest(7)==null);f.queue.seal();check(!f.queue.drained());
        nativeDone[0]=true;check(copy.pollRetired()&&third.closes==1&&f.queue.drained());check(copy.pollRetired());
        check(f.accounting.state.imageCount()==0&&f.accounting.state.fenceCount()==0);passed.add("stop and seal retain copy until actual original fence retires");
        final int[] late={0};f.queue.onAvailable(()->{late[0]++;return new Resource(400);});check(late[0]==0);passed.add("late callback cannot acquire after seal");
        Fixture drain=new Fixture();drain.queue.stopPublishing();Resource dropped=drain.offer(100);check(dropped.closes==1&&drain.queue.takeLatest(7)==null);passed.add("stop publishing continues real queue drain");
        Fixture unknown=new Fixture();Resource uncertain=unknown.offer(100);PackedInputLeaseQueue<String>.Copy pending=unknown.queue.takeLatest(7).beginCopy(()->{throw new IllegalStateException("original fence unknown");});pending.borrowBuffer();rejects(pending::pollRetired);unknown.queue.seal();check(!unknown.queue.drained()&&uncertain.closes==0);passed.add("unknown copy quarantines original image");
        Fixture badClose=new Fixture();Resource fail=badClose.offer(100);fail.failClose=true;PackedInputLeaseQueue<String>.Acquisition unsubmitted=badClose.queue.takeLatest(7);rejects(unsubmitted::releaseUnsubmitted);badClose.queue.seal();check(!badClose.queue.drained()&&fail.closes==1);rejects(unsubmitted::releaseUnsubmitted);check(fail.closes==1);passed.add("unknown close never repeats or invents drain");
        Fixture invalid=new Fixture();Resource bad=invalid.offer(0);check(bad.closes==1&&invalid.queue.failure()!=null&&invalid.queue.takeLatest(7)==null);passed.add("nonpositive actual timestamp cannot become a source frame");
        Fixture racing=new Fixture();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),sealed=new CountDownLatch(1);
        Thread callback=new Thread(()->racing.queue.onAvailable(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}return new Resource(100);}));callback.start();check(entered.await(2,TimeUnit.SECONDS));
        Thread seal=new Thread(()->{racing.queue.seal();sealed.countDown();});seal.start();check(!sealed.await(30,TimeUnit.MILLISECONDS));release.countDown();callback.join(2000);seal.join(2000);check(!callback.isAlive()&&!seal.isAlive()&&racing.queue.drained());passed.add("seal joins actual entered acquisition instead of listener-removal assumption");
        SinkAdmission retired=drain.accounting.state.invalidate(7).readerDestroyed(7).observeDrained().retire();check(retired.attach(descriptor(8)).descriptor.generation==8);rejects(()->retired.attach(descriptor(7)));passed.add("existing Lab reducer requires true reader destruction and newer generation");
        System.out.println("PASS "+passed.size()+" packed reader ownership cases: "+passed);
    }
}
