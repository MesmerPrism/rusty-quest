package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public final class EmbeddedDuplexIdentityDomainTest {
    @Test public void retainedCleanupSignerKeepsClosedPrepareVersions() {
        for (String domain : new String[] {
                "rusty.quest.android.media.retained_cleanup_prepare.v1",
                "rusty.quest.android.media.retained_abort_prepare.v2",
                "rusty.quest.android.media.retained_cleanup_prepare.v3",
                "rusty.quest.android.media.retained_abort_prepare.v4" }) {
            assertTrue(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(domain + "\0{}")));
            assertFalse(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(domain + "\0")));
            assertFalse(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(domain + ".extra\0{}")));
        }
        assertFalse(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(
                "rusty.quest.android.media.retained_cleanup_prepare.v5\0{}")));
        assertFalse(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(
                "rusty.quest.android.media.retained_abort_prepare.v3\0{}")));
    }

    @Test public void genericOwnerSignerCannotSignCommonLanContext() {
        assertFalse(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(
                "rusty.manifold.peer.common_lan_reciprocal_ed25519_context.v1\0context")));
        assertTrue(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(
                "rusty.quest.android.media.owner_dispatch_envelope.v1\0request\0frame")));
        assertTrue(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(
                "rusty.quest.android.media.owner_dispatch_envelope.v1\0terminal_response\0frame")));
        assertFalse(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes("arbitrary\0frame")));
    }

    @Test public void durableSeedRecordHasVersionAndClosedKeyLengths() throws Exception {
        byte[] seed = new byte[32];
        byte[] publicKey = new byte[32];
        Arrays.fill(seed, (byte) 0x51);
        Arrays.fill(publicKey, (byte) 0x72);
        byte[] record = EmbeddedDuplexIdentity.encodeSeedRecord(seed, publicKey);
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(record));
        assertEquals(0x45444931, input.readInt());
        assertEquals(2, input.readInt());
        assertEquals(32, input.readInt());
        assertEquals(44, input.readInt());
        byte[] storedSeed = new byte[32];
        input.readFully(storedSeed);
        assertArrayEquals(seed, storedSeed);
        byte[] prefix = new byte[] {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] storedPrefix = new byte[prefix.length];
        input.readFully(storedPrefix);
        assertArrayEquals(prefix, storedPrefix);
        byte[] storedPublic = new byte[32];
        input.readFully(storedPublic);
        assertArrayEquals(publicKey, storedPublic);
        assertEquals(-1, input.read());
    }

    @Test public void canonicalLegacyPrivateKeyCanKeepItsIdentityWithoutJca() {
        byte[] encoded = new byte[] {
                0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
                0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
        };
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x37);
        byte[] canonical = new byte[encoded.length + seed.length];
        System.arraycopy(encoded, 0, canonical, 0, encoded.length);
        System.arraycopy(seed, 0, canonical, encoded.length, seed.length);
        assertArrayEquals(seed, EmbeddedDuplexIdentity.strictLegacySeed(canonical));
        canonical[0] = 0x31;
        assertTrue(EmbeddedDuplexIdentity.strictLegacySeed(canonical) == null);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}
