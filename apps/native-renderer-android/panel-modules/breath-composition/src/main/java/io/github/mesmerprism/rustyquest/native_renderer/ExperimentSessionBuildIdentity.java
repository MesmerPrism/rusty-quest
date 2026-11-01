package io.github.mesmerprism.rustyquest.native_renderer;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Installed PackageManager identity plus bounded generic build asset. Wi-Fi only. */
final class ExperimentSessionBuildIdentity {
    static JSONObject read(Context context) {
        long code=-1;String name="",asset="";
        try {PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
            code=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;name=info.versionName;
        }catch(Exception unavailable) { /* Unknown remains unknown. */ }
        try(InputStream input=context.getAssets().open("android-build-identity.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[256];int n;
            while((n=input.read(buffer))!=-1){if(bytes.size()+n>1024)throw new IllegalArgumentException("build-identity-budget");bytes.write(buffer,0,n);}
            asset=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }catch(Exception unavailable) { /* Source identity is optional on retained APKs. */ }
        try{return project(code,name,asset);}catch(Exception unavailable){return new JSONObject();}
    }
    static JSONObject project(long code,String name,String asset) throws Exception {
        JSONObject out=new JSONObject().put("v",1).put("version_code",code>0&&code<=2147483647L?code:JSONObject.NULL)
            .put("version_name",name!=null&&name.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")?name:JSONObject.NULL);
        JSONObject source=null;
        try {if(asset!=null&&asset.length()<=1024)source=new JSONObject(asset);}catch(Exception malformed) { /* no raw fallback */ }
        if(source!=null&&!"rusty.quest.android_build_identity.v1".equals(source.optString("schema")))source=null;
        for(String field:new String[]{"source_commit","source_tree","source_composition_fingerprint","packaging_fingerprint"}) {
            Object raw=source==null?null:source.opt(field);int length=field.equals("source_commit")||field.equals("source_tree")?40:64;
            out.put(field,raw instanceof String&&((String)raw).matches("[0-9a-f]{"+length+"}")?raw:JSONObject.NULL);
        }
        return out;
    }
}
