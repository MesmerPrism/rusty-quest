package io.github.mesmerprism.rustyquest.native_renderer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Bounded trusted-LAN HTTP adapter; authentication belongs entirely to the gateway. */
final class ExperimentSessionGatewayHttpServer implements AutoCloseable {
    private final ExperimentSessionCommandGateway owner;
    private final int port;
    // Keep ten admitted connections bounded without queuing valid requests behind idle readers.
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,10,30,TimeUnit.SECONDS,new SynchronousQueue<Runnable>());
    private volatile ServerSocket server;
    ExperimentSessionGatewayHttpServer(ExperimentSessionCommandGateway owner,int port){this.owner=owner;this.port=port;}
    void start()throws Exception {
        server=new ServerSocket(port,8);
        final ServerSocket listening=server;
        Thread accept=new Thread(new Runnable(){@Override public void run(){
            while(!listening.isClosed())try {
                final Socket socket=listening.accept();
                if(!(socket.getInetAddress().isSiteLocalAddress()||socket.getInetAddress().isLoopbackAddress()||socket.getInetAddress().isLinkLocalAddress())){socket.close();continue;}
                socket.setSoTimeout(5000);
                try{workers.execute(new Runnable(){@Override public void run(){serve(socket);}});}catch(RuntimeException full){socket.close();}
            }catch(Exception ended){break;}
        }},"experiment-command-lan");accept.setDaemon(true);accept.start();
    }
    private void serve(Socket socket) {
        try(Socket connection=socket) {
            InputStream input=connection.getInputStream();String first=line(input,1024);String[] parts=first.split(" ");
            if(parts.length!=3||!"/v1/gateway".equals(parts[1])||!"HTTP/1.1".equals(parts[2])){reply(connection,404,"", "{}");return;}
            Map<String,String> headers=new HashMap<>();int size=first.length();
            while(true){String line=line(input,2048);size+=line.length();if(size>8192)throw new IllegalArgumentException("headers-too-large");if(line.isEmpty())break;
                int colon=line.indexOf(':');if(colon<=0)throw new IllegalArgumentException("invalid-header");String key=line.substring(0,colon).toLowerCase(java.util.Locale.ROOT);if(headers.put(key,line.substring(colon+1).trim())!=null)throw new IllegalArgumentException("duplicate-header");}
            String origin=headers.get("origin");
            if(origin!=null&&!owner.allowedOrigin(origin)){reply(connection,403,"","{}");return;}
            String cors=origin==null?"":"Access-Control-Allow-Origin: "+origin+"\r\nVary: Origin\r\n";
            if("OPTIONS".equals(parts[0])) {
                if(origin==null||!("POST".equals(headers.get("access-control-request-method"))||"GET".equals(headers.get("access-control-request-method")))
                        || headers.containsKey("access-control-request-headers")&&!"content-type".equalsIgnoreCase(headers.get("access-control-request-headers"))) {reply(connection,403,"","{}");return;}
                reply(connection,200,cors+"Access-Control-Allow-Methods: GET, POST\r\nAccess-Control-Allow-Headers: Content-Type\r\nAccess-Control-Allow-Private-Network: true\r\n", "{}");return;
            }
            if("GET".equals(parts[0])){reply(connection,200,cors,owner.identity().toString());return;}
            if(!"POST".equals(parts[0])||headers.containsKey("transfer-encoding")||!"application/json".equals(headers.get("content-type"))){reply(connection,400,cors,"{}");return;}
            int length=Integer.parseInt(headers.get("content-length"));if(length<1||length>8192)throw new IllegalArgumentException("body-bound");
            byte[] bytes=new byte[length];int offset=0;while(offset<length){int count=input.read(bytes,offset,length-offset);if(count<0)throw new IllegalArgumentException("incomplete-body");offset+=count;}
            try{reply(connection,200,cors,owner.exchange(new JSONObject(new String(bytes,StandardCharsets.UTF_8))).toString());}
            catch(Exception denied){reply(connection,403,cors,"{\"error\":\"gateway-request-rejected\"}");}
        }catch(Exception invalid){/* Bounded malformed requests close without reaching the owner. */}
    }
    private static String line(InputStream input,int max)throws Exception {
        ByteArrayOutputStream result=new ByteArrayOutputStream();int previous=-1;
        while(result.size()<max){int value=input.read();if(value<0)throw new IllegalArgumentException("incomplete-http");if(previous=='\r'&&value=='\n'){byte[] bytes=result.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);}result.write(value);previous=value;}
        throw new IllegalArgumentException("line-too-long");
    }
    private static void reply(Socket socket,int status,String cors,String body)throws Exception {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream output=socket.getOutputStream();
        output.write(("HTTP/1.1 "+status+" "+(status==200?"OK":"Rejected")+"\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\n"+cors+"Content-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(bytes);output.flush();
    }
    @Override public void close(){ServerSocket current=server;server=null;if(current!=null)try{current.close();}catch(Exception ignored){}workers.shutdownNow();}
}
