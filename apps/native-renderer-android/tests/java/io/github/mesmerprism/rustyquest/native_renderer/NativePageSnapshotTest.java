package io.github.mesmerprism.rustyquest.native_renderer;

import java.lang.reflect.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Pure production packet/lifetime tests. Does not pretend to exercise Android layout or IME. */
public final class NativePageSnapshotTest {
    private static int assertions;
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Class<?> wire;
        try{wire=Class.forName("io.github.mesmerprism.rustyquest.native_renderer.NativePageSnapshot$Wire");}
        catch(ClassNotFoundException absent){throw new AssertionError("native page publication implementation absent",absent);}
        Method pack=wire.getDeclaredMethod("pack",byte[].class,byte[].class);pack.setAccessible(true);
        byte[] json="{\"revision\":1}".getBytes(StandardCharsets.UTF_8),font={7,8,9};
        byte[] packet=(byte[])pack.invoke(null,json,font);
        ByteBuffer header=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        check(header.getInt()==0x3147504e,"NPG1 magic");check(header.getInt()==1,"version");
        check(header.getInt()==json.length,"UTF8 byte length");check(header.getInt()==3,"atlas length");
        check(packet.length==16+json.length+3,"exact envelope length");
        check(Arrays.equals(Arrays.copyOfRange(packet,16,16+json.length),json),"scene bytes unchanged");
        reject(pack,new byte[0],font,"empty scene");reject(pack,new byte[512*1024+1],font,"scene byte cap");
        reject(pack,json,new byte[1300001],"font byte cap");
        Method current=wire.getDeclaredMethod("current",long.class,long.class,long.class,long.class,long.class,long.class);current.setAccessible(true);
        check((Boolean)current.invoke(null,1L,2L,3L,1L,2L,3L),"matching live owner");
        check(!(Boolean)current.invoke(null,1L,2L,3L,1L,4L,3L),"stale content rejected");
        check(!(Boolean)current.invoke(null,1L,2L,3L,1L,2L,4L),"stale raster rejected");
        check(!(Boolean)current.invoke(null,1L,2L,3L,4L,2L,3L),"stale producer rejected");
        check(!(Boolean)current.invoke(null,0L,2L,3L,0L,2L,3L),"zero identity rejected");
        Method clip=wire.getDeclaredMethod("clip",float[].class,float[].class);clip.setAccessible(true);
        float[] full={-10,20,100,900},viewport={0,0,1024,768};
        check(Arrays.equals((float[])clip.invoke(null,full,viewport),new float[]{0,20,100,768}),"clip intersects without moving full origin");
        check(full[0]==-10&&full[3]==900,"full geometry remains unmodified");
        check(clip.invoke(null,new float[]{-20,0,-10,20},viewport)==null,"empty clip omitted");
        Method opacity;
        try{opacity=wire.getDeclaredMethod("opacity",float.class,float.class,boolean.class);}
        catch(NoSuchMethodException absent){throw new AssertionError("disabled widget presentation policy absent",absent);}
        opacity.setAccessible(true);
        check(Math.abs((Float)opacity.invoke(null,1f,1f,false)-0.55f)<0.0001f,"disabled control visibly muted");
        check((Float)opacity.invoke(null,1f,1f,true)==1f,"enabled control unmodified");
        check(Math.abs((Float)opacity.invoke(null,0.5f,0.5f,true)-0.25f)<0.0001f,"ancestor opacity retained");
        try{opacity.invoke(null,Float.NaN,1f,true);throw new AssertionError("nonfinite opacity admitted");}
        catch(InvocationTargetException error){check(error.getCause() instanceof IllegalArgumentException,"nonfinite opacity rejected atomically");}
        Class<?> publication=Class.forName("io.github.mesmerprism.rustyquest.native_renderer.NativePageSnapshot$Publication");
        Constructor<?> ctor=publication.getDeclaredConstructor(long.class,long.class,long.class,long.class,long.class,byte[].class,byte[].class);ctor.setAccessible(true);
        Object value=ctor.newInstance(1L,2L,3L,4L,5L,json,font);
        Method read=publication.getDeclaredMethod("read",long.class,long.class,long.class);read.setAccessible(true);
        check(read.invoke(value,9L,0L,0L)==null,"other generation unavailable");
        check(((byte[])read.invoke(value,1L,3L,4L)).length==0,"matching revision and atlas unchanged");
        byte[] with=(byte[])read.invoke(value,1L,3L,0L);
        check(ByteBuffer.wrap(with).order(ByteOrder.LITTLE_ENDIAN).getInt(12)==3,"atlas resent after receiver cache loss");
        byte[] without=(byte[])read.invoke(value,1L,0L,4L);
        check(ByteBuffer.wrap(without).order(ByteOrder.LITTLE_ENDIAN).getInt(12)==0,"geometry revision reuses current atlas");
        json[0]=0;font[0]=0;with[16]=0;
        check(((byte[])read.invoke(value,1L,0L,0L))[16]=='{',"published bytes privately immutable");
        Method layerSteps;
        try{layerSteps=wire.getDeclaredMethod("layerSteps",long[].class,float[].class);}
        catch(NoSuchMethodException absent){throw new AssertionError("ordered retained-window presentation absent",absent);}
        layerSteps.setAccessible(true);
        check(Arrays.equals((int[])layerSteps.invoke(null,new long[]{0,7,11},new float[]{0,0.4f,0.6f}),new int[]{0,-2,1,-3,2}),"root then dim/modal then dim/top-modal paint order");
        check(Arrays.equals((int[])layerSteps.invoke(null,new long[]{0,7},new float[]{0,0}),new int[]{0,1}),"no unrequested dimming");
        rejectLayers(layerSteps,new long[]{0,7,7},new float[]{0,0.2f,0.3f},"duplicate window identity");
        rejectLayers(layerSteps,new long[]{0,7},new float[]{0,Float.NaN},"nonfinite window dim");
        rejectLayers(layerSteps,new long[]{1},new float[]{0},"missing original root window");
        rejectLayers(layerSteps,new long[]{0,1,2,3,4,5,6,7,8},new float[9],"window budget");
        Method geometry=wire.getDeclaredMethod("windowRect",float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class);geometry.setAccessible(true);
        float[] decor=(float[])geometry.invoke(null,300f,150f,900f,600f,1f,0f,0f,1.5f);
        float[] child=(float[])geometry.invoke(null,300f,150f,600f,400f,1.5f,0f,0f,1.5f);
        check(Arrays.equals(decor,new float[]{200,100,800,500}),"physical decor normalized once");
        check(Arrays.equals(decor,child),"scaled logical modal content agrees with physical decor");
        float[] moved=(float[])geometry.invoke(null,600f,450f,900f,600f,1f,300f,300f,1.5f);
        check(Arrays.equals(decor,moved),"common presentation screen origin preserves window placement");
        Method selected=wire.getDeclaredMethod("singleChoice",int.class,int.class,int.class,int.class);selected.setAccessible(true);
        check((Boolean)selected.invoke(null,1,5,2,7),"actual scrolled list checked position projects to visible row");
        check(!(Boolean)selected.invoke(null,1,5,1,7),"unchecked sibling stays unchecked");
        check(!(Boolean)selected.invoke(null,0,5,2,7),"nonchoice menu never acquires selected choice");
        Method wrapper;
        try{wrapper=wire.getDeclaredMethod("boundsOnlyWrapper",Class.class);}
        catch(NoSuchMethodException absent){throw new AssertionError("drawable wrapper semantic admission absent",absent);}
        wrapper.setAccessible(true);
        check((Boolean)wrapper.invoke(null,Class.forName("android.graphics.drawable.InsetDrawable")),"actual bounds-only inset admitted");
        for(String name:new String[]{"ClipDrawable","RotateDrawable","ScaleDrawable"}){
            check(!(Boolean)wrapper.invoke(null,Class.forName("android.graphics.drawable."+name)),name+" canvas/level semantics cannot be dropped");
        }
        check(!(Boolean)wrapper.invoke(null,UnreviewedInset.class),"custom inset override cannot inherit native admission");
        if(args.length==1){
            // Neutral interoperability fixture, never actual field text or a runtime snapshot.
            byte[] glyph=ByteBuffer.allocate(81).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x3154504e).putInt(1).putInt(1).putInt(1).putInt(1).putInt(1).putInt(64).putInt(8)
                .putFloat(1).putFloat(-1).putFloat(0).putInt(1)
                .putFloat(0).putFloat(-1f/64).putFloat(1f/64).putFloat(1f/64).putFloat(0).putFloat(0).putFloat(1).putFloat(1).put((byte)255).array();
            String neutral="{\"generation\":1,\"revision\":1,\"content_epoch\":1,\"atlas_epoch\":1,\"raster_epoch\":1,\"page_id\":\"interop-only\",\"logical_width\":1024,\"logical_height\":768,\"complete\":true,\"interactive\":true,\"shapes\":[{\"rect\":[32,32,16,8],\"color\":[1,1,1,1],\"shape\":[4,0,0,0]}],\"text_runs\":[{\"font_run\":0,\"x\":16,\"y\":32,\"size\":16,\"color\":[1,1,1,1]}],\"batches\":[{\"kind\":\"shapes\",\"first\":0,\"count\":1,\"clip\":[0,0,1024,768]},{\"kind\":\"text\",\"first\":0,\"count\":1,\"clip\":[0,0,1024,768]}]}";
            java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),(byte[])pack.invoke(null,neutral.getBytes(StandardCharsets.UTF_8),glyph));
        }
        System.out.println("NativePageSnapshotTest PASS assertions="+assertions+" scope=wire-budget-clip-immutable-publication-owner-fences; no Android/IME claim");
    }
    static void reject(Method method,byte[] json,byte[] atlas,String reason)throws Exception {
        try{method.invoke(null,json,atlas);throw new AssertionError("accepted "+reason);}
        catch(InvocationTargetException error){check(error.getCause() instanceof IllegalArgumentException,reason+" explicit rejection");}
    }
    static void rejectLayers(Method method,long[] ids,float[] dims,String reason)throws Exception {
        try{method.invoke(null,ids,dims);throw new AssertionError("accepted "+reason);}
        catch(InvocationTargetException error){check(error.getCause() instanceof IllegalArgumentException,reason+" explicit rejection");}
    }
    static final class UnreviewedInset extends android.graphics.drawable.InsetDrawable {
        UnreviewedInset(){super((android.graphics.drawable.Drawable)null,0);}
    }
}
