package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
/** Host observations only. Any media/frame/native effect is a fixture failure. */
public final class EmbeddedDuplexNative {
 public static final int FRAME_OBSERVATION_WORDS=17, FRAME_TIMED_OBSERVATION_WORDS=19;
 public static boolean quiescent=true;
 public static boolean localCameraQuiescent(){return quiescent;}
 public static boolean registerReceiverFrame(long[] a){throw new AssertionError("no native frame registration");}
 public static boolean recordReceiverFrameRendered(long[] a){throw new AssertionError("no native rendering");}
 public static long[] currentReceiverFrame(long a,long b,long c,long d,long e,long f){throw new AssertionError("no frame observations");}
 public static long[] currentReceiverFrameTimed(long a,long b,long c,long d,long e,long f){throw new AssertionError("no timed frame observations");}
 public static void retireReceiverGeneration(long a){throw new AssertionError("no native generation retirement");}
 public static void retireReceiverConnection(long a,long b){throw new AssertionError("no native connection retirement");}
 static byte[] handleOwnerFrame(byte[] a){throw new AssertionError("no native dispatch");}
}
