package io.github.mesmerprism.rustyquest.native_renderer;

public final class ExperimentSessionBleNamePolicyTest {
    private static int controls;
    private static void expect(String name, String expected) {
        if (!expected.equals(ExperimentSessionBleNamePolicy.disposition(name))) {
            throw new AssertionError("Wrong scan-response name disposition");
        }
        controls++;
    }
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    public static void main(String[] args) {
        expect(null, "unavailable");
        expect("", "unavailable");
        expect("  \t", "unavailable");
        expect("Quest", "included");
        expect(repeat("x", 29), "included");
        expect(repeat("x", 30), "exceeds-scan-response-budget");
        expect(repeat("\u00e9", 14) + "x", "included");
        expect(repeat("\u00e9", 15), "exceeds-scan-response-budget");
        expect(repeat("\u4e16", 9) + "xx", "included");
        expect(repeat("\u4e16", 10), "exceeds-scan-response-budget");
        expect(repeat("\ud83d\ude00", 7) + "x", "included");
        expect(repeat("\ud83d\ude00", 7) + "xx", "exceeds-scan-response-budget");
        expect(repeat("x", 1024), "exceeds-scan-response-budget");
        System.out.println("BLE scan-response name controls passed: " + controls);
    }
}
