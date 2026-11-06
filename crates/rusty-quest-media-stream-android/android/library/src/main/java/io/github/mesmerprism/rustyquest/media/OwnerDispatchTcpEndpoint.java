package io.github.mesmerprism.rustyquest.media;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded framing only. The injected native handler authenticates every action. */
public final class OwnerDispatchTcpEndpoint implements AutoCloseable {
    public interface Handler { byte[] handle(byte[] requestFrame) throws Exception; }
    /** Actual accepted request task ownership, not an ID inferred from untrusted frame bytes. */
    public static final class RequestSpan {
        private final OwnerDispatchTcpEndpoint owner;private final Socket socket;
        private final java.util.concurrent.CompletableFuture<Void> joined=new java.util.concurrent.CompletableFuture<>();
        private volatile boolean handlerReturned;
        private RequestSpan(OwnerDispatchTcpEndpoint owner,Socket socket){this.owner=owner;this.socket=socket;}
        public boolean stopRequested(){return owner.stopping;}
        public boolean terminal(){return handlerReturned && socket.isClosed() && joined.isDone() && !joined.isCompletedExceptionally();}
    }
    private static final ThreadLocal<RequestSpan> CURRENT_REQUEST=new ThreadLocal<>();
    public static RequestSpan currentRequestSpan(){return CURRENT_REQUEST.get();}
    public boolean ownsRequestSpan(RequestSpan actual){return actual!=null && actual.owner==this;}
    /** Snapshot only. Actual request Futures/socket finally blocks own joins. */
    public boolean requestsQuiescent(){return sockets.isEmpty() && workers.getActiveCount()==0 && workers.getQueue().isEmpty();}

    public enum Phase { NONE, TARGET_BINDING, INPUT_BOUNDS, OWNED_CONTROL, CONNECT, WRITE_REQUEST,
        READ_RESPONSE_HEADER, READ_RESPONSE_BODY, RESPONSE_RETURNED }
    public enum Outcome { NONE, IN_PROGRESS, RETURNED, FAILED, UNAVAILABLE }
    public enum Failure { NONE, TARGET_UNAVAILABLE, INPUT_BOUNDS, DESIRED_STOP, CONNECT_FAILURE,
        DEADLINE, EOF, FRAME_BOUNDS, IO, CALLBACK_REJECTED }
    public interface ExchangeObserver { void observe(Phase phase, Outcome outcome, Failure failure); }
    /** One actual outbound original. Desired Stop is thread-safe transport cancellation, never native cleanup. */
    public static final class ExchangeControl {
        private Socket socket;private boolean stopRequested,entered;
        private final java.util.concurrent.CompletableFuture<Void> joined=new java.util.concurrent.CompletableFuture<>();
        private synchronized void attach(Socket actual)throws IOException{
            if(entered)throw new IllegalStateException("outbound exchange replay");entered=true;socket=actual;
            if(stopRequested){actual.close();throw new java.net.SocketException("owned exchange desired Stop");}
        }
        public synchronized void requestStop(){
            stopRequested=true;
            if(socket!=null)try{socket.close();}catch(IOException retained){}
        }
        public synchronized boolean stopRequested(){return stopRequested;}
        public java.util.concurrent.CompletableFuture<Void> joined(){return joined;}
        private void finished(){synchronized(this){socket=null;}joined.complete(null);}
    }
    private static final class ExchangeTrace {
        private final ExchangeObserver observer;
        private final String correlation;
        private Phase phase = Phase.TARGET_BINDING;
        private volatile boolean deadlineExpired;
        ExchangeTrace(ExchangeObserver observer,byte[] frame) { this.observer = observer;correlation=frameCorrelation(frame); }
        void enter(Phase value) { phase = value; publish(Outcome.IN_PROGRESS, Failure.NONE); }
        void publish(Outcome outcome, Failure failure) {
            if (observer != null) try { observer.observe(phase, outcome, failure); }
            catch (RuntimeException ignored) { /* Observation never changes transport. */ }
        }
        void failed(Exception error) { failed(error,false); }
        void failed(Exception error,boolean desiredStop) {
            boolean ownedControl = phase == Phase.OWNED_CONTROL || phase == Phase.CONNECT || phase == Phase.WRITE_REQUEST
                    || phase == Phase.READ_RESPONSE_HEADER || phase == Phase.READ_RESPONSE_BODY;
            Failure cause = desiredStop && ownedControl && error instanceof IOException && !(error instanceof EOFException)
                    && !(error instanceof FrameBoundsException) ? Failure.DESIRED_STOP : deadlineExpired ? Failure.DEADLINE : error instanceof EOFException ? Failure.EOF
                    : error instanceof SocketTimeoutException ? Failure.DEADLINE
                    : error instanceof ConnectException ? Failure.CONNECT_FAILURE
                    : phase == Phase.TARGET_BINDING ? Failure.TARGET_UNAVAILABLE
                    : phase == Phase.INPUT_BOUNDS ? Failure.INPUT_BOUNDS
                    : error instanceof FrameBoundsException ? Failure.FRAME_BOUNDS
                    : error instanceof IOException ? Failure.IO : Failure.CALLBACK_REJECTED;
            publish(Outcome.FAILED, cause);
            logFailure("OUTBOUND",phase.name(),cause,error,correlation);
        }
    }
    private static final class FrameBoundsException extends IOException {
        private static final long serialVersionUID = 1L;
        FrameBoundsException() { super("owner frame bound"); }
    }
    public static final int MAX_FRAME_BYTES = 128 * 1024;
    private static final int IO_TIMEOUT_MS = 30_000;
    private static final int SHUTDOWN_TIMEOUT_MS = 10_000;
    private final ServerSocket listener;
    private final Thread acceptThread;
    private final ThreadPoolExecutor workers;
    private final Handler handler;
    private final boolean retainAcceptedOwnerTasks;
    private final ScheduledThreadPoolExecutor deadlines = deadlineExecutor();
    private final ConcurrentHashMap<Socket, ScheduledFuture<?>> socketDeadlines = new ConcurrentHashMap<>();
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile boolean stopping;
    private volatile String failure = "";

