package io.github.mesmerprism.rustyquest;
public final class NativeHeldReviewPixelsHostTest {
    private static void require(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        byte[] rgba={(byte)255,2,3,4,5,6,7,(byte)255};
        int[] a=NativeHeldReviewPixels.decode(1,2,37,rgba);
        require(a.length==2&&a[0]==0x04ff0203&&a[1]==0xff050607);
        int[] b=NativeHeldReviewPixels.decode(2,1,44,rgba);
        require(b[0]==0x040302ff&&b[1]==0xff070605);
        for(int n=0;n<4;n++){
            try{NativeHeldReviewPixels.decode(n==0?0:1,n==1?4097:1,n==2?43:37,n==3?new byte[3]:new byte[4]);}
            catch(IllegalArgumentException expected){continue;}
            throw new AssertionError("invalid image accepted");
        }
        System.out.println("PASS RGBA/BGRA, alpha, row order and bounded shape");
    }
}
