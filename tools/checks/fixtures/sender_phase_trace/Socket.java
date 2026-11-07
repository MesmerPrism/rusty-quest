package io.github.mesmerprism.rustyquest.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;

/** External socket leaves only: no bind/connect/network operation is performed. */
final class Socket {
    static volatile InputStream input;
    static volatile OutputStream output;
    static int connections,closes;
    private boolean closed;
    void bind(SocketAddress address) {}
    void connect(SocketAddress address,int timeout){synchronized(Socket.class){connections++;}}
    void setTcpNoDelay(boolean value) {}
    void setSoTimeout(int value) {}
    InputStream getInputStream(){return input;}
    OutputStream getOutputStream(){return output;}
    void close()throws IOException{
        synchronized(this){if(closed)return;closed=true;}
        synchronized(Socket.class){closes++;}
        input.close();output.close();
    }
    synchronized boolean isClosed(){return closed;}
    static synchronized void reset(InputStream in,OutputStream out){input=in;output=out;connections=closes=0;}
}
