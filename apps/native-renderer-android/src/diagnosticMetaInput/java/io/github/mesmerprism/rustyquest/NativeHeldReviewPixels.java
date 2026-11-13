package io.github.mesmerprism.rustyquest;

/** Bounded byte conversion only; no effect, resampling, alpha removal or row reordering. */
final class NativeHeldReviewPixels {
    static int[] decode(int width,int height,int format,byte[] bytes) {
        if(width<=0||height<=0||width>4096||height>4096||(format!=37&&format!=44)||bytes==null||(long)width*height*4!=bytes.length)throw new IllegalArgumentException("closed held image shape required");
        int[] argb=new int[width*height];
        for(int p=0;p<argb.length;p++){
            int i=p*4,r=bytes[i+(format==44?2:0)]&255,g=bytes[i+1]&255,b=bytes[i+(format==44?0:2)]&255;
            argb[p]=((bytes[i+3]&255)<<24)|(r<<16)|(g<<8)|b;
        }
        return argb;
    }
}
