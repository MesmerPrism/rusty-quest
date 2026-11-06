package org.rusty.quest.feedback;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded standard-DEX class_def reader; counts definitions, not merely referenced API descriptors. */
final class DexDefinitions {
    private DexDefinitions() { }
    static Set<String> read(byte[] bytes) {
        try {
            if(bytes.length<112 || bytes[0]!='d' || bytes[1]!='e' || bytes[2]!='x' || bytes[3]!='\n'
                    || bytes[7]!=0)throw new IllegalArgumentException("Invalid DEX header");
            String version=new String(bytes,4,3,StandardCharsets.US_ASCII);
            if(!Arrays.asList("035","037","038","039","040").contains(version))throw new IllegalArgumentException("Unsupported standard DEX version");
            ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if(data.getInt(32)!=bytes.length || data.getInt(36)!=112 || data.getInt(40)!=0x12345678)
                throw new IllegalArgumentException("Invalid DEX size/header/endian");
            int stringCount=data.getInt(56), strings=data.getInt(60);
            int typeCount=data.getInt(64),types=data.getInt(68);
            int classCount=data.getInt(96),classes=data.getInt(100);
            range(strings,stringCount,4,bytes.length);range(types,typeCount,4,bytes.length);range(classes,classCount,32,bytes.length);
            Set<String> result=new HashSet<>();
            for(int i=0;i<classCount;i++) {
                int type=data.getInt(classes+i*32);
                if(type<0 || type>=typeCount)throw new IllegalArgumentException("Invalid DEX type index");
                int string=data.getInt(types+type*4);
                if(string<0 || string>=stringCount)throw new IllegalArgumentException("Invalid DEX string index");
                int cursor=data.getInt(strings+string*4);range(cursor,1,1,bytes.length);
                int prefix=0;
                while((bytes[cursor++]&128)!=0)if(++prefix>=5)throw new IllegalArgumentException("Invalid DEX string size");
                int start=cursor;
                while(cursor<bytes.length && bytes[cursor]!=0)cursor++;
                if(cursor==bytes.length)throw new IllegalArgumentException("Unterminated DEX descriptor");
                String descriptor=new String(bytes,start,cursor-start,StandardCharsets.UTF_8);
                if(!descriptor.matches("L[A-Za-z_$][A-Za-z0-9_$/]*;") || !result.add(descriptor))
                    throw new IllegalArgumentException("Invalid or duplicate DEX class definition");
            }
            return result;
        } catch(IndexOutOfBoundsException invalid) { throw new IllegalArgumentException("Truncated DEX",invalid); }
    }
    private static void range(int offset,int count,int stride,int length) {
        if(offset<0 || count<0 || (long)offset+(long)count*stride>length)throw new IllegalArgumentException("DEX table outside artifact");
    }
}
