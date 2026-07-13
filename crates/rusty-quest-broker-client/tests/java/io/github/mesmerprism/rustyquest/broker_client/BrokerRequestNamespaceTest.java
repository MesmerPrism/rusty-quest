package io.github.mesmerprism.rustyquest.broker_client;

import java.util.Arrays;

/** Sequential relaunch, deliberate replay, and provider-restart request-id checks. */
public final class BrokerRequestNamespaceTest {
    public static void main(String[] args) {
        BrokerRequestNamespace first = BrokerRequestNamespace.fromEntropy(fill((byte) 0x11));
        BrokerRequestNamespace relaunch = BrokerRequestNamespace.fromEntropy(fill((byte) 0x22));
        BrokerRequestNamespace afterProviderRestart =
                BrokerRequestNamespace.fromEntropy(fill((byte) 0x33));

        String firstUse = first.requestId("client.native-renderer", "use");
        String deliberateReplayUse = firstUse;
        assertEquals(firstUse, deliberateReplayUse);
        assertDifferent(firstUse, first.requestId("client.native-renderer", "use"));
        assertDifferent(firstUse, first.requestId("client.native-renderer", "after-revoke"));
        assertDifferent(firstUse, relaunch.requestId("client.native-renderer", "use"));
        assertDifferent(
                relaunch.requestId("client.native-renderer", "issue"),
                afterProviderRestart.requestId("client.native-renderer", "issue"));
    }

    private static byte[] fill(byte value) {
        byte[] output = new byte[16];
        Arrays.fill(output, value);
        return output;
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected deliberate replay id reuse");
        }
    }

    private static void assertDifferent(String left, String right) {
        if (left.equals(right)) {
            throw new AssertionError("sequential launch/restart ids must differ");
        }
    }
}
