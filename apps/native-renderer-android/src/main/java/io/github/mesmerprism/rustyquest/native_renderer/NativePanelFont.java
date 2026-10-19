package io.github.mesmerprism.rustyquest.native_renderer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.text.PositionedGlyphs;
import android.os.Build;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextShaper;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;

/** One-shot platform shaping and monochrome signed-distance atlas. No font assets retained. */
public final class NativePanelFont {
    private static final int EM=64, RANGE=8, PAD=10, LIMIT=1024;
    private NativePanelFont() {}
    private static final class Key {
        final Font font; final int id;
        Key(Font f,int i) { font=f; id=i; }
        public int hashCode() { return font.hashCode()*31+id; }
        public boolean equals(Object o) { return o instanceof Key && ((Key)o).id==id && ((Key)o).font.equals(font); }
    }
    private static final class Tile { int left,top,w,h,x,y; byte[] sdf; }
    private static final class Glyph { Tile tile; float x,y; }
    private static final class Run { float advance,ascent,descent; final ArrayList<Glyph> glyphs=new ArrayList<Glyph>(); }
    private static void require(boolean ok,String reason) { if(!ok) throw new IllegalArgumentException("native panel font: "+reason); }
    private static boolean finite(float f) { return !Float.isNaN(f)&&!Float.isInfinite(f); }

