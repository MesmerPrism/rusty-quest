package io.github.mesmerprism.rustyquest.media;

import android.util.Log;
import java.io.EOFException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback framing/handler/cleanup with only Android Log modeled. */
public final class OwnerDispatchDiagnosticHost {
    private static void require(boolean actual,String message){if(!actual)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        byte[] request="SENSITIVE_SIGNED_FRAME".getBytes(StandardCharsets.US_ASCII);
        String digest=args[0];
        AtomicInteger calls=new AtomicInteger();
        AtomicReference<OwnerDispatchTcpEndpoint.RequestSpan> span=new AtomicReference<>();
        OwnerDispatchTcpEndpoint rejected=new OwnerDispatchTcpEndpoint(InetAddress.getLoopbackAddress(),0,frame->{
            calls.incrementAndGet();span.set(OwnerDispatchTcpEndpoint.currentRequestSpan());
            throw new IllegalStateException("DO_NOT_LOG_SIGNED_FRAME_KEY_AND_PRIVATE_TEXT");
        });
        try {
            OwnerDispatchTcpEndpoint.ExchangeControl control=new OwnerDispatchTcpEndpoint.ExchangeControl();
            try{OwnerDispatchTcpEndpoint.exchangeOwned(InetAddress.getLoopbackAddress(),rejected.port(),request,null,control);throw new AssertionError("Rejected callback returned a receipt");}
            catch(EOFException expected){/* Same preexisting lost-reply semantics. */}
            require(control.joined().isDone()&&!control.joined().isCompletedExceptionally(),"Actual owned exchange join changed");
            List<String> entries=Log.entries();
            require(entries.stream().anyMatch(s->s.contains("direction=INBOUND phase=HANDLE_REQUEST failure=CALLBACK_REJECTED exception=ILLEGAL_STATE frame_sha256="+digest)),"Swallowed handler cause has no finite correlated diagnostic");
            require(entries.stream().anyMatch(s->s.contains("direction=OUTBOUND phase=READ_RESPONSE_HEADER failure=EOF exception=EOF frame_sha256="+digest)),"Source exchange lost its correlated finite EOF cause");
            require(entries.size()==2,"Expected one target and one source failure marker");
            for(String entry:entries){require(!entry.contains("DO_NOT_LOG")&&!entry.contains("SENSITIVE_SIGNED_FRAME"),"Sensitive exception/payload leaked");require(entry.length()<256,"Unbounded failure marker");}
            require(calls.get()==1&&rejected.ready(),"Diagnostic changed dispatch count/readiness");
        } finally {rejected.close();}
        require(rejected.terminal()&&span.get()!=null&&span.get().terminal(),"Existing request/endpoint terminal joins changed");

        // Broken diagnostic publication must preserve the original lost-reply behavior and cleanup.
        Log.clear();Log.fail=true;
        OwnerDispatchTcpEndpoint loggerFailed=new OwnerDispatchTcpEndpoint(InetAddress.getLoopbackAddress(),0,frame->{throw new IllegalStateException("PRIVATE_SECOND_FAILURE");});
        try {
            try{OwnerDispatchTcpEndpoint.exchange(InetAddress.getLoopbackAddress(),loggerFailed.port(),request);throw new AssertionError("Logger fabricated success");}
            catch(EOFException expected){/* Not the Log failure. */}
        } finally {loggerFailed.close();Log.fail=false;}
        require(loggerFailed.terminal(),"Log publication failure changed cleanup");

        Log.clear();
        try(OwnerDispatchTcpEndpoint success=new OwnerDispatchTcpEndpoint(InetAddress.getLoopbackAddress(),0,frame->frame)){
            require(java.util.Arrays.equals(OwnerDispatchTcpEndpoint.exchange(InetAddress.getLoopbackAddress(),success.port(),request),request),"Successful response changed");
        }
        require(Log.entries().isEmpty(),"Failure markers invented for successful exchange");
        System.out.println("PASS: real rejected-handler/source EOF correlation, payload exclusion, unchanged failure/success/terminal joins and broken logger isolation; host JVM only");
    }
}
