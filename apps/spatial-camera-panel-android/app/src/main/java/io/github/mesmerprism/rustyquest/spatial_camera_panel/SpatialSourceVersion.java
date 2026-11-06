package io.github.mesmerprism.rustyquest.spatial_camera_panel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/** Context-free development metadata. This is not APK or runtime admission. */
public final class SpatialSourceVersion {
    private static final String RESOURCE = "META-INF/rusty-quest/source-version.v1.txt";

    private SpatialSourceVersion() {}

    public static String read(ClassLoader loader) {
        if (loader == null) throw new IllegalStateException("source-version-loader");
        try {
            Enumeration<URL> resources = loader.getResources(RESOURCE);
            if (!resources.hasMoreElements()) throw new IllegalStateException("source-version-missing");
            URL resource = resources.nextElement();
            if (resources.hasMoreElements()) throw new IllegalStateException("source-version-ambiguous");
            byte[] bytes = new byte[76];
            int length = 0;
            try (InputStream stream = resource.openStream()) {
                while (length < bytes.length) {
                    int count = stream.read(bytes, length, bytes.length - length);
                    if (count < 0) break;
                    if (count == 0) throw new IllegalStateException("source-version-read");
                    length += count;
                }
            }
            String text = new String(bytes, 0, length, StandardCharsets.US_ASCII);
            if (length != 75 || !text.matches("0\\.1\\.0\\+src\\.[a-f0-9]{64}\\n")) {
                throw new IllegalStateException("source-version-malformed");
            }
            return text.substring(0, 74);
        } catch (IOException error) {
            throw new IllegalStateException("source-version-read", error);
        }
    }
}
