package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exact operator hash encoding; native receipt strings remain opaque strings. */
public final class EmbeddedDuplexCanonicalJson {
    private EmbeddedDuplexCanonicalJson() {}
    public static String encode(Object value) throws Exception {
        StringBuilder out = new StringBuilder(); append(out, value); return out.toString();
    }
    /** Compare exact JSON values across Integer/Long parser representations. */
    static boolean sameValue(Object left,Object right) throws Exception {
        return encode(left).equals(encode(right));
    }
    public static String sha256(Object value) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder(64);
        for(byte item:digest) out.append(Character.forDigit((item>>>4)&15,16)).append(Character.forDigit(item&15,16));
        return out.toString();
    }
    public static String sha256Bytes(byte[] exact) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(exact);StringBuilder out=new StringBuilder(64);
        for(byte item:digest)out.append(Character.forDigit((item>>>4)&15,16)).append(Character.forDigit(item&15,16));
        return out.toString();
    }
    private static void append(StringBuilder out,Object value) throws Exception {
        if(value==null||value==JSONObject.NULL){out.append("null");return;}
        if(value instanceof String){quote(out,(String)value);return;}
        if(value instanceof Boolean){out.append(value.toString());return;}
        if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long){out.append(value.toString());return;}
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();
            Iterator<String> it=object.keys();while(it.hasNext()){
                String key=it.next();if(!key.matches("[\\x20-\\x7e]+"))throw new IllegalArgumentException("operator hash key is not ASCII");keys.add(key);
            }
            Collections.sort(keys);out.append('{');boolean first=true;
            for(String key:keys){if(!first)out.append(',');first=false;quote(out,key);out.append(':');append(out,object.get(key));}
            out.append('}');return;
        }
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;out.append('[');for(int i=0;i<array.length();i++){if(i!=0)out.append(',');append(out,array.get(i));}out.append(']');return;}
        throw new IllegalArgumentException("operator hash value type");
    }
    private static void quote(StringBuilder out,String text){
        out.append('"');
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            switch(c){
                case '"':out.append("\\\"");break;
                case '\\':out.append("\\\\");break;
                case '\b':out.append("\\b");break;
                case '\f':out.append("\\f");break;
                case '\n':out.append("\\n");break;
                case '\r':out.append("\\r");break;
                case '\t':out.append("\\t");break;
                default:
                    if(c<0x20){out.append("\\u00").append(Character.forDigit(c>>>4,16)).append(Character.forDigit(c&15,16));}
                    else if(Character.isHighSurrogate(c)){
                        if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(i+1)))throw new IllegalArgumentException("unpaired operator surrogate");
                        out.append(c).append(text.charAt(++i));
                    }else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("unpaired operator surrogate");
                    else out.append(c);
            }
        }
        out.append('"');
    }
}
