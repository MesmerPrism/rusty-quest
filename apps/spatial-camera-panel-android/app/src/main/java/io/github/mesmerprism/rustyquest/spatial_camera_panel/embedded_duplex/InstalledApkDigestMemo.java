package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.InputStream;
import java.security.MessageDigest;

/** One non-persisted entry. Identity/readability is checked even on a digest hit. */
final class InstalledApkDigestMemo {
    interface Source { Selection open() throws Exception; }
    interface Selection extends AutoCloseable {
        Object identity();
        InputStream input();
        void requireCurrent() throws Exception;
        @Override void close() throws java.io.IOException;
    }
    private Object identity;
    private String digest;

    synchronized String read(Source source) throws Exception {
        try (Selection selected = source.open()) {
            selected.requireCurrent();
            Object observed = selected.identity();
            if (observed == null) throw new IllegalStateException("installed APK identity unavailable");
            if (observed.equals(identity) && digest != null) {
                selected.requireCurrent();
                return digest;
            }
            identity = null;
            digest = null;
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[65536];
            int count;
            while ((count = selected.input().read(block)) != -1) hash.update(block, 0, count);
            selected.requireCurrent();
            StringBuilder value = new StringBuilder(64);
            for (byte item : hash.digest()) {
                value.append(Character.forDigit((item >>> 4) & 15, 16));
                value.append(Character.forDigit(item & 15, 16));
            }
            identity = observed;
            digest = value.toString();
            return digest;
        } catch (Exception failure) {
            identity = null;
            digest = null;
            throw failure;
        }
    }
}
