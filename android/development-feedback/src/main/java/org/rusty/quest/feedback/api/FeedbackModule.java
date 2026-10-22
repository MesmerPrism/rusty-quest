package org.rusty.quest.feedback.api;

/** Parent-owned CPU contract. Implementations must not retain scratch or start asynchronous work. */
public interface FeedbackModule {
    int abiVersion();
    String generationId();
    long compute(long input, long counter, byte[] scratch);
}
