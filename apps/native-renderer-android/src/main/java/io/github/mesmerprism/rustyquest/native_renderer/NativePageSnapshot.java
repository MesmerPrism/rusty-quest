package io.github.mesmerprism.rustyquest.native_renderer;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.TextPaint;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Observes retained Views only. No editable, callback, command, or application state authority. */
final class NativePageSnapshot {
    static final int MAX_SHAPES=1024,MAX_RUNS=128,MAX_BATCHES=2048;
    private volatile Publication published;
    private String visualKey,fontKey;
    private byte[] atlas;
    private long revision,atlasEpoch;
    private final NativePanelFont.PageCache glyphCache=new NativePanelFont.PageCache();
    private String failure="not-observed";
    private int shapeCount,runCount;
    private long preparationMicros;

    /** Pure packet helpers intentionally loadable by a host JVM without Android initialization. */
    static final class Wire {
        static byte[] pack(byte[] json,byte[] font){
            if(json==null||json.length==0||json.length>512*1024||font==null||font.length>1300000
                    ||16L+json.length+font.length>2*1024*1024)throw new IllegalArgumentException("native-page-packet-budget");
            return ByteBuffer.allocate(16+json.length+font.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x3147504e).putInt(1).putInt(json.length).putInt(font.length).put(json).put(font).array();
        }
        static boolean current(long generation,long content,long raster,long actualGeneration,long actualContent,long actualRaster){
            return generation>0&&content>0&&raster>0&&generation==actualGeneration&&content==actualContent&&raster==actualRaster;
        }
        static float opacity(float inherited,float own,boolean enabled){
            if(!Float.isFinite(inherited)||!Float.isFinite(own)||inherited<0||inherited>1||own<0||own>1)
                throw new IllegalArgumentException("native-page-opacity");
            return inherited*own*(enabled?1f:0.55f);
        }
        static float[] clip(float[] rect,float[] parent){
            float l=Math.max(rect[0],parent[0]),t=Math.max(rect[1],parent[1]),r=Math.min(rect[2],parent[2]),b=Math.min(rect[3],parent[3]);
            return l<r&&t<b?new float[]{l,t,r,b}:null;
        }
    }
    static final class Publication {
        final long generation,contentEpoch,revision,atlasEpoch,rasterEpoch;
        private final byte[] full,reuse;
        Publication(long generation,long content,long revision,long atlasEpoch,long raster,byte[] scene,byte[] atlas){
            if(generation<=0||content<=0||revision<=0||atlasEpoch<=0||raster<=0)throw new IllegalArgumentException("native-page-identity");
            this.generation=generation;contentEpoch=content;this.revision=revision;this.atlasEpoch=atlasEpoch;rasterEpoch=raster;
            full=Wire.pack(scene,atlas);reuse=Wire.pack(scene,new byte[0]);
        }
        byte[] read(long generation,long seenRevision,long seenAtlas){
            if(generation!=this.generation)return null;
            if(seenRevision==revision&&seenAtlas==atlasEpoch)return new byte[0];
            return (seenAtlas==atlasEpoch?reuse:full).clone();
        }
    }
    byte[] read(long generation,long content,long raster,long seenRevision,long seenAtlas){
        Publication value=published;
        return value!=null&&Wire.current(value.generation,value.contentEpoch,value.rasterEpoch,generation,content,raster)
            ?value.read(generation,seenRevision,seenAtlas):null;
    }
    void invalidate(){published=null;visualKey=null;}
    String failure(){return failure;}
    String counts(){return " shapes="+shapeCount+" runs="+runCount+" atlasEpoch="+atlasEpoch+" snapshotRevision="+revision+" prepMicros="+preparationMicros;}
    void retire(){invalidate();atlas=null;fontKey=null;glyphCache.clear();}

