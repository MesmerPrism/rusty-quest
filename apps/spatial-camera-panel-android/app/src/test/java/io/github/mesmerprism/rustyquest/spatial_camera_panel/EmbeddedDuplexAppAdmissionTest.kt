package io.github.mesmerprism.rustyquest.spatial_camera_panel

import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexAppAdmission as Admission
import java.util.concurrent.CompletableFuture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmbeddedDuplexAppAdmissionTest {
  private val nonce = "1".repeat(32)
  @Test fun bootstrapOriginalAndReadinessHaveClosedDistinctRoutes() {
    val original="2".repeat(32)
    val selected=EmbeddedDuplexOperatorContract.parseFields("bootstrap_real_peer",nonce,mapOf("original_id" to original))
    assertEquals("BOOTSTRAP_REAL_PEER",selected.route.name);assertEquals(original,selected.originalId)
    val read=EmbeddedDuplexOperatorContract.parseFields("bootstrap_admission_status",nonce,emptyMap())
    assertEquals("BOOTSTRAP_ADMISSION_STATUS",read.route.name);assertNull(read.originalId)
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexOperatorContract.parseFields("bootstrap_admission_status",nonce,mapOf("original_id" to original))
    }
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddedDuplexOperatorContract.parseFields("bootstrap_real_peer",nonce,mapOf("original_id" to original,"admitted" to true))
    }
  }
  @Test fun snapshotReportsActualRetainedFutureAndSeparateAttachmentReason() {
    val failed=CompletableFuture.failedFuture<Any>(IllegalStateException("retained"))
    val snapshot=Admission.activity(true,false,false,true,7,true,true,true,null,failed)
    assertEquals("already_attached",snapshot.attachmentReason)
    assertEquals("runtime_future_retained",snapshot.bootstrapReason)
    assertFalse(snapshot.bootstrapGateOpen)
    assertEquals("failed",JSONObject(snapshot.json()).getString("runtime_future"))
    assertTrue(Admission.activity(true,false,false,true,7,true,true,true,null,null).bootstrapGateOpen)
  }
  @Test fun missingCarrierFactsAndUnobservedRouteRemainTruthful() {
    assertEquals("carrier_fence_absent",Admission.activity(true,false,false,true,0,true,false,true,null,null).attachmentReason)
    assertEquals("carrier_surface_invalid",Admission.activity(true,false,false,true,0,true,true,false,null,null).attachmentReason)
    val disabled=Admission.activity(false,false,false,null,0,false,false,false,null,null)
    assertEquals("product_inputs_disabled",disabled.attachmentReason)
    assertTrue(JSONObject(disabled.json()).isNull("capture_route_selected"))
    assertTrue(JSONObject(Admission.dispatchRejected().json()).isNull("activity_present"))
    assertFalse(JSONObject(Admission.noActivity().json()).getBoolean("activity_present"))
  }
}
