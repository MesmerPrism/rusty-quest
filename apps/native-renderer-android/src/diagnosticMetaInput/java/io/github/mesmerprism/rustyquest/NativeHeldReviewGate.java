package io.github.mesmerprism.rustyquest;

/** Joins legacy synchronous calls before irrevocably closing cached Binders. */
final class NativeHeldReviewGate {
    static boolean authorizeReviewCall(int uid,boolean dump,boolean synchronous) {return uid==2000&&dump&&synchronous;}
    interface Dispatch {boolean post(Runnable action);}
    interface Action {void run() throws Exception;}
    static final class Cleanup {
        static final int NOT_ATTEMPTED=0,PENDING=1,COMPLETE=2,UNKNOWN=3;
        private int result=NOT_ATTEMPTED;
        private boolean originalStarted;
        synchronized int state(){return result;}
        void request(boolean main,Dispatch dispatch,Action action) {
            synchronized(this){if(result!=NOT_ATTEMPTED)return;result=PENDING;}
            Runnable original=()->{
                synchronized(this){if(result!=PENDING||originalStarted)return;originalStarted=true;}
                try {
                    action.run();
                    synchronized(this){if(result==PENDING)result=COMPLETE;notifyAll();}
                } catch(Exception error){unknown();}
            };
            try {if(main)original.run();else if(!dispatch.post(original))unknown();}
            catch(RuntimeException error){unknown();}
        }
        private synchronized void unknown(){result=UNKNOWN;notifyAll();}
        synchronized int await(boolean main,long millis){
            if(main||millis<=0)return result;
            long end=System.nanoTime()+Math.min(millis,2000)*1000000L;
            while(result==PENDING){
                long remaining=end-System.nanoTime();if(remaining<=0)break;
                try{wait(remaining/1000000L,(int)(remaining%1000000L));}
                catch(InterruptedException error){Thread.currentThread().interrupt();unknown();break;}
            }
            return result;
        }
    }
    private boolean sealed;
    private int originalCalls;
    synchronized boolean enter() {
        if (sealed) return false;
        originalCalls++; return true;
    }
    synchronized void leave() {
        if (originalCalls <= 0) throw new IllegalStateException("legacy call ownership");
        originalCalls--;
    }
    synchronized boolean seal(long expected, long current) {
        if (sealed || expected <= 0 || expected != current || originalCalls != 0) return false;
        sealed=true; return true;
    }
}
