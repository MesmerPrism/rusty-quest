package io.github.mesmerprism.rustymanifold.broker;

/** Fail-closed package projection for a Binder sending UID. */
final class BinderCallerPackageResolver {
    private BinderCallerPackageResolver() {}

    static String requireUnambiguousPackage(String[] packages) {
        if (packages == null || packages.length == 0) {
            throw new SecurityException("Binder UID has no package");
        }
        if (packages.length != 1) {
            throw new SecurityException("Binder UID maps to multiple packages");
        }
        String packageName = packages[0] == null ? "" : packages[0].trim();
        if (packageName.isEmpty()) {
            throw new SecurityException("Binder UID package is empty");
        }
        return packageName;
    }
}
