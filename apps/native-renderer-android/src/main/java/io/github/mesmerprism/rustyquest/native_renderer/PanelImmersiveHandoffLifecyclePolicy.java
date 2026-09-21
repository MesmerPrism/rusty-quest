package io.github.mesmerprism.rustyquest.native_renderer;

/**
 * Pure application-lifetime ownership policy for {@link PanelImmersiveHandoff}.
 *
 * <p>Owner tokens are adapter identities, not Activity identities. Re-registration advances the
 * generation and immediately invalidates every callback from the predecessor. Terminal exit
 * remains latched across recreation and internal recovery, and clears ownership before any caller
 * can launch. Only an explicitly admitted cold-user-launch epoch can establish fresh ownership.</p>
 */
final class PanelImmersiveHandoffLifecyclePolicy {
    static final class Registration {
        final long generation;
        final long replacedOwnerToken;
        final boolean admitted;

        Registration(long generation, long replacedOwnerToken, boolean admitted) {
            this.generation = generation;
            this.replacedOwnerToken = replacedOwnerToken;
            this.admitted = admitted;
        }
    }

    private long generation;
    private long ownerToken;
    private long pendingExplicitLaunchOwnerToken;
    private boolean terminalExit;

    Registration register(long candidateOwnerToken) {
        requireOwnerToken(candidateOwnerToken);
        long replacedOwnerToken = ownerToken;
        generation += 1L;
        if (terminalExit) {
            ownerToken = 0L;
            pendingExplicitLaunchOwnerToken = candidateOwnerToken;
            return new Registration(generation, replacedOwnerToken, false);
        }
        ownerToken = candidateOwnerToken;
        pendingExplicitLaunchOwnerToken = 0L;
        return new Registration(generation, replacedOwnerToken, true);
    }

    Registration admitExplicitLaunchEpoch(long candidateOwnerToken) {
        requireOwnerToken(candidateOwnerToken);
        if (!terminalExit || pendingExplicitLaunchOwnerToken != candidateOwnerToken) {
            return new Registration(generation, ownerToken, false);
        }
        long replacedOwnerToken = ownerToken;
        generation += 1L;
        terminalExit = false;
        ownerToken = candidateOwnerToken;
        pendingExplicitLaunchOwnerToken = 0L;
        return new Registration(generation, replacedOwnerToken, true);
    }

    long beginRequest(long candidateOwnerToken) {
        if (terminalExit || ownerToken != candidateOwnerToken) {
            return -1L;
        }
        generation += 1L;
        return generation;
    }

    boolean canLaunch(long candidateOwnerToken, long expectedGeneration) {
        return !terminalExit
            && ownerToken == candidateOwnerToken
            && generation == expectedGeneration;
    }

    long beginTerminalExit() {
        terminalExit = true;
        ownerToken = 0L;
        pendingExplicitLaunchOwnerToken = 0L;
        generation += 1L;
        return generation;
    }

    boolean release(long candidateOwnerToken) {
        if (ownerToken == candidateOwnerToken) {
            ownerToken = 0L;
            return true;
        }
        if (pendingExplicitLaunchOwnerToken == candidateOwnerToken) {
            pendingExplicitLaunchOwnerToken = 0L;
            return true;
        }
        return false;
    }

    boolean hasOwner() {
        return ownerToken != 0L;
    }

    boolean isTerminalExit() {
        return terminalExit;
    }

    boolean hasPendingExplicitLaunch() {
        return pendingExplicitLaunchOwnerToken != 0L;
    }

    long generation() {
        return generation;
    }

    private static void requireOwnerToken(long ownerToken) {
        if (ownerToken <= 0L) {
            throw new IllegalArgumentException("owner token must be positive");
        }
    }
}
