package io.github.mesmerprism.rustyquest.media;

/** Process-internal trusted executor installed into the host authority library. */
public interface AndroidMediaOwnerRegistry {
    String execute(String ticketJson, boolean compensate);
    boolean verify(String ticketJson, String readbackJson);

    /** Consumes one issued readback and returns its checked provider snapshot, or null. */
    default String verifyAndReadEvidence(String ticketJson, String readbackJson) { return null; }
}
