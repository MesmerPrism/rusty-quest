package io.github.mesmerprism.rustyquest.ble_control;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;

/** Carrier framing only. A completed fragment never authorizes an application command. */
public final class HubBleFrames {
    public static final String SERVICE = "9a7b3001-7d6a-4b7f-9d4a-6f7c0a030001";
    public static final String WRITE = "9a7b3002-7d6a-4b7f-9d4a-6f7c0a030002";
    public static final String READ = "9a7b3003-7d6a-4b7f-9d4a-6f7c0a030003";
    public static final String STATUS = "9a7b3004-7d6a-4b7f-9d4a-6f7c0a030004";
    public static final int HEADER = 8, MAX_BYTES = 16_384;
    public static final long ASSEMBLY_MS = 10_000;
    private int expectedId = 1, total;
    private long deadline;
    private static final class PendingBytes extends ByteArrayOutputStream {
        void clear() { java.util.Arrays.fill(buf,(byte)0);reset(); }
    }
    private final PendingBytes pending = new PendingBytes();
    private boolean closed;

    public synchronized byte[] accept(byte[] chunk, int mtu, long now) throws IOException {
        try {
            if (closed || chunk == null || mtu < 23 || mtu > 517 || chunk.length <= HEADER || chunk.length > Math.min(mtu-3,244)) throw new IOException("carrier chunk bound");
            int id = u16(chunk,2), offset = u16(chunk,4), count = u16(chunk,6);
            if (chunk[0] != 1 || chunk[1] != 0 || id != expectedId || count < 1 || count > MAX_BYTES || offset != pending.size()) throw new IOException("carrier identity or order");
            if (offset == 0) { total=count; deadline=now+ASSEMBLY_MS; }
            if (count != total || now < deadline-ASSEMBLY_MS || now >= deadline || offset+chunk.length-HEADER > total) throw new IOException("carrier assembly bound");
            pending.write(chunk,HEADER,chunk.length-HEADER);
            if (pending.size() != total) return null;
            byte[] result=pending.toByteArray();pending.clear();total=0;
            if (expectedId == 65535) closed=true; else expectedId++;
            return result;
        } catch (IOException denied) { close();throw denied; }
    }
    public synchronized void close() { closed=true;pending.clear();total=0; }
    public static byte[] chunk(byte[] body,int id,int offset,int mtu) throws IOException {
        if (body == null || body.length < 1 || body.length > MAX_BYTES || id < 1 || id > 65535 || offset < 0 || offset >= body.length || mtu < 23 || mtu > 517) throw new IOException("carrier output bound");
        int count=Math.min(body.length-offset,Math.min(mtu-3,244)-HEADER);
        byte[] result=new byte[HEADER+count];result[0]=1;put16(result,2,id);put16(result,4,offset);put16(result,6,body.length);System.arraycopy(body,offset,result,HEADER,count);return result;
    }
    public static String text(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (Exception malformed) { throw new IOException("invalid carrier UTF8",malformed); }
    }
    private static int u16(byte[] b,int i) {return (b[i]&255)*256+(b[i+1]&255);}
    private static void put16(byte[] b,int i,int value) {b[i]=(byte)(value>>8);b[i+1]=(byte)value;}
}
