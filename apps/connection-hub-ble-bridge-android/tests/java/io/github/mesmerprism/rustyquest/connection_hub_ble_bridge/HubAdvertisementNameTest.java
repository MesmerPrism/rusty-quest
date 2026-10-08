package io.github.mesmerprism.rustyquest.connection_hub_ble_bridge;
public final class HubAdvertisementNameTest {
    private static int cases;
    private static void accepts(String name){if(!HubGattBridge.useScanResponseName(name))throw new AssertionError("named path denied");cases++;}
    private static void rejects(String name){if(HubGattBridge.useScanResponseName(name))throw new AssertionError("anonymous fallback denied");cases++;}
    public static void main(String[] args)throws Exception{
        accepts("Meta Quest 2");accepts("Q".repeat(29));rejects("Q".repeat(30));
        accepts("界".repeat(9));rejects("界".repeat(10));
        accepts("😀".repeat(7)+"Q");rejects("😀".repeat(7)+"QQ");
        rejects(null);rejects("");rejects("  \t  ");
        System.out.println("PASS "+cases+" production cases");
    }
}