    public OwnerDispatchTcpEndpoint(InetAddress localAddress, int port, Handler handler) throws IOException {
        this(localAddress, port, handler, false);
    }
    /** Explicit fresh-owner task retention; ordinary endpoint timing remains unchanged. */
    public OwnerDispatchTcpEndpoint(InetAddress localAddress, int port, Handler handler, boolean retainAcceptedOwnerTasks) throws IOException {
        if (localAddress == null || port < 0 || port > 65535 || handler == null) {
            throw new IllegalArgumentException("owner control endpoint binding");
        }
        this.handler = handler;
        this.retainAcceptedOwnerTasks = retainAcceptedOwnerTasks;
        workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(8), runnable -> {
                    Thread thread = new Thread(runnable, "rusty-owner-control-effect");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(false);
            listener.bind(new InetSocketAddress(localAddress, port), 8);
        } catch (IOException failed) {
            listener.close();
            workers.shutdownNow();
            deadlines.shutdownNow();
            throw failed;
        }
        acceptThread = new Thread(this::accept, "rusty-owner-control-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public int port() { return listener.getLocalPort(); }
    public boolean ready() { return !stopping && !listener.isClosed() && acceptThread.isAlive() && failure.isEmpty(); }
    public boolean terminal() { return stopping && listener.isClosed() && !acceptThread.isAlive()
            && workers.isTerminated() && deadlines.isTerminated() && sockets.isEmpty(); }

    private void accept() {
        try {
            while (!stopping) {
                Socket socket = listener.accept();
                if (stopping) { socket.close(); break; }
                sockets.add(socket);
                try {
                    socketDeadlines.put(socket, deadlines.schedule(() -> closeSocket(socket),
                            IO_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                    RequestSpan span=new RequestSpan(this,socket);
                    java.util.concurrent.FutureTask<Void> actual=new java.util.concurrent.FutureTask<Void>(()->{
                        CURRENT_REQUEST.set(span);
                        try{serve(socket);return null;}
                        finally{CURRENT_REQUEST.remove();span.handlerReturned=true;}
                    }){
                        @Override protected void done(){
                            try{get();if(!socket.isClosed())throw new IllegalStateException("request socket not joined");span.joined.complete(null);}
                            catch(Throwable failure){span.joined.completeExceptionally(failure);}
                        }
                    };
                    workers.execute(actual);
                }
                catch (RejectedExecutionException full) { closeSocket(socket); }
            }
        } catch (SocketException closed) {
            if (!stopping) failure = "accept-socket-failed";
        } catch (IOException failed) {
            if (!stopping) failure = "accept-io-failed";
        }
    }

    private void serve(Socket socket) {
        InboundPhase phase=InboundPhase.READ_REQUEST;
        String correlation="NONE";
        try {
            socket.setSoTimeout(IO_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            byte[] request = readFrame(socket);
            correlation=frameCorrelation(request);
            if (stopping) return;
            // Incomplete unauthenticated input retains its finite framing policy. A fully received
            // native-dispatched original is retained while its actual handler finishes.
            if (retainAcceptedOwnerTasks) {
                ScheduledFuture<?> inputDeadline=socketDeadlines.remove(socket);
                if(inputDeadline!=null)inputDeadline.cancel(false);
                socketDeadlines.put(socket,deadlines.schedule(()->warnPending("same owner handler remains Pending"),IO_TIMEOUT_MS,TimeUnit.MILLISECONDS));
            }
            // No endpoint monitor is held across native authority or platform work.
            phase=InboundPhase.HANDLE_REQUEST;
            byte[] response = handler.handle(request);
            phase=InboundPhase.WRITE_RESPONSE;
            writeFrame(new DataOutputStream(socket.getOutputStream()), response);
        } catch (Exception failed) {
            // A lost reply is uncertainty. Native replay state retains the obligation;
            // transport must never synthesize a successful owner receipt.
            Failure reason=phase==InboundPhase.HANDLE_REQUEST?Failure.CALLBACK_REJECTED
                    :failed instanceof FrameBoundsException?Failure.FRAME_BOUNDS
                    :failed instanceof EOFException?Failure.EOF
                    :stopping && failed instanceof IOException?Failure.DESIRED_STOP
                    :failed instanceof SocketTimeoutException?Failure.DEADLINE
                    :failed instanceof IOException?Failure.IO:Failure.CALLBACK_REJECTED;
            logFailure("INBOUND",phase.name(),reason,failed,correlation);
        } finally { closeSocket(socket); }
    }

    /** The caller must obtain the exact address from current accepted route authority. */
    public static byte[] exchange(InetAddress targetAddress, int targetPort, byte[] frame) throws IOException {
        return exchange(targetAddress, targetPort, frame, null);
    }

    /** Finite local transport observation only; a returned frame is not an authenticated receipt. */
    public static byte[] exchange(InetAddress targetAddress, int targetPort, byte[] frame,
            ExchangeObserver observer) throws IOException {
        ExchangeTrace trace = new ExchangeTrace(observer,frame);
        try {
            trace.enter(Phase.TARGET_BINDING);
            if (targetAddress == null || targetPort <= 0 || targetPort > 65535) {
                throw new IllegalArgumentException("owner control target");
            }
            trace.enter(Phase.INPUT_BOUNDS);
            requireFrame(frame);
            ScheduledThreadPoolExecutor timeout = deadlineExecutor();
            try (Socket socket = new Socket()) {
                timeout.schedule(() -> { trace.deadlineExpired = true; closeTimedSocket(socket); }, IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                trace.enter(Phase.CONNECT);
                socket.connect(new InetSocketAddress(targetAddress, targetPort), 3_000);
                socket.setSoTimeout(IO_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                trace.enter(Phase.WRITE_REQUEST);
                writeFrame(new DataOutputStream(socket.getOutputStream()), frame);
                byte[] response = readFrame(socket, trace);
                trace.phase = Phase.RESPONSE_RETURNED;
                trace.publish(Outcome.RETURNED, Failure.NONE);
                return response;
            } finally { timeout.shutdownNow(); }
        } catch (IOException | RuntimeException error) {
            trace.failed(error);
            throw error;
        }
    }

    /** Existing app owner supplies this exact control before connect; estimates publish warnings only. */
    public static byte[] exchangeOwned(InetAddress address,int port,byte[] frame,ExchangeObserver observer,ExchangeControl control)throws IOException{
        return exchangeOwnedObserved(address,port,frame,observer,control,3000,IO_TIMEOUT_MS,OwnerDispatchTcpEndpoint::warnPending);
    }
    interface EstimatePublisher{void publish(String label);}
    /** Owner-package qualification seam: warning timing/publication only, never protocol authority or cancellation. */
    static byte[] exchangeOwnedObserved(InetAddress address,int port,byte[] frame,ExchangeObserver observer,ExchangeControl control,
            long connectEstimateMs,long frameEstimateMs,EstimatePublisher publisher)throws IOException{
        if(control==null || publisher==null || connectEstimateMs<0 || frameEstimateMs<0)throw new IllegalArgumentException("owned exchange control");
        ExchangeTrace trace=new ExchangeTrace(observer,frame);ScheduledThreadPoolExecutor estimates=deadlineExecutor();
        try(Socket socket=new Socket()){
            trace.enter(Phase.TARGET_BINDING);
            if(address==null || port<=0 || port>65535)throw new IllegalArgumentException("owner control target");
            trace.enter(Phase.INPUT_BOUNDS);requireFrame(frame);
            trace.enter(Phase.OWNED_CONTROL);control.attach(socket);
            ScheduledFuture<?> connectEstimate=estimates.schedule(()->publishEstimate(publisher,"same owner connect remains Pending"),connectEstimateMs,TimeUnit.MILLISECONDS);
            trace.enter(Phase.CONNECT);socket.connect(new InetSocketAddress(address,port));connectEstimate.cancel(false);
            socket.setSoTimeout(0);socket.setTcpNoDelay(true);
            estimates.schedule(()->publishEstimate(publisher,"same outbound owner frame remains Pending"),frameEstimateMs,TimeUnit.MILLISECONDS);
            if(control.stopRequested())throw new java.net.SocketException("owned exchange desired Stop");
            trace.enter(Phase.WRITE_REQUEST);writeFrame(new DataOutputStream(socket.getOutputStream()),frame);
            trace.enter(Phase.READ_RESPONSE_HEADER);byte[] header=new byte[4];readOwnedExactly(socket,header);
            int length=ByteBuffer.wrap(header).getInt();if(length<=0 || length>MAX_FRAME_BYTES)throw new FrameBoundsException();
            trace.enter(Phase.READ_RESPONSE_BODY);byte[] response=new byte[length];readOwnedExactly(socket,response);
            trace.phase=Phase.RESPONSE_RETURNED;trace.publish(Outcome.RETURNED,Failure.NONE);return response;
        }catch(IOException|RuntimeException failure){trace.failed(failure,control.stopRequested());throw failure;}
        finally{
            estimates.shutdownNow();
            boolean interrupted=false;
            for(;;)try{if(estimates.awaitTermination(Long.MAX_VALUE,TimeUnit.NANOSECONDS))break;}
                catch(InterruptedException actual){interrupted=true;control.requestStop();}
            control.finished();if(interrupted)Thread.currentThread().interrupt();
        }
    }
    private static void publishEstimate(EstimatePublisher publisher,String label){
        try{publisher.publish(label);}catch(Throwable diagnosticFailure){/* Publication cannot replace the same actual exchange. */}
    }
    private static void readOwnedExactly(Socket socket,byte[] target)throws IOException{
        java.io.InputStream input=socket.getInputStream();int offset=0;
        while(offset<target.length){int count=input.read(target,offset,target.length-offset);
            if(count<0)throw new EOFException("owner frame truncated");offset+=count;}
    }
    private static byte[] readFrame(Socket socket) throws IOException { return readFrame(socket, null); }
    private static byte[] readFrame(Socket socket, ExchangeTrace trace) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IO_TIMEOUT_MS);
        byte[] header = new byte[4];
        if (trace != null) trace.enter(Phase.READ_RESPONSE_HEADER);
        readExactly(socket, header, deadline);
        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) throw new FrameBoundsException();
        byte[] frame = new byte[length];
        if (trace != null) trace.enter(Phase.READ_RESPONSE_BODY);
        readExactly(socket, frame, deadline);
        return frame;
    }

    private static void readExactly(Socket socket, byte[] target, long deadline) throws IOException {
        InputStream input = socket.getInputStream();
        int offset = 0;
        while (offset < target.length) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) throw new SocketTimeoutException("owner frame deadline");
            socket.setSoTimeout((int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) throw new EOFException("owner frame truncated");
            offset += count;
        }
    }

    private static void writeFrame(DataOutputStream output, byte[] frame) throws IOException {
        requireFrame(frame);
        output.writeInt(frame.length);
        output.write(frame);
        output.flush();
    }

    private static void requireFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length == 0 || frame.length > MAX_FRAME_BYTES) {
            throw new FrameBoundsException();
        }
    }

    private void closeSocket(Socket socket) {
        try { socket.close(); }
        catch (IOException ignored) { }
        sockets.remove(socket);
        ScheduledFuture<?> deadline = socketDeadlines.remove(socket);
        if (deadline != null) deadline.cancel(false);
    }

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "rusty-owner-control-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static void closeTimedSocket(Socket socket) {
        try { socket.close(); }
        catch (IOException ignored) { }
    }

