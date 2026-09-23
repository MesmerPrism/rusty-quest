package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import android.content.Context

/** Activity lifecycle bridge; startup authorization remains with the process host. */
internal object EmbeddedDuplexActivityAttachment {
  fun attach(context: Context, display: EmbeddedDuplexDisplayCoordinator): Long =
      EmbeddedDuplexProcessHost.forApplication(context).attachDisplay(display)

  /** This is valid only before native bootstrap. A live host requires typed Stop first. */
  fun detachUninitialized(context: Context, generation: Long) {
    EmbeddedDuplexProcessHost.forApplication(context).detachUninitializedDisplay(generation)
  }
}
