package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialVideoLifecycleShutdownCoordinatorTest {
  @Test fun blockedWorkerDoesNotBlockUiDestroyAndCleanupRemainsOrdered() {
    val executor = Executors.newSingleThreadExecutor()
    val coordinator = SpatialVideoLifecycleShutdownCoordinator(executor)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val cleaned = CountDownLatch(1)
    val events = Collections.synchronizedList(ArrayList<String>())
    assertTrue(coordinator.dispatch {
      events += "accepted-work-start"
      entered.countDown()
      release.await(2, TimeUnit.SECONDS)
      events += "accepted-work-end"
    })
    assertTrue(entered.await(1, TimeUnit.SECONDS))

    val callbackMs = measureTimeMillis {
      assertTrue(
          coordinator.beginOrderedShutdown(
              retireProjectionPeer = {
                events += "peer-retire"
                false
              },
              finishCleanup = { retired ->
                events += "cleanup:$retired"
                cleaned.countDown()
              },
          )
      )
    }

    assertTrue("destroy callback blocked for ${callbackMs}ms", callbackMs < 250L)
    assertTrue(coordinator.isFenced())
    assertFalse(coordinator.dispatch { events += "late-work" })
    assertFalse(cleaned.await(50, TimeUnit.MILLISECONDS))
    release.countDown()
    assertTrue(cleaned.await(1, TimeUnit.SECONDS))
    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
    assertEquals(
        listOf("accepted-work-start", "accepted-work-end", "peer-retire", "cleanup:false"),
        events,
    )
  }
}
