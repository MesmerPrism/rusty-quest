package io.github.mesmerprism.rustyquest.spatial_camera_panel

internal data class SpatialAdapterRuntimeInput(
    val enabled: Boolean,
    val profileId: String,
    val projectId: String,
    val featureId: String,
    val lockRevision: Long,
    val lockSha256: String,
)

internal data class SpatialAdapterLockDecision(
    val applied: Boolean,
    val authorityReceiptSchema: String,
    val projectId: String,
    val featureId: String,
    val lockRevision: Long,
    val lockSha256: String,
    val runtimeProfileId: String,
    val rejectionReason: String,
) {
  fun markerFields(): String =
      "lockBindingSchema=rusty.quest.lock_bound_activation.v1 " +
          "lockAuthorityReceiptSchema=${activityMarkerToken(authorityReceiptSchema)} " +
          "activationState=${if (applied) "applied" else "rejected"} " +
          "projectId=${activityMarkerToken(projectId)} " +
          "featureId=${activityMarkerToken(featureId)} " +
          "conformanceLockRevision=$lockRevision " +
          "conformanceLockSha256=$lockSha256 " +
          "runtimeProfileId=${activityMarkerToken(runtimeProfileId)} " +
          "activationRejectReason=$rejectionReason"
}

internal class SpatialAdapterDecisionCache(
    private val resolver: (SpatialAdapterRuntimeInput) -> SpatialAdapterLockDecision
) {
  private var cachedInput: SpatialAdapterRuntimeInput? = null
  private var cachedDecision: SpatialAdapterLockDecision? = null

  @Synchronized
  fun decisionFor(input: SpatialAdapterRuntimeInput): SpatialAdapterLockDecision {
    val current = cachedDecision
    if (input == cachedInput && current != null) {
      return current
    }
    return resolver(input).also {
      cachedInput = input
      cachedDecision = it
    }
  }

  @Synchronized
  fun clear() {
    cachedInput = null
    cachedDecision = null
  }
}

internal object SpatialAdapterLockBinding {
  const val AUTHORITY_RECEIPT_SCHEMA =
      "rusty.quest.spatial_adapter_lock_authority_receipt.v1"

  fun parseAuthorityReceipt(
      receipt: String?,
      input: SpatialAdapterRuntimeInput,
  ): SpatialAdapterLockDecision {
    val fields = receipt?.split('\t') ?: emptyList()
    if (fields.size != 8 || fields[0] != AUTHORITY_RECEIPT_SCHEMA) {
      return rejected(input, "native-authority-receipt-invalid")
    }
    val state = fields[1]
    val revision = fields[4].toLongOrNull()
    val digest = fields[5]
    val reason = fields[7]
    if (state !in setOf("applied", "rejected") ||
        revision == null ||
        digest.length != 64 ||
        !digest.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ||
        reason.isBlank() ||
        (state == "applied") != (reason == "none")) {
      return rejected(input, "native-authority-receipt-invalid")
    }
    val applied = state == "applied" && reason == "none"
    if (applied &&
        (!input.enabled ||
            fields[2] != input.projectId ||
            fields[3] != input.featureId ||
            revision != input.lockRevision ||
            !digest.equals(input.lockSha256, ignoreCase = true) ||
            fields[6] != input.profileId)) {
      return rejected(input, "native-authority-receipt-input-mismatch")
    }
    return SpatialAdapterLockDecision(
        applied = applied,
        authorityReceiptSchema = fields[0],
        projectId = fields[2],
        featureId = fields[3],
        lockRevision = revision,
        lockSha256 = digest,
        runtimeProfileId = fields[6],
        rejectionReason = if (applied) "none" else reason,
    )
  }

  fun rejected(
      input: SpatialAdapterRuntimeInput,
      reason: String,
  ): SpatialAdapterLockDecision =
      SpatialAdapterLockDecision(
          applied = false,
          authorityReceiptSchema = AUTHORITY_RECEIPT_SCHEMA,
          projectId = input.projectId.ifBlank { "none" },
          featureId = input.featureId.ifBlank { "none" },
          lockRevision = input.lockRevision,
          lockSha256 = input.lockSha256.ifBlank { "none" },
          runtimeProfileId = input.profileId.ifBlank { "none" },
          rejectionReason = reason,
      )
}