    /** NPT1 little-endian wire v1: 8 u32 header, run metrics/count/glyphs, then R8 pixels. */
    public static byte[] prepare(String[] labels) {
        if(Build.VERSION.SDK_INT<31) throw new UnsupportedOperationException("native panel font requires Android API 31");
        long started=System.nanoTime();
        require(labels!=null && labels.length<=32,"run budget");
        final TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL)); paint.setTextSize(EM); paint.setColor(Color.WHITE);
        final HashMap<Key,Tile> cache=new HashMap<Key,Tile>();
        final ArrayList<Tile> tiles=new ArrayList<Tile>();
        ArrayList<Run> runs=new ArrayList<Run>(); final int[] total={0};
        for(String label:labels) {
            require(label!=null && label.length()<=256,"label budget");
            for(int i=0;i<label.length();i++) { char c=label.charAt(i); require(c!='\n'&&c!='\r'&&c!='\t',"single line only");
                if(Character.isHighSurrogate(c)) { require(i+1<label.length()&&Character.isLowSurrogate(label.charAt(++i)),"surrogate"); }
                else require(!Character.isLowSurrogate(c),"surrogate"); }
            final Run run=new Run();
            TextShaper.shapeText(label,0,label.length(),TextDirectionHeuristics.FIRSTSTRONG_LTR,paint,new TextShaper.GlyphsConsumer() {
                public void accept(int start,int count,PositionedGlyphs shaped,TextPaint style) {
                    require(total[0]+shaped.glyphCount()<=1024,"glyph budget"); total[0]+=shaped.glyphCount();
                    run.advance+=shaped.getAdvance()/EM;
                    run.ascent=Math.min(run.ascent,shaped.getAscent()/EM); run.descent=Math.max(run.descent,shaped.getDescent()/EM);
                    for(int i=0;i<shaped.glyphCount();i++) {
                        require(shaped.getGlyphId(i)!=0,"missing glyph");
                        Key key=new Key(shaped.getFont(i),shaped.getGlyphId(i)); Tile tile=cache.get(key);
                        if(tile==null) { require(cache.size()<256,"unique glyph budget"); tile=raster(key,style); cache.put(key,tile); tiles.add(tile); }
                        if(tile.w>0) { Glyph g=new Glyph(); g.tile=tile; g.x=shaped.getGlyphX(i); g.y=shaped.getGlyphY(i); run.glyphs.add(g); }
                    }
                }
            });
            require(finite(run.advance)&&finite(run.ascent)&&finite(run.descent),"nonfinite metrics"); runs.add(run);
        }
        int x=0,y=0,row=0;
        for(Tile t:tiles) { if(t.w==0) continue; if(x+t.w>LIMIT) { x=0;y+=row;row=0; } require(y+t.h<=LIMIT,"atlas full");t.x=x;t.y=y;x+=t.w;row=Math.max(row,t.h); }
        int height=1; while(height<y+row) height*=2;
        byte[] pixels=new byte[LIMIT*height]; int visible=0;
        for(Tile t:tiles) if(t.w>0) for(int j=0;j<t.h;j++) System.arraycopy(t.sdf,j*t.w,pixels,(t.y+j)*LIMIT+t.x,t.w);
        for(Run r:runs) visible+=r.glyphs.size();
        ByteBuffer out=ByteBuffer.allocate(32+runs.size()*16+visible*32+pixels.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x3154504e).putInt(1).putInt(LIMIT).putInt(height).putInt(runs.size()).putInt(pixels.length).putInt(EM).putInt(RANGE);
        for(Run r:runs) { out.putFloat(r.advance).putFloat(r.ascent).putFloat(r.descent).putInt(r.glyphs.size());
            for(Glyph g:r.glyphs) { Tile t=g.tile;
                out.putFloat((g.x+t.left)/EM).putFloat((g.y+t.top)/EM).putFloat((float)t.w/EM).putFloat((float)t.h/EM);
                out.putFloat((float)t.x/LIMIT).putFloat((float)t.y/height).putFloat((float)(t.x+t.w)/LIMIT).putFloat((float)(t.y+t.h)/height);
            }
        }
        out.put(pixels);
        Log.i("NativePanelFont","prepared runs="+runs.size()+" unique="+cache.size()+" glyphs="+total[0]+" api="+Build.VERSION.SDK_INT+" atlas="+LIMIT+"x"+height+" prepMs="+((System.nanoTime()-started)/1000000));
        return out.array();
    }
    private static Tile raster(Key key,TextPaint paint) {
        // Color-font tables are intentionally unsupported: this atlas represents monochrome coverage.
        ByteBuffer font=key.font.getBuffer().duplicate().order(ByteOrder.BIG_ENDIAN);
        int base=0;
        if(font.getInt(0)==0x74746366) { int index=key.font.getTtcIndex(); require(index>=0&&index<font.getInt(8),"TTC index");base=font.getInt(12+index*4); }
        require(base>=0 && base<=font.limit()-12,"font directory"); int tables=font.getShort(base+4)&65535;
        require(tables<=256 && base+12L+tables*16L<=font.limit(),"font tables");
        for(int i=0;i<tables;i++) { int tag=font.getInt(base+12+i*16); require(tag!=0x434f4c52&&tag!=0x43424454&&tag!=0x73626978&&tag!=0x53564720,"color font unsupported"); }
        RectF b=new RectF();key.font.getGlyphBounds(key.id,paint,b); Tile t=new Tile();
        require(finite(b.left)&&finite(b.top)&&finite(b.right)&&finite(b.bottom),"glyph bounds");
        if(b.isEmpty()) return t;
        t.left=(int)Math.floor(b.left)-PAD;t.top=(int)Math.floor(b.top)-PAD;
        t.w=(int)Math.ceil(b.right)+PAD-t.left;t.h=(int)Math.ceil(b.bottom)+PAD-t.top;
        require(t.w>0&&t.h>0&&t.w<=256&&t.h<=256,"glyph dimensions");
        Bitmap bitmap=Bitmap.createBitmap(t.w,t.h,Bitmap.Config.ARGB_8888);
        try { Canvas c=new Canvas(bitmap); c.drawGlyphs(new int[]{key.id},0,new float[]{-t.left,-t.top},0,1,key.font,paint);
            int[] argb=new int[t.w*t.h]; bitmap.getPixels(argb,0,t.w,0,0,t.w,t.h); byte[] coverage=new byte[argb.length];
            for(int i=0;i<argb.length;i++) { int a=argb[i]>>>24; require(a==0||(argb[i]&0xffffff)==0xffffff,"colored coverage unsupported");coverage[i]=(byte)a; }
            t.sdf=distanceField(coverage,t.w,t.h);
        } finally { bitmap.recycle(); }
        return t;
    }
    /** Exact squared Euclidean transform, two separable lower-envelope passes. */
    static float[] distances(byte[] alpha,int w,int h,boolean inside) {
        float[] data=new float[w*h]; float inf=1e6f;
        for(int i=0;i<data.length;i++) data[i]=(((alpha[i]&255)>=128)==inside)?0:inf;
        float[] f=new float[Math.max(w,h)],d=new float[f.length];int[] v=new int[f.length];float[] z=new float[f.length+1];
        for(int y=0;y<h;y++) { System.arraycopy(data,y*w,f,0,w); edt(f,d,v,z,w);System.arraycopy(d,0,data,y*w,w); }
        for(int x=0;x<w;x++) { for(int y=0;y<h;y++) f[y]=data[y*w+x];edt(f,d,v,z,h);for(int y=0;y<h;y++) data[y*w+x]=d[y]; }
        return data;
    }
    private static void edt(float[] f,float[] d,int[] v,float[] z,int n) {
        int k=0;v[0]=0;z[0]=Float.NEGATIVE_INFINITY;z[1]=Float.POSITIVE_INFINITY;
        for(int q=1;q<n;q++) { float s;
            do { int p=v[k];s=((f[q]+q*q)-(f[p]+p*p))/(2f*(q-p));if(s<=z[k]) k--;else break; } while(k>=0);
            k++;v[k]=q;z[k]=s;z[k+1]=Float.POSITIVE_INFINITY;
        }
        k=0;for(int q=0;q<n;q++) { while(z[k+1]<q) k++;float delta=q-v[k];d[q]=delta*delta+f[v[k]]; }
    }
    static byte[] distanceField(byte[] alpha,int w,int h) {
        require(w>0&&h>0&&w<=256&&h<=256&&alpha.length==w*h,"coverage budget");
        float[] toIn=distances(alpha,w,h,true),toOut=distances(alpha,w,h,false);byte[] out=new byte[alpha.length];
        for(int i=0;i<out.length;i++) { int a=alpha[i]&255;float signed=a>0&&a<255?(a/255f-.5f):a>=128?(float)Math.sqrt(toOut[i])-.5f:.5f-(float)Math.sqrt(toIn[i]);
            out[i]=(byte)Math.round(255*Math.max(0,Math.min(1,.5f+signed/(2*RANGE)))); }
        return out;
    }
}

