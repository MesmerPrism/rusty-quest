package io.github.mesmerprism.rustyquest.native_renderer;

import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
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
    /** Actual retained window roots in the same order as the platform/input owner. */
    static final class Layer {
        final View root;final long windowId;final float dimAmount;
        Layer(View root,long windowId,float dimAmount){this.root=root;this.windowId=windowId;this.dimAmount=dimAmount;}
    }

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
        static int[] layerSteps(long[] ids,float[] dims){
            if(ids==null||dims==null||ids.length==0||ids.length>8||ids.length!=dims.length||ids[0]!=0||dims[0]!=0)
                throw new IllegalArgumentException("native-page-window-budget");
            int count=ids.length;
            for(int i=0;i<ids.length;i++){
                if(i>0&&ids[i]<=0||!Float.isFinite(dims[i])||dims[i]<0||dims[i]>1)throw new IllegalArgumentException("native-page-window-identity");
                for(int j=0;j<i;j++)if(ids[j]==ids[i])throw new IllegalArgumentException("native-page-window-duplicate");
                if(dims[i]>0)count++;
            }
            int[] steps=new int[count];int at=0;
            for(int i=0;i<ids.length;i++){if(dims[i]>0)steps[at++]=-i-1;steps[at++]=i;}
            return steps;
        }
        static float[] windowRect(float x,float y,float width,float height,float scale,float originX,float originY,float raster){
            for(float value:new float[]{x,y,width,height,scale,originX,originY,raster})if(!Float.isFinite(value))throw new IllegalArgumentException("native-page-window-geometry");
            if(width<0||height<0||scale<=0||raster<=0)throw new IllegalArgumentException("native-page-window-geometry");
            float left=(x-originX)/raster,top=(y-originY)/raster;
            return new float[]{left,top,left+width*scale/raster,top+height*scale/raster};
        }
        static boolean singleChoice(int choiceMode,int firstVisible,int child,int checked){
            return choiceMode==1&&firstVisible>=0&&child>=0&&checked>=0&&(long)firstVisible+child==checked;
        }
        static boolean boundsOnlyWrapper(Class<?> type){
            // InsetDrawable projects its effect into child bounds. Clip/rotation/level wrappers
            // also change Canvas painting; unwrapping them would silently paint the wrong scene.
            // Exact identity deliberately excludes custom subclasses overriding draw().
            return type==android.graphics.drawable.InsetDrawable.class;
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
        return observe(new Layer[]{new Layer(content,0,0)},coordinateRoot,excluded,generation,contentEpoch,rasterEpoch,rasterScale,interactive);
    }
    boolean observe(Layer[] layers,View coordinateRoot,View excluded,long generation,long contentEpoch,long rasterEpoch,float rasterScale,boolean interactive){
        long started=System.nanoTime();
        try{
            if(!interactive||coordinateRoot==null||coordinateRoot.isLayoutRequested()||layers==null||layers.length==0||layers.length>8)throw new Unavailable("layout-or-owner");
            long[] identities=new long[layers.length];float[] dims=new float[layers.length];
            StringBuilder windows=new StringBuilder();
            for(int i=0;i<layers.length;i++){
                Layer layer=layers[i];require(layer!=null&&layer.root!=null&&layer.root.isAttachedToWindow()
                    &&layer.root.isShown()&&!layer.root.isLayoutRequested(),"window-layout-or-owner");
                identities[i]=layer.windowId;dims[i]=layer.dimAmount;
                windows.append(layer.windowId).append(':').append(System.identityHashCode(layer.root)).append(';');
            }
            Builder builder=new Builder(coordinateRoot,excluded,rasterScale);
            float[] viewport={0,0,1024,768};
            for(int step:Wire.layerSteps(identities,dims)){
                if(step<0){builder.shape(0,0,1024,768,Color.BLACK,dims[-step-1],0,0,viewport);continue;}
                builder.visit(layers[step].root,viewport,1f,0);
            }
            String nextFont=builder.fontSignature();
            JSONObject scene=new JSONObject().put("generation",generation).put("content_epoch",contentEpoch)
                .put("raster_epoch",rasterEpoch).put("page_id","content-"+contentEpoch)
                .put("logical_width",1024).put("logical_height",768).put("complete",true).put("interactive",true)
                .put("shapes",builder.shapes).put("text_runs",builder.runs).put("batches",builder.batches);
            String nextVisual=scene.toString()+"/"+nextFont+"/"+windows;
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
        float scale(View view){
            Matrix matrix=new Matrix();view.transformMatrixToGlobal(matrix);float[] values=new float[9];matrix.getValues(values);
            require(Math.abs(values[Matrix.MSKEW_X])<0.001f&&Math.abs(values[Matrix.MSKEW_Y])<0.001f
                &&Math.abs(values[Matrix.MPERSP_0])<0.00001f&&Math.abs(values[Matrix.MPERSP_1])<0.00001f
                &&Math.abs(values[Matrix.MPERSP_2]-1)<0.00001f,"transformed-widget");
            float x=values[Matrix.MSCALE_X],y=values[Matrix.MSCALE_Y];
            require(Float.isFinite(x)&&Float.isFinite(y)&&x>0&&Math.abs(x-y)<0.001f,"nonuniform-widget");return x;
        }
        float[] rect(View view,float globalScale){
            int[] location=new int[2];view.getLocationOnScreen(location);
            return Wire.windowRect(location[0],location[1],view.getWidth(),view.getHeight(),globalScale,origin[0],origin[1],rasterScale);
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
        void background(View view,float[] r,float alpha,float[] clip,float unit)throws Exception {
            if(view instanceof Button||view instanceof EditText){
                int border=view.hasFocus()?0xff52b7df:view.isPressed()?0xff83d0ed:0xff516578;
                shape(r[0],r[1],r[2],r[3],border,alpha,4,0,clip);
                shape(r[0]+1,r[1]+1,r[2]-1,r[3]-1,view.isPressed()?0xff304659:0xff1b2b38,alpha,3,0,clip);return;
            }
            Drawable drawable=view.getBackground();
            if(drawable==null)return;
            if(view instanceof Spinner||view instanceof ProgressBar)return; // These standard controls have explicit primitives below.
            drawable(drawable,view,r,alpha,clip,unit,0);
        }
        void drawable(Drawable drawable,View view,float[] r,float alpha,float[] clip,float unit,int depth)throws Exception {
            require(depth<=16,"drawable-budget");
            float opacity=alpha*drawable.getAlpha()/255f;
            if(drawable instanceof ColorDrawable){
                int fill=(((ColorDrawable)drawable).getColor()&0x00ffffff)|(drawable.getAlpha()<<24);
                shape(r[0],r[1],r[2],r[3],fill,alpha,0,0,clip);
            }
            else if(drawable instanceof GradientDrawable){
                GradientDrawable gradient=(GradientDrawable)drawable;
                require(gradient.getShape()==GradientDrawable.RECTANGLE||gradient.getShape()==GradientDrawable.OVAL,"background-shape");
                require(gradient.getColors()==null,"gradient-background");
                int fill=gradient.getColor()==null?Color.TRANSPARENT:gradient.getColor().getColorForState(view.getDrawableState(),gradient.getColor().getDefaultColor());
                float radius=gradient.getShape()==GradientDrawable.OVAL?Math.min(r[2]-r[0],r[3]-r[1])/2:gradient.getCornerRadius()*unit;
                shape(r[0],r[1],r[2],r[3],fill,opacity,radius,0,clip);
            }else if(drawable instanceof DrawableWrapper){
                require(Wire.boundsOnlyWrapper(drawable.getClass()),"unsupported-drawable-wrapper");
                Drawable child=((DrawableWrapper)drawable).getDrawable();require(child!=null&&child!=drawable,"drawable-wrapper");
                drawable(child,view,drawableRect(drawable,child,r,unit),alpha,clip,unit,depth+1);
            }else if(drawable instanceof LayerDrawable){
                LayerDrawable layered=(LayerDrawable)drawable;require(layered.getNumberOfLayers()<=16,"drawable-layer-budget");
                for(int i=0;i<layered.getNumberOfLayers();i++){
                    if(layered.getId(i)==android.R.id.mask)continue; // Ripple mask clips feedback; it is not a painted layer.
                    Drawable child=layered.getDrawable(i);require(child!=null&&child!=drawable,"drawable-layer");
                    drawable(child,view,drawableRect(drawable,child,r,unit),alpha,clip,unit,depth+1);
                }
            }else{
                Drawable current=drawable.getCurrent();
                require(current!=null&&current!=drawable,"unsupported-background");
                drawable(current,view,drawableRect(drawable,current,r,unit),alpha,clip,unit,depth+1);
            }
        }
        float[] drawableRect(Drawable parent,Drawable child,float[] r,float unit){
            Rect outer=parent.getBounds(),inner=child.getBounds();
            require(!outer.isEmpty()&&!inner.isEmpty(),"drawable-layout-pending");
            return new float[]{r[0]+(inner.left-outer.left)*unit,r[1]+(inner.top-outer.top)*unit,
                r[0]+(inner.right-outer.left)*unit,r[1]+(inner.bottom-outer.top)*unit};
        }
        void visit(View view,float[] ancestor,float inheritedAlpha,int depth)throws Exception {
            if(view==excluded||view.getVisibility()!=View.VISIBLE||view.getAlpha()<=0)return;
            require(depth<=64&&++visited<=1024,"view-budget");
            require(!view.isLayoutRequested(),"pending-layout");
            float globalScale=scale(view),unit=globalScale/rasterScale;
            float[] r=rect(view,globalScale),clip=Wire.clip(r,ancestor);if(clip==null)return;
            float alpha=Wire.opacity(inheritedAlpha,view.getAlpha(),view.isEnabled());
            background(view,r,alpha,clip,unit);
            if(view instanceof SeekBar)seek((SeekBar)view,r,alpha,clip,unit);
            else if(view instanceof ProgressBar)progress((ProgressBar)view,r,alpha,clip);
            else if(view instanceof CheckBox)check((CheckBox)view,r,alpha,clip,unit);
            else if(view instanceof CheckedTextView)checkedText((CheckedTextView)view,r,alpha,clip,unit);
            if(view instanceof TextView)text((TextView)view,r,alpha,clip,unit);
            else if(view instanceof ViewGroup){
                require(view instanceof LinearLayout||view instanceof FrameLayout||view instanceof ScrollView
                    ||view instanceof HorizontalScrollView||view instanceof Spinner||view instanceof RelativeLayout||view instanceof ListView,"unsupported-container");
                ViewGroup group=(ViewGroup)view;
                require(group.getChildCount()<=1024,"child-budget");
                float[] childClip=ancestor;
                if(group.getClipChildren()){
                    float[] childBounds=r.clone();
                    if(group.getClipToPadding()){childBounds[0]+=view.getPaddingLeft()*unit;childBounds[1]+=view.getPaddingTop()*unit;childBounds[2]-=view.getPaddingRight()*unit;childBounds[3]-=view.getPaddingBottom()*unit;}
                    childClip=Wire.clip(childBounds,ancestor);if(childClip==null)return;
                }
                ListView list=view instanceof ListView?(ListView)view:null;
                for(int i=0;i<group.getChildCount();i++){
                    View child=group.getChildAt(i);
                    boolean selected=list!=null&&Wire.singleChoice(list.getChoiceMode(),list.getFirstVisiblePosition(),i,list.getCheckedItemPosition());
                    float[] row=null;
                    if(selected){row=rect(child,scale(child));shape(row[0],row[1],row[2],row[3],0x3358b9db,alpha,2,0,childClip);}
                    visit(child,childClip,alpha,depth+1);
                    if(selected){ // A plain adapter TextView may not implement Checkable; retain the ListView's checked state visibly.
                        line(row[0]+1,row[1]+1,row[2]-1,row[1]+1,1,0xff58b9db,alpha,childClip);
                        line(row[0]+1,row[3]-1,row[2]-1,row[3]-1,1,0xff58b9db,alpha,childClip);
                    }
                    if(list!=null&&i+1<group.getChildCount()&&list.getDivider()!=null&&list.getDividerHeight()>0){
                        float[] item=rect(child,scale(child));float divider=unit*list.getDividerHeight();
                        drawable(list.getDivider(),view,new float[]{r[0]+view.getPaddingLeft()*unit,item[3],r[2]-view.getPaddingRight()*unit,item[3]+divider},alpha,childClip,unit,0);
                    }
                }
                if(view instanceof Spinner){float x=r[2]-12,y=(r[1]+r[3])/2;line(x-4,y-2,x,y+2,2,0xffbed4e4,alpha,clip);line(x,y+2,x+4,y-2,2,0xffbed4e4,alpha,clip);}
            }else require(view.getClass()==View.class||view instanceof ProgressBar,"unsupported-widget");
            if(view.getForeground()!=null)drawable(view.getForeground(),view,r,alpha,clip,unit,0);
        }
        void seek(SeekBar bar,float[] r,float alpha,float[] clip,float unit)throws Exception {
            require(bar.getMax()>0,"slider-range");
            float l=r[0]+bar.getPaddingLeft()*unit,right=r[2]-bar.getPaddingRight()*unit,y=(r[1]+r[3])/2;
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
        void check(CheckBox box,float[] r,float alpha,float[] clip,float unit)throws Exception {
            float x=r[0]+box.getPaddingLeft()*unit+3,y=(r[1]+r[3])/2;
            shape(x,y-8,x+16,y+8,box.isEnabled()?0xff7ab5cd:0xff657886,alpha,2,0,clip);
            shape(x+1,y-7,x+15,y+7,0xff1b2b38,alpha,1,0,clip);
            if(box.isChecked()){line(x+3,y,x+7,y+4,2,0xff99d5eb,alpha,clip);line(x+7,y+4,x+13,y-4,2,0xff99d5eb,alpha,clip);}
        }
        void checkedText(CheckedTextView view,float[] r,float alpha,float[] clip,float unit)throws Exception {
            Drawable mark=view.getCheckMarkDrawable();if(mark==null)return;
            Rect bounds=mark.getBounds();require(!bounds.isEmpty(),"checkmark-layout-pending");
            float l=r[0]+bounds.left*unit,t=r[1]+bounds.top*unit,right=r[0]+bounds.right*unit,bottom=r[1]+bounds.bottom*unit;
            boolean radio=false;
            for(android.view.ViewParent parent=view.getParent();parent instanceof View;parent=parent.getParent()){
                if(parent instanceof ListView){radio=((ListView)parent).getChoiceMode()==ListView.CHOICE_MODE_SINGLE;break;}
            }
            int tint=view.getCheckMarkTintList()==null?0xff58b9db:view.getCheckMarkTintList().getColorForState(view.getDrawableState(),view.getCheckMarkTintList().getDefaultColor());
            float radius=radio?Math.min(right-l,bottom-t)/2:2;
            shape(l,t,right,bottom,tint,alpha,radius,0,clip);
            shape(l+2,t+2,right-2,bottom-2,0xff1b2b38,alpha,Math.max(0,radius-2),0,clip);
            if(view.isChecked()){
                if(radio)shape(l+5,t+5,right-5,bottom-5,tint,alpha,Math.max(0,radius-5),0,clip);
                else{float y=(t+bottom)/2;line(l+3,y,l+7,y+4,2,tint,alpha,clip);line(l+7,y+4,right-3,t+3,2,tint,alpha,clip);}
            }
        }
        void text(TextView view,float[] r,float alpha,float[] clip,float unit)throws Exception {
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
            float left=r[0]+(view.getTotalPaddingLeft()-view.getScrollX())*unit;
            float top=r[1]+(view.getTotalPaddingTop()-view.getScrollY())*unit;
            float[] textClip=Wire.clip(new float[]{r[0]+view.getCompoundPaddingLeft()*unit,r[1]+view.getExtendedPaddingTop()*unit,
                r[2]-view.getCompoundPaddingRight()*unit,r[3]-view.getExtendedPaddingBottom()*unit},clip);
            if(textClip==null)return;
            int start=view.getSelectionStart(),end=view.getSelectionEnd();
            if(view instanceof EditText&&view.hasFocus()&&start>=0&&end>=0){
                int a=Math.min(start,end),b=Math.max(start,end);
                for(int i=layout.getLineForOffset(a);i<=layout.getLineForOffset(b);i++){
                    float y1=top+layout.getLineTop(i)*unit,y2=top+layout.getLineBottom(i)*unit;
                    float x1=left+layout.getPrimaryHorizontal(Math.max(a,layout.getLineStart(i)))*unit;
                    float x2=left+layout.getPrimaryHorizontal(Math.min(b,layout.getLineEnd(i)))*unit;
                    if(a==b)shape(x1,y1,x1+1.5f,y2,view.getCurrentTextColor(),alpha,0,0,textClip);
                    else shape(Math.min(x1,x2),y1,Math.max(x1,x2),y2,view.getHighlightColor(),alpha,1,0,textClip);
                }
                EditableComposition(view,layout,left,top,alpha,textClip,unit);
            }
            int firstLine=layout.getLineForVertical((int)Math.floor((textClip[1]-top)/unit));
            int lastLine=layout.getLineForVertical((int)Math.ceil((textClip[3]-top)/unit));
            for(int i=firstLine;i<=lastLine;i++){
                if(top+layout.getLineBottom(i)*unit<=textClip[1]||top+layout.getLineTop(i)*unit>=textClip[3])continue;
                int a=layout.getLineStart(i),b=layout.getLineEnd(i);
                while(b>a&&(displayed.charAt(b-1)=='\n'||displayed.charAt(b-1)=='\r'))b--;
                require(b-a<=256,"text-line-budget");
                String line=displayed.subSequence(a,b).toString();
                int ellipsis=layout.getEllipsisCount(i);
                if(ellipsis>0){int at=layout.getEllipsisStart(i);line=line.substring(0,at)+"\u2026"+line.substring(Math.min(line.length(),at+ellipsis));}
                if(line.isEmpty())continue;
                require(line.length()<=256&&runs.length()<MAX_RUNS,"text-run-budget");
                int index=lines.size();lines.add(line);paints.add(new TextPaint(view.getPaint()));
                runs.put(new JSONObject().put("font_run",index).put("x",left+layout.getLineLeft(i)*unit)
                    .put("y",top+layout.getLineBaseline(i)*unit).put("size",view.getTextSize()*unit).put("color",array(color(hint?view.getCurrentHintTextColor():view.getCurrentTextColor(),alpha))));
                batch("text",index,textClip);
            }
            require(view.getError()==null,"editor-error-popup");
        }
        void EditableComposition(TextView view,Layout layout,float left,float top,float alpha,float[] clip,float unit)throws Exception {
            CharSequence raw=view.getText();if(!(raw instanceof android.text.Spannable))return;
            int start=android.view.inputmethod.BaseInputConnection.getComposingSpanStart((android.text.Spannable)raw);
            int end=android.view.inputmethod.BaseInputConnection.getComposingSpanEnd((android.text.Spannable)raw);
            if(start<0||end<=start)return;
            for(int i=layout.getLineForOffset(start);i<=layout.getLineForOffset(end);i++){
                float x1=left+layout.getPrimaryHorizontal(Math.max(start,layout.getLineStart(i)))*unit;
                float x2=left+layout.getPrimaryHorizontal(Math.min(end,layout.getLineEnd(i)))*unit;
                float y=top+layout.getLineBaseline(i)*unit+2;
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
