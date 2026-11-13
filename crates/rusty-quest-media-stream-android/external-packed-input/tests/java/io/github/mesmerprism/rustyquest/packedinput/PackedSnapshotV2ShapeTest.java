// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustyquest.packedinput;

public final class PackedSnapshotV2ShapeTest {
    public static void main(String[] args) {
        long[] configured=PackedSnapshotV2Shape.configured(PackedInputLeaseQueueTest.descriptor(7));
        if(configured.length!=5||configured[0]!=2560||configured[1]!=1280||configured[2]!=1||configured[3]!=768||configured[4]!=6)throw new AssertionError("v2 five-long descriptor changed");
        PackedSnapshotV2Shape.requireExpected(7,7);
        for(long stale:new long[]{0,-1,8})PackedInputLeaseQueueTest.rejects(()->PackedSnapshotV2Shape.requireExpected(stale,7));
        if(PackedSnapshotV2Shape.VERSION!=2||!PackedSnapshotV2Shape.DESCRIPTOR.equals("io.github.mesmerprism.rustyquest.NativeMetaInputPublisher.v2"))throw new AssertionError("app-owned wire identity changed");
        PackedInputLeaseQueueTest.Fixture f=new PackedInputLeaseQueueTest.Fixture();f.offer(100);
        PackedInputLeaseQueue<String>.Copy copy=f.queue.takeLatest(7).beginCopy(()->true);copy.borrowBuffer();
        if(!copy.pollRetired()||PackedSnapshotV2Shape.PROVIDES_FINAL_RENDER_STATUS)throw new AssertionError("copy completion manufactured final renderer status");
        System.out.println("PASS fixed v2 descriptor shape, exact generation and no wildcard admission");
    }
}