    /** Called on the UI thread after layout; shaping occurs only when visible display/style changes. */
    boolean observe(View content,View coordinateRoot,View excluded,long generation,long contentEpoch,long rasterEpoch,float rasterScale,boolean interactive){
        long started=System.nanoTime();
        try{
            if(!interactive||content==null||coordinateRoot==null||!content.isAttachedToWindow()||!content.isShown()
                    ||content.isLayoutRequested()||coordinateRoot.isLayoutRequested())throw new Unavailable("layout-or-owner");
            Builder builder=new Builder(coordinateRoot,excluded,rasterScale);
            builder.visit(content,new float[]{0,0,1024,768},1f,0);
            String nextFont=builder.fontSignature();
            JSONObject scene=new JSONObject().put("generation",generation).put("content_epoch",contentEpoch)
                .put("raster_epoch",rasterEpoch).put("page_id","content-"+contentEpoch)
                .put("logical_width",1024).put("logical_height",768).put("complete",true).put("interactive",true)
                .put("shapes",builder.shapes).put("text_runs",builder.runs).put("batches",builder.batches);
            String nextVisual=scene.toString()+"/"+nextFont;
            if(nextVisual.equals(visualKey)&&published!=null)return false;
            byte[] nextAtlas=atlas;
            long nextAtlasEpoch=atlasEpoch;
            if(!nextFont.equals(fontKey)||nextAtlas==null){
                nextAtlas=NativePanelFont.preparePage(builder.lines.toArray(new String[0]),builder.paints.toArray(new TextPaint[0]),glyphCache);
                nextAtlasEpoch=Math.addExact(atlasEpoch,1);
            }
            long nextRevision=Math.addExact(revision,1);
            scene.put("revision",nextRevision).put("atlas_epoch",nextAtlasEpoch);
            Publication next=new Publication(generation,contentEpoch,nextRevision,nextAtlasEpoch,rasterEpoch,
                scene.toString().getBytes(StandardCharsets.UTF_8),nextAtlas);
            atlas=nextAtlas;atlasEpoch=nextAtlasEpoch;revision=nextRevision;fontKey=nextFont;visualKey=nextVisual;
            failure="none";shapeCount=builder.shapes.length();runCount=builder.runs.length();preparationMicros=(System.nanoTime()-started)/1000;published=next;return true;
        }catch(Unavailable unavailable){failure=unavailable.reason;invalidate();return false;}
        catch(Exception invalid){failure="snapshot-budget-or-style";invalidate();return false;}
    }
    private static final class Unavailable extends RuntimeException {
        final String reason;Unavailable(String reason){super(reason);this.reason=reason;}
    }
    private static void require(boolean condition,String reason){if(!condition)throw new Unavailable(reason);}
    private static JSONArray array(float... values)throws Exception{JSONArray result=new JSONArray();for(float value:values){require(Float.isFinite(value),"nonfinite");result.put((double)value);}return result;}
    private static float[] color(int argb,float alpha){return new float[]{Color.red(argb)/255f,Color.green(argb)/255f,Color.blue(argb)/255f,Color.alpha(argb)/255f*alpha};}
    private static final class Builder {
        final JSONArray shapes=new JSONArray(),runs=new JSONArray(),batches=new JSONArray();
        final ArrayList<String> lines=new ArrayList<String>();
        final ArrayList<TextPaint> paints=new ArrayList<TextPaint>();
        final View excluded;final float rasterScale;final int[] origin=new int[2];
        int visited;
        Builder(View coordinateRoot,View excluded,float scale){
            require(Float.isFinite(scale)&&scale>0,"raster-scale");this.excluded=excluded;rasterScale=scale;
            coordinateRoot.getRootView().getLocationOnScreen(origin);
        }
        float[] rect(View view){
            int[] location=new int[2];view.getLocationOnScreen(location);
            float l=(location[0]-origin[0])/rasterScale,t=(location[1]-origin[1])/rasterScale;
            return new float[]{l,t,l+view.getWidth(),t+view.getHeight()};
        }
        void batch(String kind,int first,float[] clip)throws Exception {
            require(batches.length()<MAX_BATCHES,"batch-budget");
            batches.put(new JSONObject().put("kind",kind).put("first",first).put("count",1).put("clip",array(clip)));
        }
        void shape(float l,float t,float r,float b,int argb,float alpha,float radius,float rotation,float[] clip)throws Exception {
            if(l>=r||t>=b||alpha<=0||Color.alpha(argb)==0)return;
            require(shapes.length()<MAX_SHAPES,"shape-budget");int index=shapes.length();
            shapes.put(new JSONObject().put("rect",array((l+r)/2,(t+b)/2,(r-l)/2,(b-t)/2))
                .put("color",array(color(argb,alpha))).put("shape",array(Math.min(radius,Math.min(r-l,b-t)/2),rotation,0,0)));
            batch("shapes",index,clip);
        }
        void line(float x1,float y1,float x2,float y2,float width,int argb,float alpha,float[] clip)throws Exception {
            float length=(float)Math.hypot(x2-x1,y2-y1),cx=(x1+x2)/2,cy=(y1+y2)/2;
            shape(cx-length/2,cy-width/2,cx+length/2,cy+width/2,argb,alpha,width/2,(float)Math.atan2(y2-y1,x2-x1),clip);
        }
        void background(View view,float[] r,float alpha,float[] clip)throws Exception {
            if(view instanceof Button||view instanceof EditText){
                int border=view.hasFocus()?0xff52b7df:view.isPressed()?0xff83d0ed:0xff516578;
                shape(r[0],r[1],r[2],r[3],border,alpha,4,0,clip);
                shape(r[0]+1,r[1]+1,r[2]-1,r[3]-1,view.isPressed()?0xff304659:0xff1b2b38,alpha,3,0,clip);return;
            }
            Drawable drawable=view.getBackground();
            if(drawable==null)return;
            if(drawable instanceof ColorDrawable)shape(r[0],r[1],r[2],r[3],((ColorDrawable)drawable).getColor(),alpha,0,0,clip);
            else if(drawable instanceof GradientDrawable){
                GradientDrawable gradient=(GradientDrawable)drawable;
                require(gradient.getShape()==GradientDrawable.RECTANGLE||gradient.getShape()==GradientDrawable.OVAL,"background-shape");
                require(gradient.getColors()==null,"gradient-background");
                int fill=gradient.getColor()==null?Color.TRANSPARENT:gradient.getColor().getColorForState(view.getDrawableState(),gradient.getColor().getDefaultColor());
                float radius=gradient.getShape()==GradientDrawable.OVAL?Math.min(r[2]-r[0],r[3]-r[1])/2:gradient.getCornerRadius();
                shape(r[0],r[1],r[2],r[3],fill,alpha,radius,0,clip);
            }else if(!(view instanceof Spinner)&&!(view instanceof ProgressBar))throw new Unavailable("unsupported-background");
        }
        void visit(View view,float[] ancestor,float inheritedAlpha,int depth)throws Exception {
            if(view==excluded||view.getVisibility()!=View.VISIBLE||view.getAlpha()<=0)return;
            require(depth<=64&&++visited<=1024,"view-budget");
            require(!view.isLayoutRequested(),"pending-layout");
            float[] r=rect(view),clip=Wire.clip(r,ancestor);if(clip==null)return;
            require(view.getRotation()==0&&view.getScaleX()==1&&view.getScaleY()==1,"transformed-widget");
            float alpha=Wire.opacity(inheritedAlpha,view.getAlpha(),view.isEnabled());
            background(view,r,alpha,clip);
            if(view instanceof SeekBar)seek((SeekBar)view,r,alpha,clip);
            else if(view instanceof ProgressBar)progress((ProgressBar)view,r,alpha,clip);
            else if(view instanceof CheckBox)check((CheckBox)view,r,alpha,clip);
            if(view instanceof TextView)text((TextView)view,r,alpha,clip);
            else if(view instanceof ViewGroup){
                require(view instanceof LinearLayout||view instanceof FrameLayout||view instanceof ScrollView
                    ||view instanceof HorizontalScrollView||view instanceof Spinner||view instanceof RelativeLayout,"unsupported-container");
                ViewGroup group=(ViewGroup)view;
                require(group.getChildCount()<=1024,"child-budget");
                float[] childClip=ancestor;
                if(group.getClipChildren()){
                    float[] childBounds=r.clone();
                    if(group.getClipToPadding()){childBounds[0]+=view.getPaddingLeft();childBounds[1]+=view.getPaddingTop();childBounds[2]-=view.getPaddingRight();childBounds[3]-=view.getPaddingBottom();}
                    childClip=Wire.clip(childBounds,ancestor);if(childClip==null)return;
                }
                for(int i=0;i<group.getChildCount();i++)visit(group.getChildAt(i),childClip,alpha,depth+1);
                if(view instanceof Spinner){float x=r[2]-12,y=(r[1]+r[3])/2;line(x-4,y-2,x,y+2,2,0xffbed4e4,alpha,clip);line(x,y+2,x+4,y-2,2,0xffbed4e4,alpha,clip);}
            }else require(view.getClass()==View.class||view instanceof ProgressBar,"unsupported-widget");
        }
        void seek(SeekBar bar,float[] r,float alpha,float[] clip)throws Exception {
            require(bar.getMax()>0,"slider-range");
            float l=r[0]+bar.getPaddingLeft(),right=r[2]-bar.getPaddingRight(),y=(r[1]+r[3])/2;
            float fraction=(bar.getProgress()-bar.getMin())/(float)Math.max(1,bar.getMax()-bar.getMin());
            if(bar.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL)fraction=1-fraction;
            float x=l+(right-l)*fraction;int accent=bar.isEnabled()?0xff58b9db:0xff657886;
            shape(l,y-2,right,y+2,0xff425767,alpha,2,0,clip);
            shape(l,y-2,x,y+2,accent,alpha,2,0,clip);
            shape(x-7,y-7,x+7,y+7,accent,alpha,7,0,clip);
        }
        void progress(ProgressBar bar,float[] r,float alpha,float[] clip)throws Exception {
            require(!bar.isIndeterminate(),"indeterminate-progress");
            float y=(r[1]+r[3])/2;
            shape(r[0],y-3,r[2],y+3,0xff334859,alpha,3,0,clip);
            float x=r[0]+(r[2]-r[0])*bar.getProgress()/Math.max(1,bar.getMax());
            shape(r[0],y-3,x,y+3,0xff58b9db,alpha,3,0,clip);
        }
        void check(CheckBox box,float[] r,float alpha,float[] clip)throws Exception {
            float x=r[0]+box.getPaddingLeft()+3,y=(r[1]+r[3])/2;
            shape(x,y-8,x+16,y+8,box.isEnabled()?0xff7ab5cd:0xff657886,alpha,2,0,clip);
            shape(x+1,y-7,x+15,y+7,0xff1b2b38,alpha,1,0,clip);
            if(box.isChecked()){line(x+3,y,x+7,y+4,2,0xff99d5eb,alpha,clip);line(x+7,y+4,x+13,y-4,2,0xff99d5eb,alpha,clip);}
        }
        void text(TextView view,float[] r,float alpha,float[] clip)throws Exception {
            Layout layout=view.getLayout();require(layout!=null,"text-layout");
            CharSequence displayed=layout.getText();
            require(displayed.length()<=32768,"text-layout-budget");
            boolean hint=displayed.length()==0&&view.getHint()!=null&&view.getHint().length()>0;
            if(hint){
                displayed=view.getHint();
                layout=android.text.StaticLayout.Builder.obtain(displayed,0,displayed.length(),view.getPaint(),Math.max(1,layout.getWidth()))
                    .setAlignment(layout.getAlignment()).setIncludePad(false).setLineSpacing(view.getLineSpacingExtra(),view.getLineSpacingMultiplier()).build();
            }
            if(displayed instanceof Spanned){
                // IME suggestion/underline spans do not replace the editor's paint or editable.
                Spanned spans=(Spanned)displayed;
                require(spans.getSpans(0,spans.length(),android.text.style.MetricAffectingSpan.class).length==0
                    &&spans.getSpans(0,spans.length(),android.text.style.ForegroundColorSpan.class).length==0,"styled-text");
            }
            float left=r[0]+view.getTotalPaddingLeft()-view.getScrollX();
            float top=r[1]+view.getTotalPaddingTop()-view.getScrollY();
            float[] textClip=Wire.clip(new float[]{r[0]+view.getCompoundPaddingLeft(),r[1]+view.getExtendedPaddingTop(),
                r[2]-view.getCompoundPaddingRight(),r[3]-view.getExtendedPaddingBottom()},clip);
            if(textClip==null)return;
            int start=view.getSelectionStart(),end=view.getSelectionEnd();
            if(view instanceof EditText&&view.hasFocus()&&start>=0&&end>=0){
                int a=Math.min(start,end),b=Math.max(start,end);
                for(int i=layout.getLineForOffset(a);i<=layout.getLineForOffset(b);i++){
                    float y1=top+layout.getLineTop(i),y2=top+layout.getLineBottom(i);
                    float x1=left+layout.getPrimaryHorizontal(Math.max(a,layout.getLineStart(i)));
                    float x2=left+layout.getPrimaryHorizontal(Math.min(b,layout.getLineEnd(i)));
                    if(a==b)shape(x1,y1,x1+1.5f,y2,view.getCurrentTextColor(),alpha,0,0,textClip);
                    else shape(Math.min(x1,x2),y1,Math.max(x1,x2),y2,view.getHighlightColor(),alpha,1,0,textClip);
                }
                EditableComposition(view,layout,left,top,alpha,textClip);
            }
            int firstLine=layout.getLineForVertical((int)Math.floor(textClip[1]-top));
            int lastLine=layout.getLineForVertical((int)Math.ceil(textClip[3]-top));
            for(int i=firstLine;i<=lastLine;i++){
                if(top+layout.getLineBottom(i)<=textClip[1]||top+layout.getLineTop(i)>=textClip[3])continue;
                int a=layout.getLineStart(i),b=layout.getLineEnd(i);
                while(b>a&&(displayed.charAt(b-1)=='\n'||displayed.charAt(b-1)=='\r'))b--;
                require(b-a<=256,"text-line-budget");
                String line=displayed.subSequence(a,b).toString();
                int ellipsis=layout.getEllipsisCount(i);
                if(ellipsis>0){int at=layout.getEllipsisStart(i);line=line.substring(0,at)+"\u2026"+line.substring(Math.min(line.length(),at+ellipsis));}
                if(line.isEmpty())continue;
                require(line.length()<=256&&runs.length()<MAX_RUNS,"text-run-budget");
                int index=lines.size();lines.add(line);paints.add(new TextPaint(view.getPaint()));
                runs.put(new JSONObject().put("font_run",index).put("x",left+layout.getLineLeft(i))
                    .put("y",top+layout.getLineBaseline(i)).put("size",view.getTextSize()).put("color",array(color(hint?view.getCurrentHintTextColor():view.getCurrentTextColor(),alpha))));
                batch("text",index,textClip);
            }
            require(view.getError()==null,"editor-error-popup");
        }
        void EditableComposition(TextView view,Layout layout,float left,float top,float alpha,float[] clip)throws Exception {
            CharSequence raw=view.getText();if(!(raw instanceof android.text.Spannable))return;
            int start=android.view.inputmethod.BaseInputConnection.getComposingSpanStart((android.text.Spannable)raw);
            int end=android.view.inputmethod.BaseInputConnection.getComposingSpanEnd((android.text.Spannable)raw);
            if(start<0||end<=start)return;
            for(int i=layout.getLineForOffset(start);i<=layout.getLineForOffset(end);i++){
                float x1=left+layout.getPrimaryHorizontal(Math.max(start,layout.getLineStart(i)));
                float x2=left+layout.getPrimaryHorizontal(Math.min(end,layout.getLineEnd(i)));
                float y=top+layout.getLineBaseline(i)+2;
                line(Math.min(x1,x2),y,Math.max(x1,x2),y,1,view.getCurrentTextColor(),alpha,clip);
            }
        }
        String fontSignature()throws Exception {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            for(int i=0;i<lines.size();i++){
                byte[] text=lines.get(i).getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(text.length).array());hash.update(text);
                TextPaint p=paints.get(i);
                String style=System.identityHashCode(p.getTypeface())+":"+p.getTextScaleX()+":"+p.getTextSkewX()+":"+p.isFakeBoldText()+":"+p.getFlags()+":"+p.getLetterSpacing()+":"+p.getFontFeatureSettings()+":"+p.getFontVariationSettings();
                hash.update(style.getBytes(StandardCharsets.UTF_8));
            }
            return Arrays.toString(hash.digest());
        }
    }
}