    /** Full product cleanup must precede endpoint retirement. */
    @Override public void close() {
        stopping = true;
        try { listener.close(); }
        catch (IOException ignored) { }
        for (Socket socket : sockets) closeSocket(socket);
        workers.shutdownNow();
        deadlines.shutdownNow();
        try {
            acceptThread.join(SHUTDOWN_TIMEOUT_MS);
            if(retainAcceptedOwnerTasks && acceptThread.isAlive()) {
                warnPending("same control accept thread remains Pending"); acceptThread.join();
            }
            if(!workers.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) && retainAcceptedOwnerTasks) {
                warnPending("same control workers remain Pending");
                while(!workers.awaitTermination(Long.MAX_VALUE,TimeUnit.NANOSECONDS)) { /* actual same executor */ }
            }
            if(!deadlines.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) && retainAcceptedOwnerTasks) {
                warnPending("same control deadlines remain Pending");
                while(!deadlines.awaitTermination(Long.MAX_VALUE,TimeUnit.NANOSECONDS)) { /* actual same executor */ }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!terminal()) throw new IllegalStateException("owner control cleanup remains pending");
    }
    private static void warnPending(String label) {
        try { android.util.Log.w("RQSpatialCameraPanel", label); } catch (RuntimeException ignored) { }
    }
    private enum InboundPhase { READ_REQUEST, HANDLE_REQUEST, WRITE_RESPONSE }
    /** Diagnostic correlation only. Never export frame bytes, exception text or a success claim. */
    private static String frameCorrelation(byte[] frame) {
        if(frame==null || frame.length==0 || frame.length>MAX_FRAME_BYTES)return "NONE";
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(frame);
            char[] hex="0123456789abcdef".toCharArray(),value=new char[digest.length*2];
            for(int i=0;i<digest.length;i++){value[i*2]=hex[(digest[i]&255)>>>4];value[i*2+1]=hex[digest[i]&15];}
            return new String(value);
        }catch(Throwable unavailable){return "NONE";}
    }
    private static void logFailure(String direction,String phase,Failure reason,Exception error,String correlation) {
        try {
            String type=error instanceof EOFException?"EOF":error instanceof SocketTimeoutException?"SOCKET_TIMEOUT"
                    :error instanceof SocketException?"SOCKET":error instanceof IOException?"IO"
                    :error instanceof IllegalArgumentException?"ILLEGAL_ARGUMENT":error instanceof IllegalStateException?"ILLEGAL_STATE"
                    :error instanceof SecurityException?"SECURITY":error instanceof InterruptedException?"INTERRUPTED":"OTHER";
            android.util.Log.w("RQSpatialCameraPanel","channel=owner-dispatch direction="+direction+" phase="+phase
                    +" failure="+reason.name()+" exception="+type+" frame_sha256="+correlation);
        }catch(Throwable unavailable){/* Observation cannot replace the original failure or its cleanup. */}
    }
}
