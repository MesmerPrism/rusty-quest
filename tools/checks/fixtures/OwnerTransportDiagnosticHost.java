import io.github.mesmerprism.rustyquest.media.OwnerDispatchTcpEndpoint;
import io.github.mesmerprism.rustyquest.media.OwnerTransportAttempt;
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexEnrollmentDraft;
import java.net.*;
import java.io.*;
import java.util.Arrays;

public final class OwnerTransportDiagnosticHost {
    static int controls;
    static final InetAddress LOOP;
    static { try { LOOP=InetAddress.getByName("127.0.0.1"); } catch(Exception e) { throw new RuntimeException(e); } }
    static void check(boolean ok) { if(!ok) throw new AssertionError("transport control "+controls); controls++; }
    static final byte[] REQUEST={1,2,3};
    static OwnerTransportAttempt attempt() { return new OwnerTransportAttempt(); }
    static OwnerDispatchTcpEndpoint.ExchangeObserver observer(OwnerTransportAttempt a,long sequence) {
        return (p,o,f)->a.observe(sequence,p,o,f);
    }
    static void fields(OwnerTransportAttempt a,String phase,String outcome,String cause) {
        String json=a.snapshot(); check(json.contains("\"phase\":\""+phase+"\""));
        check(json.contains("\"outcome\":\""+outcome+"\""));check(json.contains("\"failure\":\""+cause+"\""));
    }
    interface Reply { void write(Socket socket) throws Exception; }
    static void raw(Reply reply,String phase,String cause) throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,LOOP)) {
            java.util.concurrent.atomic.AtomicReference<Throwable> serverFailure=new java.util.concurrent.atomic.AtomicReference<>();
            Thread t=new Thread(()->{try(Socket s=server.accept()) {
                DataInputStream input=new DataInputStream(s.getInputStream());int size=input.readInt();byte[] request=new byte[size];input.readFully(request);
                reply.write(s);
            } catch(Exception e) { serverFailure.set(e); }});t.start();
            OwnerTransportAttempt a=attempt();long n=a.begin();boolean failed=false;
            try { OwnerDispatchTcpEndpoint.exchange(LOOP,server.getLocalPort(),REQUEST,observer(a,n)); }
            catch(IOException expected) {failed=true;}
            check(failed);t.join(35_000);check(!t.isAlive());check(serverFailure.get()==null);fields(a,phase,"FAILED",cause);
        }
    }
    static EmbeddedDuplexEnrollmentDraft draft(String role,String revoker,boolean fixture) {
        return new EmbeddedDuplexEnrollmentDraft(role,"11"+new String(new char[62]).replace('\0','1'),
                "host.test.peer","operator.test","adapter.test",revoker,"authority.test",120000,fixture);
    }
    public static void main(String[] args) throws Exception {
        EmbeddedDuplexEnrollmentDraft first=draft("peer_a","operator.revoker.shared",false);
        EmbeddedDuplexEnrollmentDraft second=draft("peer_b","operator.revoker.shared",false);
        EmbeddedDuplexEnrollmentDraft.requirePairedCleanupIdentity(first,second);check(true);
        for(EmbeddedDuplexEnrollmentDraft invalid:new EmbeddedDuplexEnrollmentDraft[]{
                draft("peer_b","operator.revoker.other",false),draft("peer_a","operator.revoker.shared",false),
                draft("peer_b","operator.revoker.shared",true),null}) {
            boolean rejected=false;try {EmbeddedDuplexEnrollmentDraft.requirePairedCleanupIdentity(first,invalid);}
            catch(IllegalArgumentException expected){rejected=true;}check(rejected);
        }
        OwnerTransportAttempt a=attempt();fields(a,"NONE","NONE","NONE");
        long old=a.begin(),current=a.begin();a.observe(old,OwnerDispatchTcpEndpoint.Phase.CONNECT,OwnerDispatchTcpEndpoint.Outcome.FAILED,OwnerDispatchTcpEndpoint.Failure.CONNECT_FAILURE);
        fields(a,"TARGET_BINDING","IN_PROGRESS","NONE");check(a.snapshot().contains("\"attempt_sequence\":2"));
        int refused;
        try(ServerSocket reserve=new ServerSocket(0,1,LOOP)){refused=reserve.getLocalPort();}
        a=attempt();long n=a.begin();boolean failed=false;
        try {OwnerDispatchTcpEndpoint.exchange(LOOP,refused,REQUEST,observer(a,n));} catch(ConnectException expected){failed=true;}
        check(failed);fields(a,"CONNECT","FAILED","CONNECT_FAILURE");
        a=attempt();n=a.begin();failed=false;
        try {OwnerDispatchTcpEndpoint.exchange(LOOP,refused,new byte[0],observer(a,n));}catch(IOException expected){failed=true;}
        check(failed);fields(a,"INPUT_BOUNDS","FAILED","INPUT_BOUNDS");
        a=attempt();n=a.begin();failed=false;
        try {OwnerDispatchTcpEndpoint.exchange(null,refused,REQUEST,observer(a,n));}catch(IllegalArgumentException expected){failed=true;}
        check(failed);fields(a,"TARGET_BINDING","FAILED","TARGET_UNAVAILABLE");
        raw(s->{},"READ_RESPONSE_HEADER","EOF");
        raw(s->{s.getOutputStream().write(new byte[]{0,0});},"READ_RESPONSE_HEADER","EOF");
        raw(s->{DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeInt(4);out.write(new byte[]{1,2});out.flush();},"READ_RESPONSE_BODY","EOF");
        raw(s->{DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeInt(OwnerDispatchTcpEndpoint.MAX_FRAME_BYTES+1);out.flush();},"READ_RESPONSE_HEADER","FRAME_BOUNDS");
        // A rejected authenticated handler sends no error envelope. Its EOF proves no particular rejection cause.
        try(OwnerDispatchTcpEndpoint endpoint=new OwnerDispatchTcpEndpoint(LOOP,0,request->{throw new IOException("REJECTED_SECRET_PAYLOAD");})) {
            a=attempt();n=a.begin();failed=false;
            try {OwnerDispatchTcpEndpoint.exchange(LOOP,endpoint.port(),REQUEST,observer(a,n));}catch(EOFException expected){failed=true;}
            check(failed);fields(a,"READ_RESPONSE_HEADER","FAILED","EOF");check(!a.snapshot().contains("SECRET_PAYLOAD"));
        }
        try(OwnerDispatchTcpEndpoint endpoint=new OwnerDispatchTcpEndpoint(LOOP,0,request->new byte[]{9,8,7})) {
            a=attempt();n=a.begin();byte[] response=OwnerDispatchTcpEndpoint.exchange(LOOP,endpoint.port(),REQUEST,observer(a,n));
            check(Arrays.equals(response,new byte[]{9,8,7}));fields(a,"RESPONSE_RETURNED","RETURNED","NONE");
            // Diagnostics cannot change a successful wire result, even when the observer itself rejects.
            check(Arrays.equals(OwnerDispatchTcpEndpoint.exchange(LOOP,endpoint.port(),REQUEST,(p,o,f)->{throw new IllegalStateException("observer");}),response));
            // Both overloads retain the same wire behavior. Returned bytes are deliberately not a proof parser.
            check(Arrays.equals(OwnerDispatchTcpEndpoint.exchange(LOOP,endpoint.port(),REQUEST),response));
        }
        // Exercise the unchanged actual 30-second read deadline, never a shortened testing deadline.
        raw(s->{Thread.sleep(31_000);},"READ_RESPONSE_HEADER","DEADLINE");
        System.out.println("PASS "+controls+" production transport controls; returned bytes not authenticated; no device calls");
    }
}
