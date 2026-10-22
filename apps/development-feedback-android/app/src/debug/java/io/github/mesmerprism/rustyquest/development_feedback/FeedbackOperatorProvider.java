package io.github.mesmerprism.rustyquest.development_feedback;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Debug-only exact shell UID plus DUMP. No URI CRUD, path, class, Intent or command channel. */
public final class FeedbackOperatorProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private void requireShell() {
        if(Binder.getCallingUid()!=2000)throw new SecurityException("Feedback operator requires shell UID");
        getContext().enforceCallingPermission("android.permission.DUMP","Feedback operator requires DUMP");
    }
    @SuppressWarnings("deprecation") // Bundle.get supports the selected API26 minimum; values remain strictly String-only.
    @Override public Bundle call(String method,String arg,Bundle extras) {
        requireShell();
        if(arg!=null)throw new IllegalArgumentException("No untyped argument accepted");
        Map<String,String> input=new LinkedHashMap<>();
        if(extras!=null) {
            if(extras.size()>8)throw new IllegalArgumentException("Too many feedback fields");
            for(String key:extras.keySet()) {
                Object value=extras.get(key);
                if(!(value instanceof String))throw new IllegalArgumentException("Feedback fields must be strings");
                input.put(key,(String)value);
            }
        }
        FeedbackApplication app=(FeedbackApplication)getContext().getApplicationContext();
        try {
            Bundle result=new Bundle();result.putString("status_json",new JSONObject(app.controller().execute(method,input)).toString());return result;
        }catch(IOException failure){throw new IllegalStateException("Feedback private artifact read failed",failure);}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){requireShell();throw new UnsupportedOperationException("Use typed calls");}
    @Override public String getType(Uri uri){requireShell();throw new UnsupportedOperationException("Use typed calls");}
    @Override public Uri insert(Uri uri,ContentValues values){requireShell();throw new UnsupportedOperationException("Use typed calls");}
    @Override public int delete(Uri uri,String selection,String[] args){requireShell();throw new UnsupportedOperationException("Use typed calls");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){requireShell();throw new UnsupportedOperationException("Use typed calls");}
}
