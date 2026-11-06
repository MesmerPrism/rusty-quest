package io.github.mesmerprism.rustyquest.native_renderer;

import java.lang.reflect.*;
import java.util.Arrays;

/** Actual page admission and SDF CPU helpers; Android platform shaping is a separate device check. */
public final class NativePageFontTest {
    static int assertions;
    static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] ignored)throws Exception {
        Class<?> font=Class.forName("io.github.mesmerprism.rustyquest.native_renderer.NativePanelFont");
        Method validate;
        try{validate=font.getDeclaredMethod("validatePageLines",String[].class);}
        catch(NoSuchMethodException absent){throw new AssertionError("page font pre-shaping admission absent",absent);}
        validate.setAccessible(true);
        check(((Integer)validate.invoke(null,(Object)new String[]{"hello","\ud83d\ude42"}))==7,"Unicode admission counts UTF16");
        String[] boundary=new String[128];Arrays.fill(boundary,"a");
        check(((Integer)validate.invoke(null,(Object)boundary))==128,"page admits more than toolbar32 runs");
        reject(validate,new String[129],"page run cap");reject(validate,new String[]{null},"null line");
        reject(validate,new String[]{"a\nb"},"multiline is split by original Layout");
        reject(validate,new String[]{"\ud800"},"unpaired high surrogate");reject(validate,new String[]{"\udc00"},"unpaired low surrogate");
        char[] longLine=new char[257];Arrays.fill(longLine,'a');reject(validate,new String[]{new String(longLine)},"line cap");
        Method sdf=font.getDeclaredMethod("distanceField",byte[].class,int.class,int.class);sdf.setAccessible(true);
        byte[] coverage={0,0,0,0,(byte)255,0,0,0,0};
        byte[] distance=(byte[])sdf.invoke(null,coverage,3,3);
        check((distance[4]&255)>128,"solid center inside contour");
        check((distance[0]&255)<(distance[1]&255),"Euclidean corner farther than adjacent edge");
        check((distance[1]&255)==(distance[3]&255)&&distance[1]==distance[5]&&distance[3]==distance[7],"symmetric SDF");
        System.out.println("NativePageFontTest PASS assertions="+assertions+" scope=page-admission-real-SDF; no Android TextShaper claim");
    }
    static void reject(Method method,String[] lines,String why)throws Exception {
        try{method.invoke(null,(Object)lines);throw new AssertionError("accepted "+why);}
        catch(InvocationTargetException error){check(error.getCause() instanceof IllegalArgumentException,why);}
    }
}
