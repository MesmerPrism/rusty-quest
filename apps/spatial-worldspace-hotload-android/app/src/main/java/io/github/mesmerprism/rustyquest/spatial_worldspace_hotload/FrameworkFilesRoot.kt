package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import java.io.File

/** Resolve only the trusted framework app-files path at the SDK adapter boundary. */
internal class FrameworkFilesRoot(frameworkDirectory:File) {
    val directory:File=frameworkDirectory.toPath().toRealPath().toFile().canonicalFile
}
