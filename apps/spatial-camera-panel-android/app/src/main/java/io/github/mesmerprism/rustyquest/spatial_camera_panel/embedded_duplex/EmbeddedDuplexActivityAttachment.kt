package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import android.content.Context
import java.util.concurrent.CompletableFuture

/** Activity lifecycle bridge; startup authorization remains with the process host. */
internal object EmbeddedDuplexActivityAttachment {
  fun selectLocalAfterTerminal(context: Context,
      fence: io.github.mesmerprism.rustyquest.spatial_camera_panel.LocalRollbackRequestFence,
      ticket: io.github.mesmerprism.rustyquest.spatial_camera_panel.LocalRollbackRequestFence.Ticket,
      currentRouteGeneration: java.util.function.LongSupplier,
      ownerAlive: java.util.function.BooleanSupplier): CompletableFuture<String> =
      EmbeddedDuplexProcessHost.forApplication(context).selectLocalAfterTerminal(
          fence,ticket,currentRouteGeneration,ownerAlive)

  fun attach(context: Context, display: EmbeddedDuplexDisplayCoordinator): Long =
      EmbeddedDuplexProcessHost.forApplication(context).attachDisplay(display)

  /** This is valid only before native bootstrap. A live host requires typed Stop first. */
  fun detachUninitialized(context: Context, generation: Long) {
    EmbeddedDuplexProcessHost.forApplication(context).detachUninitializedDisplay(generation)
    // Activity-owned capture stop is independent from the already-cleaned Peer subscription.
    OwnStereoCaptureRuntime.currentForApplication()?.requestStopOwn()
  }

  fun diagnoseLocalFixture(context: Context, generation: Long): CompletableFuture<String> =
      EmbeddedDuplexProcessHost.forApplication(context).diagnoseLocalFixture(generation)

  fun retryLocalDiagnosticCleanup(context: Context, generation: Long): CompletableFuture<String> =
      EmbeddedDuplexProcessHost.forApplication(context).retryLocalDiagnosticCleanup(generation)
}
