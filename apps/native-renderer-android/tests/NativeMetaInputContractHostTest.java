package io.github.mesmerprism.rustyquest;

/** Host-only checks of the actual transport value validation, no Android calls. */
public final class NativeMetaInputContractHostTest {
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    private static void refuse(long generation, long[] values) {
        try { new NativeMetaInputContract.RenderStatus(generation, values); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid completion accepted");
    }
    public static void main(String[] args) {
        require(NativeMetaInputContract.VERSION == 2);
        require(NativeMetaInputContract.DESCRIPTOR.endsWith(".v2"));
        for (long[] size : new long[][] {{1280,672},{1280,1280},{2560,1280}}) {
            require(NativeMetaInputContract.isClosedInputDimensions(size[0],size[1]));
        }
        for (long[] size : new long[][] {{2560,672},{1280,0},{2559,1280},{2560,1281},{-1,1280}}) {
            require(!NativeMetaInputContract.isClosedInputDimensions(size[0],size[1]));
        }
        for (int mode : new int[] {0,1,2}) require(NativeMetaInputContract.isSupportedMode(mode));
        for (int mode : new int[] {-1,3,Integer.MAX_VALUE}) require(!NativeMetaInputContract.isSupportedMode(mode));
        for (int mode : new int[] {0,1,2}) {
            long[] values={17,4,mode,4,mode,0,8,1234,1L<<mode,1};
            NativeMetaInputContract.RenderStatus status = new NativeMetaInputContract.RenderStatus(17,values);
            require(status.completedMode == mode && status.completedValid && status.witnessMask == (1L<<mode));
        }
        long[] valid={17,4,2,4,2,0,8,1234,4,1};
        refuse(18,valid);
        long[] bad=valid.clone();bad[3]=5;refuse(17,bad);
        bad=valid.clone();bad[2]=3;refuse(17,bad);
        bad=valid.clone();bad[4]=3;refuse(17,bad);
        bad=valid.clone();bad[7]=0;refuse(17,bad);
        bad=valid.clone();bad[8]=8;refuse(17,bad);
        System.out.println("PASS closed reader layouts, v2 modes, generation/completion/witness boundaries");
    }
}
