package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;

/** Reads the installed base APK only; this cache never supplies execution authority. */
final class InstalledApkDigest {
    private final InstalledApkDigestMemo memo = new InstalledApkDigestMemo();

    String read(Context context) throws Exception {
        return memo.read(() -> open(context));
    }

    private static InstalledApkDigestMemo.Selection open(Context context) throws Exception {
        Identity before = capture(context);
        FileInputStream input = new FileInputStream(before.path);
        try {
            requireDescriptor(before, Os.fstat(input.getFD()));
            return new InstalledApkDigestMemo.Selection() {
                public Object identity() { return before; }
                public InputStream input() { return input; }
                public void requireCurrent() throws Exception {
                    if (!before.equals(capture(context)))
                        throw new IllegalStateException("installed APK changed during observation");
                    requireDescriptor(before, Os.fstat(input.getFD()));
                }
                public void close() throws java.io.IOException { input.close(); }
            };
        } catch (Exception failure) {
            try { input.close(); } catch (java.io.IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private static Identity capture(Context context) throws Exception {
        String name = context.getPackageName();
        PackageInfo installed = context.getPackageManager().getPackageInfo(name, 0);
        if (installed.applicationInfo == null || !name.equals(installed.packageName)
                || installed.applicationInfo.sourceDir == null
                || !installed.applicationInfo.sourceDir.equals(context.getApplicationInfo().sourceDir))
            throw new IllegalStateException("installed package source identity unavailable");
        String path = new File(installed.applicationInfo.sourceDir).getCanonicalPath();
        StructStat stat = Os.stat(path);
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= 0)
            throw new IllegalStateException("installed APK is not a regular nonempty file");
        return new Identity(path, new Object[] { name, installed.getLongVersionCode(),
                installed.versionName, installed.firstInstallTime, installed.lastUpdateTime,
                installed.applicationInfo.uid, installed.applicationInfo.sourceDir }, stat);
    }

    private static void requireDescriptor(Identity expected, StructStat opened) {
        if (!Arrays.equals(expected.file, fileIdentity(opened)))
            throw new IllegalStateException("installed APK descriptor identity changed");
    }

    private static long[] fileIdentity(StructStat stat) {
        // Exclude access time: hashing itself may update it. API34 supplies nanoseconds.
        return new long[] { stat.st_dev, stat.st_ino, stat.st_mode, stat.st_uid, stat.st_gid,
                stat.st_nlink, stat.st_size, stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec,
                stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec };
    }

    private static final class Identity {
        final String path;
        final Object[] installed;
        final long[] file;
        Identity(String path, Object[] installed, StructStat stat) {
            this.path = path; this.installed = installed; this.file = fileIdentity(stat);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false;
            Identity next = (Identity) other;
            return path.equals(next.path) && Arrays.equals(installed, next.installed)
                    && Arrays.equals(file, next.file);
        }
        @Override public int hashCode() {
            return 31 * (31 * path.hashCode() + Arrays.hashCode(installed)) + Arrays.hashCode(file);
        }
    }
}
