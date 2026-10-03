package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;

import io.github.mesmerprism.rustyquest.broker_transport.Rfc6455Codec;
import io.github.mesmerprism.rustyquest.broker_transport.DeadlineInputStream;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.json.JSONObject;

/** Fixed-loopback existing Hub protocol; no new session, grants or private policy delegate. */
final class HubLoopbackClient implements Closeable {
    interface Readiness {void requireCurrent() throws Exception;}
    interface Sink {void frame(byte[] frame) throws Exception;void unavailable();}
    interface Connector {Socket open() throws Exception;}
    private final Readiness readiness;private final Sink sink;private final long deadlineNanos;
    private final Connector connector;
    private final SecureRandom random=new SecureRandom();private volatile Socket socket;private volatile OutputStream output;private boolean authenticationSent;private volatile boolean closed;
    HubLoopbackClient(Readiness readiness,Sink sink,long deadlineNanos) {this(readiness,sink,deadlineNanos,new Connector(){public Socket open()throws Exception{Socket next=new Socket();try{next.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),8876),2000);next.setSoTimeout(250);return next;}catch(Exception failed){next.close();throw failed;}}});}
    // Package-private target-free integration seam, never selected from Activity/GATT inputs.
    HubLoopbackClient(Readiness readiness,Sink sink,long deadlineNanos,Connector connector) {this.readiness=readiness;this.sink=sink;this.deadlineNanos=deadlineNanos;this.connector=connector;}
    synchronized void send(byte[] bytes) throws Exception {
        requireBudget();readiness.requireCurrent();JSONObject frame=new JSONObject(HubBleFrames.text(bytes));
        validate(frame,!authenticationSent);
        if(socket==null) connect();
        requireBudget();readiness.requireCurrent();byte[] mask=new byte[4];random.nextBytes(mask);
        Rfc6455Codec.writeFrame(output,true,Rfc6455Codec.OPCODE_TEXT,bytes,mask);output.flush();authenticationSent=true;
    }
    static void validate(JSONObject f,boolean first) throws Exception {
        String schema=f.optString("$schema",""),type=f.optString("type","");
        if(first) {exact(f,"$schema","type","session");if(!"rusty.quest.connection_hub.socket_authenticate.v2".equals(schema)||!"authenticate".equals(type)||!(f.get("session") instanceof String)||!f.getString("session").matches("[A-Za-z0-9_-]{32,256}")) throw new SecurityException("existing Hub authentication required");return;}
        if("rusty.quest.connection_hub.keepalive.v2".equals(schema)&&"keepalive".equals(type)) {exact(f,"$schema","type","request_sequence");return;}
        exact(f,"$schema","type","request_id","request_sequence","surface_id","command","args");
        if(!"rusty.quest.connection_hub.surface_command.v2".equals(schema)||!"surface.command".equals(type)||!"surface.concurrent_stereo.controls".equals(f.getString("surface_id"))||!("command.concurrent_stereo.own".equals(f.getString("command"))||"command.concurrent_stereo.peer".equals(f.getString("command")))||f.getJSONObject("args").length()!=0) throw new SecurityException("closed empty current-policy command required");
        // Native Hub remains authoritative for strict wire types/sequence/session/expiry/grants.
    }
    private static void exact(JSONObject f,String...keys) throws Exception {Set<String> wanted=new HashSet<>(Arrays.asList(keys));if(f.length()!=wanted.size())throw new SecurityException("unknown carrier fields");Iterator<String> names=f.keys();while(names.hasNext())if(!wanted.contains(names.next()))throw new SecurityException("unknown carrier fields");}
    private void connect() throws Exception {
        requireBudget();Socket next=connector.open();socket=next;
        try {
            requireBudget();output=next.getOutputStream();
            byte[] key=new byte[16];random.nextBytes(key);String encoded=Base64.getEncoder().encodeToString(key);
            String upgrade="GET /v1/socket HTTP/1.1\r\nHost: 127.0.0.1:8876\r\nOrigin: http://127.0.0.1:8876\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "+encoded+"\r\n\r\n";
            requireBudget();readiness.requireCurrent();output.write(upgrade.getBytes(StandardCharsets.US_ASCII));output.flush();
            InputStream timed=new DeadlineInputStream(next.getInputStream(),Math.min(deadlineNanos,System.nanoTime()+5_000_000_000L),5_000_000_000L);
            ByteArrayOutputStream header=new ByteArrayOutputStream();int last=0;
            while(header.size()<8192){int b=timed.read();if(b<0)throw new EOFException();header.write(b);last=(last<<8)|b;if(last==0x0d0a0d0a)break;}
            requireUpgrade(new String(header.toByteArray(),StandardCharsets.US_ASCII),encoded);
            requireBudget();readiness.requireCurrent();
            Thread reader=new Thread(new Runnable(){public void run(){readLoop();}},"hub-ble-loopback");reader.setDaemon(true);reader.start();
        }catch(Exception denied){close();throw denied;}
    }
    static void requireUpgrade(String response,String key)throws Exception {
        if(!response.startsWith("HTTP/1.1 101 ")||!response.endsWith("\r\n\r\n"))throw new IOException("actual Hub upgrade unavailable");
        Map<String,String> headers=new HashMap<>();String[] lines=response.split("\r\n");
        for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon<1)throw new IOException("malformed Hub upgrade");String name=lines[i].substring(0,colon).toLowerCase(Locale.ROOT);if(headers.put(name,lines[i].substring(colon+1).trim())!=null)throw new IOException("duplicate Hub upgrade header");}
        String expected=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        if(!expected.equals(headers.get("sec-websocket-accept"))||!"websocket".equalsIgnoreCase(headers.get("upgrade"))||!"upgrade".equalsIgnoreCase(headers.get("connection")))throw new IOException("actual Hub upgrade unavailable");
    }
    private void readLoop(){try{while(!closed){requireBudget();Rfc6455Codec.Frame frame;
        try{frame=Rfc6455Codec.readFrame(new DeadlineInputStream(socket.getInputStream(),Math.min(deadlineNanos,System.nanoTime()+500_000_000L),5_000_000_000L),false,HubBleFrames.MAX_BYTES);}catch(SocketTimeoutException wait){if(wait.getMessage()!=null&&wait.getMessage().contains("first-byte"))continue;throw wait;}
        if(!frame.fin)throw new IOException("fragmented Hub frame unavailable");
        if(frame.opcode==Rfc6455Codec.OPCODE_PING){synchronized(this){requireBudget();readiness.requireCurrent();byte[] mask=new byte[4];random.nextBytes(mask);Rfc6455Codec.writeFrame(output,true,Rfc6455Codec.OPCODE_PONG,frame.payload,mask);output.flush();}continue;}
        if(frame.opcode==Rfc6455Codec.OPCODE_CLOSE)break;if(frame.opcode!=Rfc6455Codec.OPCODE_TEXT)throw new IOException("unexpected Hub frame");
        requireBudget();readiness.requireCurrent();sink.frame(frame.payload);
    }}catch(Exception unavailable){}finally{close();sink.unavailable();}}
    private void requireBudget() throws IOException {if(closed||System.nanoTime()>=deadlineNanos)throw new IOException("carrier lifetime expired");}
    public void close(){closed=true;Socket current=socket;if(current!=null)try{current.close();}catch(IOException ignored){}socket=null;output=null;}
}
