package android.os;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Modeled Android endpoint; records synchronous nesting on the actual caller thread. */
public final class Trace {
    public static volatile boolean enabled;
    public static volatile int checks;
    public static volatile CountDownLatch capacityEntered;
    private static final ThreadLocal<ArrayDeque<String>> stack=ThreadLocal.withInitial(ArrayDeque::new);
    private static final List<String> events=new ArrayList<>();
    private Trace() {}
    public static synchronized boolean isEnabled(){checks++;return enabled;}
    public static void beginSection(String name){
        stack.get().push(name);
        synchronized(Trace.class){events.add("B:"+Thread.currentThread().getName()+":"+name);}
        if(name.equals("rusty.media.sender.capacity_wait")&&capacityEntered!=null)capacityEntered.countDown();
    }
    public static void endSection(){
        String name=stack.get().poll();
        if(name==null)throw new AssertionError("unmatched Trace.endSection");
        synchronized(Trace.class){events.add("E:"+Thread.currentThread().getName()+":"+name);}
    }
    public static String current(){return stack.get().peek();}
    public static synchronized List<String> snapshot(){return new ArrayList<>(events);}
    public static synchronized void reset(boolean on){enabled=on;checks=0;capacityEntered=null;events.clear();}
}
