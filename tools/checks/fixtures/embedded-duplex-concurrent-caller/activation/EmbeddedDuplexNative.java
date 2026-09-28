package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
public final class EmbeddedDuplexNative {
 public static final int FRAME_OBSERVATION_WORDS=17,FRAME_TIMED_OBSERVATION_WORDS=19;static String mode="happy";static int reads;static boolean rendered;
 public static boolean registerReceiverFrame(long[] a){return a.length==14&&a[0]==1&&a[1]==1&&a[2]==3&&a[3]==4&&a[4]==5;}
 public static boolean recordReceiverFrameRendered(long[] a){if(!registerReceiverFrame(a))return false;rendered=true;return true;}
 public static long[] currentReceiverFrame(long g,long c,long r,long d,long reader,long age){reads++;if(!rendered||(mode.equals("delayed")&&reads==1)||mode.equals("zero-native-acquisition")||mode.equals("expiry")||mode.equals("cancelled"))return null;long[] a=new long[17];a[0]=mode.equals("foreign-frame")?g+1:g;a[1]=c;a[2]=r;a[3]=d;a[4]=reader;return a;}
 public static long[] currentReceiverFrameTimed(long a,long b,long c,long d,long e,long f){throw new AssertionError();}
 public static void retireReceiverGeneration(long a){throw new AssertionError();}public static void retireReceiverConnection(long a,long b){throw new AssertionError();}
}
