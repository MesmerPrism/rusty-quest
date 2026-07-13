package io.github.mesmerprism.rustymanifold.broker;

/** Host-side damaged-input test for shared/ambiguous Binder UIDs. */
public final class BinderCallerPackageResolverTest {
    public static void main(String[] args) {
        assertEquals(
                "io.github.example.client",
                BinderCallerPackageResolver.requireUnambiguousPackage(
                        new String[] {"io.github.example.client"}));
        expectSecurity(null);
        expectSecurity(new String[0]);
        expectSecurity(new String[] {"io.github.example.first", "io.github.example.second"});
        expectSecurity(new String[] {" "});
    }

    private static void expectSecurity(String[] packages) {
        try {
            BinderCallerPackageResolver.requireUnambiguousPackage(packages);
            throw new AssertionError("damaged Binder package projection was accepted");
        } catch (SecurityException expected) {
            // Expected fail-closed result.
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
