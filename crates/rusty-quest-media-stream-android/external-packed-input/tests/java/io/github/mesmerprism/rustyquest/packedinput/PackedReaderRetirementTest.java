// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.packedinput;

public final class PackedReaderRetirementTest {
    static final class Ports implements PackedReaderRetirement.Resources {
        Runnable pending;int posts,joins,closes,terminals;boolean alive=true,unknownPost,failClose;
        public boolean postSeal(Runnable action) { posts++;pending=action;if(unknownPost)throw new IllegalStateException("post outcome unknown");return true; }
        public void detachListener() { }
        public boolean quitOriginalWorkerSafely() { return true; }
        public boolean originalWorkerAlive() { return alive; }
        public void joinTerminatedOriginalWorker() { joins++;if(alive)throw new AssertionError(); }
        public void closeOriginalReader() { closes++;if(failClose)throw new IllegalStateException("reader close unknown"); }
        public void observeReaderDestroyedAndRetired() { terminals++; }
    }
    static void check(boolean value) { if(!value)throw new AssertionError(); }
    public static void main(String[] args) {
        PackedInputLeaseQueueTest.Fixture f=new PackedInputLeaseQueueTest.Fixture();PackedInputLeaseQueueTest.Resource r=f.offer(100);
        boolean[] completed={false};PackedInputLeaseQueue<String>.Copy copy=f.queue.takeLatest(7).beginCopy(()->completed[0]);copy.borrowBuffer();
        Ports p=new Ports();PackedReaderRetirement close=new PackedReaderRetirement(f.queue,p);close.request();check(!close.pollClosed()&&p.closes==0&&p.posts==1);
        p.pending.run();check(!close.pollClosed()&&p.closes==0);p.alive=false;check(!close.pollClosed()&&p.joins==1&&p.closes==0);
        completed[0]=true;copy.pollRetired();check(close.pollClosed()&&p.closes==1&&p.terminals==1&&r.closes==1);check(close.pollClosed());close.request();check(p.posts==1&&p.closes==1);
        PackedInputLeaseQueueTest.Fixture unknown=new PackedInputLeaseQueueTest.Fixture();Ports post=new Ports();post.unknownPost=true;PackedReaderRetirement held=new PackedReaderRetirement(unknown.queue,post);held.request();held.request();check(held.failure()!=null&&!held.pollClosed()&&post.posts==1&&post.closes==0);
        PackedInputLeaseQueueTest.Fixture failure=new PackedInputLeaseQueueTest.Fixture();Ports reader=new Ports();reader.failClose=true;PackedReaderRetirement uncertain=new PackedReaderRetirement(failure.queue,reader);uncertain.request();reader.pending.run();reader.alive=false;check(!uncertain.pollClosed()&&uncertain.failure()!=null&&reader.closes==1&&reader.terminals==0);check(!uncertain.pollClosed()&&reader.closes==1);
        PackedInputLeaseQueueTest.Fixture draining=new PackedInputLeaseQueueTest.Fixture();draining.queue.stopPublishing();
        PackedInputLeaseQueueTest.Resource cleanup=draining.offer(100);check(cleanup.closes==1&&draining.accounting.state.phase==io.github.mesmerprism.questcompositor.capturebridge.SinkAdmission.Phase.ACTIVE&&draining.accounting.state.callbackCount()==0&&draining.accounting.state.imageCount()==0);
        Ports end=new Ports();PackedReaderRetirement finalClose=new PackedReaderRetirement(draining.queue,end);finalClose.request();check(end.closes==0);end.pending.run();check(draining.accounting.state.phase==io.github.mesmerprism.questcompositor.capturebridge.SinkAdmission.Phase.INVALIDATED);end.alive=false;check(finalClose.pollClosed()&&end.joins==1&&end.closes==1);
        System.out.println("PASS 4 original reader/worker retirement cases: pending copy + actual original barrier/join; unknown post no replay; failed close no false retirement; Stop drains with real ACTIVE tickets then external-retirement seal/close");
    }
}
