package io.github.mesmerprism.rustyquest.displaystream;

/** Process-retained slot; an uncertain worker is never replaced by a new Service. */
final class DisplayStreamAttempts {
    private DisplayStreamAttempt current;
    synchronized boolean available() { return current == null || current.snapshot().retired(); }
    synchronized void install(DisplayStreamAttempt next) {
        if (!available()) throw new IllegalStateException("prior display retirement unresolved");
        current = next;
    }
    synchronized boolean isCurrent(DisplayStreamAttempt expected) { return current == expected; }
}
