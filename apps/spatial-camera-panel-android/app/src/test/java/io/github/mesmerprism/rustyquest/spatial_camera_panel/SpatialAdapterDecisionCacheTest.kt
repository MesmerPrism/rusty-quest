package io.github.mesmerprism.rustyquest.spatial_camera_panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialAdapterDecisionCacheTest {
  private fun receipt(
      input: SpatialAdapterRuntimeInput,
      applied: Boolean = true,
      reason: String = if (applied) "none" else "runtime-revision-mismatch",
  ): String =
      listOf(
              SpatialAdapterLockBinding.AUTHORITY_RECEIPT_SCHEMA,
              if (applied) "applied" else "rejected",
              input.projectId,
              input.featureId,
              input.lockRevision.toString(),
              input.lockSha256,
              input.profileId,
              reason,
          )
          .joinToString("\t")

  @Test
  fun changedRuntimeInputCannotReuseAnEarlierAppliedDecision() {
    var resolverCalls = 0
    val cache =
        SpatialAdapterDecisionCache { input ->
          resolverCalls += 1
          SpatialAdapterLockBinding.parseAuthorityReceipt(
              receipt =
                  receipt(
                      input,
                      applied = input.lockRevision == 7L,
                      reason =
                          if (input.lockRevision == 7L) "none"
                          else "runtime-revision-mismatch",
                  ),
              input = input,
          )
        }
    val accepted =
        SpatialAdapterRuntimeInput(
            enabled = true,
            profileId = "profile.accepted",
            projectId = "project.accepted",
            featureId = "feature.accepted",
            lockRevision = 7L,
            lockSha256 = "A".repeat(64),
        )

    assertTrue(cache.decisionFor(accepted).applied)
    assertTrue(cache.decisionFor(accepted).applied)
    assertEquals(1, resolverCalls, "an unchanged input may reuse its decision")

    val stale = accepted.copy(lockRevision = 6L)
    val rejected = cache.decisionFor(stale)
    assertFalse(rejected.applied)
    assertEquals("runtime-revision-mismatch", rejected.rejectionReason)
    assertEquals(2, resolverCalls, "an input-key change must force re-resolution")

    assertFalse(cache.decisionFor(stale).applied)
    assertEquals(2, resolverCalls, "the rejected decision is cached only for its exact input")
    assertTrue(cache.decisionFor(accepted).applied)
    assertEquals(3, resolverCalls, "returning to the accepted input must resolve again")
  }

  @Test
  fun nativeAuthorityReceiptIsTheOnlyAppliedDecisionSource() {
    val input =
        SpatialAdapterRuntimeInput(
            enabled = true,
            profileId = "profile.accepted",
            projectId = "project.accepted",
            featureId = "feature.accepted",
            lockRevision = 7L,
            lockSha256 = "A".repeat(64),
        )
    assertTrue(SpatialAdapterLockBinding.parseAuthorityReceipt(receipt(input), input).applied)

    val malformed = SpatialAdapterLockBinding.parseAuthorityReceipt("applied", input)
    assertFalse(malformed.applied)
    assertEquals("native-authority-receipt-invalid", malformed.rejectionReason)

    val mismatchedReceipt =
        SpatialAdapterLockBinding.parseAuthorityReceipt(
            receipt(input.copy(lockRevision = input.lockRevision + 1L)),
            input,
        )
    assertFalse(mismatchedReceipt.applied)
    assertEquals(
        "native-authority-receipt-input-mismatch",
        mismatchedReceipt.rejectionReason,
    )

    val disabledInput = input.copy(enabled = false)
    val disabledAppliedReceipt =
        SpatialAdapterLockBinding.parseAuthorityReceipt(receipt(disabledInput), disabledInput)
    assertFalse(disabledAppliedReceipt.applied)
    assertEquals(
        "native-authority-receipt-input-mismatch",
        disabledAppliedReceipt.rejectionReason,
    )

    val rejected =
        SpatialAdapterLockBinding.parseAuthorityReceipt(
            receipt(input, applied = false, reason = "runtime-digest-mismatch"),
            input,
        )
    assertFalse(rejected.applied)
    assertEquals("runtime-digest-mismatch", rejected.rejectionReason)
  }
}
