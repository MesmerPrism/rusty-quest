package io.github.mesmerprism.rustyquest;

public final class NativeHeldReviewGateHostTest {
    private static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        if(args.length==1&&args[0].equals("authorization")){authorization();return;}
        if(args.length==1&&args[0].equals("cleanup")){cleanup();return;}
        NativeHeldReviewGate gate=new NativeHeldReviewGate();
        require(gate.enter()); require(!gate.seal(7,7)); gate.leave();
        require(!gate.seal(6,7)); require(!gate.seal(0,7));
        require(gate.enter()); gate.leave();
        require(gate.seal(7,7)); require(!gate.enter()); require(!gate.seal(7,7));
        authorization();cleanup();
        System.out.println("PASS cached Binder close, exact generation and original call join");
    }
    static void authorization() {
        require(NativeHeldReviewGate.authorizeReviewCall(2000,true,true));
        require(!NativeHeldReviewGate.authorizeReviewCall(2000,false,true));
        require(!NativeHeldReviewGate.authorizeReviewCall(2000,true,false));
        for(int uid:new int[]{0,1000,10100,-1})require(!NativeHeldReviewGate.authorizeReviewCall(uid,true,true));
    }
    static void cleanup() {
        NativeHeldReviewGate.Cleanup owner=new NativeHeldReviewGate.Cleanup();
        require(owner.state()==NativeHeldReviewGate.Cleanup.NOT_ATTEMPTED);
        java.util.List<Runnable> queue=new java.util.ArrayList<>();int[] calls={0};
        owner.request(false,r->{queue.add(r);return true;},()->calls[0]++);
        require(owner.state()==NativeHeldReviewGate.Cleanup.PENDING);
        require(owner.await(false,0)==NativeHeldReviewGate.Cleanup.PENDING);
        require(owner.await(true,100)==NativeHeldReviewGate.Cleanup.PENDING);
        require(calls[0]==0);queue.get(0).run();require(calls[0]==1);
        require(owner.await(false,100)==NativeHeldReviewGate.Cleanup.COMPLETE);
        owner.request(true,r->{throw new AssertionError("no original replay");},()->calls[0]++);require(calls[0]==1);
        NativeHeldReviewGate.Cleanup rejected=new NativeHeldReviewGate.Cleanup();queue.clear();
        rejected.request(false,r->{queue.add(r);return false;},()->calls[0]++);
        require(rejected.state()==NativeHeldReviewGate.Cleanup.UNKNOWN);
        queue.get(0).run();require(rejected.state()==NativeHeldReviewGate.Cleanup.UNKNOWN);require(calls[0]==1);
        NativeHeldReviewGate.Cleanup throwing=new NativeHeldReviewGate.Cleanup();
        throwing.request(true,r->true,()->{throw new IllegalStateException("unbind failed");});
        require(throwing.state()==NativeHeldReviewGate.Cleanup.UNKNOWN);
        NativeHeldReviewGate.Cleanup postThrows=new NativeHeldReviewGate.Cleanup();
        postThrows.request(false,r->{throw new IllegalStateException("post failed");},()->calls[0]++);
        require(postThrows.state()==NativeHeldReviewGate.Cleanup.UNKNOWN);
    }
}
