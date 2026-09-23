package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class EmbeddedDuplexIdentityDomainTest {
    @Test public void genericOwnerSignerCannotSignCommonLanContext() {
        assertFalse(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(
                "rusty.manifold.peer.common_lan_reciprocal_ed25519_context.v1\0context")));
        assertTrue(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(
                "rusty.quest.android.media.owner_dispatch_envelope.v1\0request\0frame")));
        assertTrue(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes(
                "rusty.quest.android.media.owner_dispatch_envelope.v1\0terminal_response\0frame")));
        assertFalse(EmbeddedDuplexIdentity.hasSupportedAuthorityDomain(bytes("arbitrary\0frame")));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
}
