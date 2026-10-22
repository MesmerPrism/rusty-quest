package io.github.mesmerprism.rustyquest.development_feedback;

import android.app.Application;
import android.os.Process;
import android.os.SystemClock;
import org.rusty.quest.feedback.AndroidDexLoader;
import java.io.IOException;
import java.util.UUID;

public final class FeedbackApplication extends Application {
    private FeedbackController controller;
    @Override public void onCreate() { super.onCreate();controller(); }
    public synchronized FeedbackController controller() {
        // Providers can be created before Application.onCreate; always initialize once here.
        if(controller==null) {
            String process=Process.myPid()+":"+SystemClock.elapsedRealtime()+":"+UUID.randomUUID();
            try{controller=new FeedbackController(getFilesDir(),new AndroidDexLoader(),true,process,AssetActivation.read(getAssets()));}
            catch(IOException failure){throw new IllegalStateException("Cannot initialize private feedback storage",failure);}
        }
        return controller;
    }
}
