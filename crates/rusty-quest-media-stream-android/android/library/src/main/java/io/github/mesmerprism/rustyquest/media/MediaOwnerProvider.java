package io.github.mesmerprism.rustyquest.media;

/** Product-supplied provider for one exact packaged owner binding. */
public interface MediaOwnerProvider {
    MediaProviderReadback execute(MediaOwnerAction action, CancellationHandle cancellation)
            throws Exception;
    MediaProviderReadback compensate(MediaOwnerAction action, CancellationHandle cancellation)
            throws Exception;
    MediaRuntimeSnapshot snapshot();
}
