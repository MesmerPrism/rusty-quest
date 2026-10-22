package org.rusty.quest.feedback;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/** Desktop conformance adapter only; intentionally retained, with no unloading assertion. */
public final class HostUrlLoader implements GenerationLoader {
    @Override public ClassLoader create(File artifact,ClassLoader contractParent) throws Exception {
        return new URLClassLoader(new URL[]{artifact.toURI().toURL()},contractParent);
    }
}
