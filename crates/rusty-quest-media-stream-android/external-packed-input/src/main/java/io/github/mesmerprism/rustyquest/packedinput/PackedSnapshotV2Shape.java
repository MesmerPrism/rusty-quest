// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.packedinput;
import io.github.mesmerprism.questcompositor.capturebridge.StereoSinkDescriptor;

/** Existing app-owned v2 snapshot wire shape; not the neutral nineteen-word wire ABI. */
public final class PackedSnapshotV2Shape {
    private PackedSnapshotV2Shape() { }
    public static final int VERSION=2;
    public static final boolean PROVIDES_FINAL_RENDER_STATUS=false;
    public static final String DESCRIPTOR="io.github.mesmerprism.rustyquest.NativeMetaInputPublisher.v2";
    public static void requireExpected(long expected,long actual) { if(expected<=0||expected!=actual)throw new IllegalStateException("exact positive reader generation required"); }
    public static long[] configured(StereoSinkDescriptor descriptor) {
        if(descriptor==null||descriptor.width!=2560||descriptor.height!=1280||descriptor.imageFormat!=1||descriptor.usage!=768)
            throw new IllegalArgumentException("initial fixed v2 packed reader required");
        return new long[]{descriptor.width,descriptor.height,descriptor.imageFormat,descriptor.usage,descriptor.maxImages};
    }
}
