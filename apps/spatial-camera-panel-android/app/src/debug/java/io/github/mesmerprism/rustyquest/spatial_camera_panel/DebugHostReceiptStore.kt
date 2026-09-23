package io.github.mesmerprism.rustyquest.spatial_camera_panel

import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A closed, bounded app-private receipt store. It never persists a raw host nonce. */
internal class DebugHostReceiptStore(
    private val root: File,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val maximumReceiptBytes: Int = DebugHostReceiptContract.MAX_RECEIPT_BYTES,
) {
  data class Identity(
      val applicationId: String,
      val apkSha256: String,
      val versionCode: Long,
      val versionName: String,
      val variant: String,
      val pid: Int,
      val epoch: String,
  )

  data class Fact(val type: String, val value: String)

  data class Status(
      val value: String,
      val receiptHash: String? = null,
      val expiresAtMs: Long? = null,
  )

  private enum class StateKind { PENDING, CONSUMED }

  private data class State(
      val kind: StateKind,
      val nonceHash: String,
      val epoch: String,
      val expiresAtMs: Long,
  )

  private val monitor = Any()
  private val stateFile = File(root, "state.v1")
  private val receiptFile = File(root, "receipt.v1.json")

  fun arm(nonce: String, epoch: String): Long = synchronized(monitor) {
    val normalizedNonce = DebugHostReceiptContract.requireNonce(nonce)
    val normalizedEpoch = DebugHostReceiptContract.requireEpoch(epoch)
    ensureRoot()
    require(!receiptFile.exists()) { "debug_host_receipt_cleanup_required" }
    val current = readStateOrNull()
    if (current != null) {
      if (current.expiresAtMs > nowMs()) {
        throw IllegalStateException("debug_host_receipt_nonce_already_armed")
      }
      require(stateFile.delete()) { "debug_host_receipt_stale_state_unremovable" }
    }
    val expiry = nowMs() + DebugHostReceiptContract.NONCE_TTL_MS
    writeState(
        State(
            kind = StateKind.PENDING,
            nonceHash = DebugHostReceiptContract.sha256(normalizedNonce.toByteArray(StandardCharsets.UTF_8)),
            epoch = normalizedEpoch,
            expiresAtMs = expiry,
        ),
    )
    expiry
  }

  fun status(): Status = synchronized(monitor) {
    ensureRoot()
    if (receiptFile.exists()) {
      val receipt = readVerifiedReceipt()
      return Status(value = "terminal", receiptHash = receipt.receiptHash)
    }
    val state = readStateOrNull() ?: return Status(value = "idle")
    if (state.expiresAtMs <= nowMs()) return Status(value = "stale", expiresAtMs = state.expiresAtMs)
    return when (state.kind) {
      StateKind.PENDING -> Status(value = "armed", expiresAtMs = state.expiresAtMs)
      StateKind.CONSUMED -> Status(value = "terminal-unavailable", expiresAtMs = state.expiresAtMs)
    }
  }

  fun finalizeReceipt(nonce: String, identity: Identity, facts: List<Fact>): String = synchronized(monitor) {
    val normalizedNonce = DebugHostReceiptContract.requireNonce(nonce)
    validateIdentity(identity)
    validateFacts(facts)
    ensureRoot()
    require(!receiptFile.exists()) { "debug_host_receipt_replay_rejected" }
    val state = readStateOrNull() ?: throw IllegalStateException("debug_host_receipt_not_armed")
    if (state.expiresAtMs <= nowMs()) {
      require(stateFile.delete()) { "debug_host_receipt_stale_state_unremovable" }
      throw IllegalStateException("debug_host_receipt_nonce_stale")
    }
    require(state.kind == StateKind.PENDING) { "debug_host_receipt_replay_rejected" }
    require(state.epoch == identity.epoch) { "debug_host_receipt_epoch_mismatch" }
    require(state.nonceHash == DebugHostReceiptContract.sha256(normalizedNonce.toByteArray(StandardCharsets.UTF_8))) {
      "debug_host_receipt_nonce_mismatch"
    }

    // Persisting this consumed transition first makes any crash fail closed rather than replayable.
    writeState(state.copy(kind = StateKind.CONSUMED))
    val receipt = buildReceipt(state.nonceHash, identity, facts)
    val bytes = receipt.json.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= maximumReceiptBytes) { "debug_host_receipt_size_exceeded" }
    writeAtomically(receiptFile, bytes)
    require(stateFile.delete()) { "debug_host_receipt_consumed_state_unremovable" }
    receipt.receiptHash
  }

  fun read(receiptHash: String): String = synchronized(monitor) {
    val expected = DebugHostReceiptContract.requireReceiptHash(receiptHash)
    val receipt = readVerifiedReceipt()
    require(receipt.receiptHash == expected) { "debug_host_receipt_hash_mismatch" }
    receipt.json
  }

  fun cleanup(receiptHash: String) = synchronized(monitor) {
    val expected = DebugHostReceiptContract.requireReceiptHash(receiptHash)
    val receipt = readVerifiedReceipt()
    require(receipt.receiptHash == expected) { "debug_host_receipt_cleanup_binding_rejected" }
    require(receiptFile.delete()) { "debug_host_receipt_cleanup_failed" }
  }

  private fun ensureRoot() {
    require(root.exists() || root.mkdirs()) { "debug_host_receipt_store_unavailable" }
    require(root.isDirectory) { "debug_host_receipt_store_not_directory" }
  }

  private fun readStateOrNull(): State? {
    if (!stateFile.exists()) return null
    val fields = stateFile.readText(StandardCharsets.UTF_8).split('|')
    require(fields.size == 5 && fields[0] == "rusty.quest.debug_host_receipt_state.v1") {
      "debug_host_receipt_state_damaged"
    }
    val kind =
        when (fields[1]) {
          "pending" -> StateKind.PENDING
          "consumed" -> StateKind.CONSUMED
          else -> throw IllegalStateException("debug_host_receipt_state_damaged")
        }
    DebugHostReceiptContract.requireReceiptHash(fields[2])
    DebugHostReceiptContract.requireEpoch(fields[3])
    val expiresAt = fields[4].toLongOrNull() ?: throw IllegalStateException("debug_host_receipt_state_damaged")
    require(expiresAt > 0) { "debug_host_receipt_state_damaged" }
    return State(kind, fields[2], fields[3], expiresAt)
  }

  private fun writeState(state: State) {
    val kind = if (state.kind == StateKind.PENDING) "pending" else "consumed"
    val payload =
        "rusty.quest.debug_host_receipt_state.v1|$kind|${state.nonceHash}|${state.epoch}|${state.expiresAtMs}"
    writeAtomically(stateFile, payload.toByteArray(StandardCharsets.UTF_8))
  }

  private fun validateIdentity(identity: Identity) {
    DebugHostReceiptContract.requireToken(identity.applicationId, "application_id")
    DebugHostReceiptContract.requireReceiptHash(identity.apkSha256)
    require(identity.versionCode > 0) { "debug_host_receipt_version_code_rejected" }
    DebugHostReceiptContract.requireToken(identity.versionName, "version_name")
    require(identity.variant == "debug") { "debug_host_receipt_variant_rejected" }
    require(identity.pid > 0) { "debug_host_receipt_pid_rejected" }
    DebugHostReceiptContract.requireEpoch(identity.epoch)
  }

  private fun validateFacts(facts: List<Fact>) {
    require(facts.size == DebugHostReceiptContract.FACT_TYPES.size) {
      "debug_host_receipt_fact_count_rejected"
    }
    facts.forEachIndexed { index, fact ->
      require(fact.type == DebugHostReceiptContract.FACT_TYPES[index]) {
        "debug_host_receipt_fact_order_rejected"
      }
      DebugHostReceiptContract.requireToken(fact.value, "fact_value")
    }
  }

  private data class VerifiedReceipt(val json: String, val receiptHash: String)

  private fun buildReceipt(nonceHash: String, identity: Identity, facts: List<Fact>): VerifiedReceipt {
    var previousHash = rootCommitment(nonceHash, identity)
    val factJson =
        facts.mapIndexed { index, fact ->
          val sequence = index + 1
          val factHash =
              DebugHostReceiptContract.sha256(
                  "${DebugHostReceiptContract.SCHEMA}|$sequence|${fact.type}|${fact.value}|$previousHash"
                      .toByteArray(StandardCharsets.UTF_8),
              )
          val encoded =
              "{\"sequence\":$sequence,\"fact\":\"${fact.type}\",\"value\":\"${fact.value}\",\"previous_hash\":\"$previousHash\",\"hash\":\"$factHash\"}"
          previousHash = factHash
          encoded
        }
    val json =
        "{\"schema\":\"${DebugHostReceiptContract.SCHEMA}\",\"version\":1,\"nonce_hash\":\"$nonceHash\",\"application_id\":\"${identity.applicationId}\",\"apk_sha256\":\"${identity.apkSha256}\",\"version_code\":${identity.versionCode},\"version_name\":\"${identity.versionName}\",\"variant\":\"${identity.variant}\",\"pid\":${identity.pid},\"epoch\":\"${identity.epoch}\",\"facts\":[${factJson.joinToString(",")}],\"receipt_hash\":\"$previousHash\"}"
    return VerifiedReceipt(json, previousHash)
  }

  private fun readVerifiedReceipt(): VerifiedReceipt {
    require(receiptFile.exists()) { "debug_host_receipt_missing" }
    val bytes = receiptFile.readBytes()
    require(bytes.size <= maximumReceiptBytes) { "debug_host_receipt_size_exceeded" }
    val json = bytes.toString(StandardCharsets.UTF_8)
    val rootObject =
        try {
          JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
          throw IllegalStateException("debug_host_receipt_damaged")
        }
    val expectedRootKeys =
        setOf(
            "schema",
            "version",
            "nonce_hash",
            "application_id",
            "apk_sha256",
            "version_code",
            "version_name",
            "variant",
            "pid",
            "epoch",
            "facts",
            "receipt_hash",
        )
    require(rootObject.keySet() == expectedRootKeys) { "debug_host_receipt_privacy_rejected" }
    require(rootObject["schema"].asString == DebugHostReceiptContract.SCHEMA) {
      "debug_host_receipt_schema_rejected"
    }
    require(rootObject["version"].asInt == 1) { "debug_host_receipt_version_rejected" }
    val identity =
        Identity(
            applicationId = rootObject["application_id"].asString,
            apkSha256 = rootObject["apk_sha256"].asString,
            versionCode = rootObject["version_code"].asLong,
            versionName = rootObject["version_name"].asString,
            variant = rootObject["variant"].asString,
            pid = rootObject["pid"].asInt,
            epoch = rootObject["epoch"].asString,
        )
    validateIdentity(identity)
    val nonceHash = DebugHostReceiptContract.requireReceiptHash(rootObject["nonce_hash"].asString)
    val factArray = rootObject["facts"].asJsonArray
    require(factArray.size() == DebugHostReceiptContract.FACT_TYPES.size) {
      "debug_host_receipt_fact_count_rejected"
    }
    var previousHash = rootCommitment(nonceHash, identity)
    factArray.forEachIndexed { index, element ->
      val fact = element.asJsonObject
      require(fact.keySet() == setOf("sequence", "fact", "value", "previous_hash", "hash")) {
        "debug_host_receipt_privacy_rejected"
      }
      val sequence = fact["sequence"].asInt
      val type = fact["fact"].asString
      val value = fact["value"].asString
      val actualPreviousHash = DebugHostReceiptContract.requireReceiptHash(fact["previous_hash"].asString)
      val actualHash = DebugHostReceiptContract.requireReceiptHash(fact["hash"].asString)
      require(sequence == index + 1 && type == DebugHostReceiptContract.FACT_TYPES[index]) {
        "debug_host_receipt_fact_order_rejected"
      }
      DebugHostReceiptContract.requireToken(value, "fact_value")
      require(actualPreviousHash == previousHash) { "debug_host_receipt_chain_rejected" }
      val expectedHash =
          DebugHostReceiptContract.sha256(
              "${DebugHostReceiptContract.SCHEMA}|$sequence|$type|$value|$previousHash"
                  .toByteArray(StandardCharsets.UTF_8),
          )
      require(actualHash == expectedHash) { "debug_host_receipt_chain_rejected" }
      previousHash = actualHash
    }
    require(rootObject["receipt_hash"].asString == previousHash) { "debug_host_receipt_chain_rejected" }
    return VerifiedReceipt(json, previousHash)
  }

  private fun rootCommitment(nonceHash: String, identity: Identity): String =
      DebugHostReceiptContract.sha256(
          listOf(
                  DebugHostReceiptContract.SCHEMA,
                  "receipt-root-v1",
                  nonceHash,
                  identity.applicationId,
                  identity.apkSha256,
                  identity.versionCode.toString(),
                  identity.versionName,
                  identity.variant,
                  identity.pid.toString(),
                  identity.epoch,
              )
              .joinToString("|")
              .toByteArray(StandardCharsets.UTF_8),
      )

  private fun writeAtomically(target: File, bytes: ByteArray) {
    val temporary = File(target.parentFile, ".${target.name}.${DebugHostReceiptContract.freshEpoch()}.tmp")
    try {
      FileOutputStream(temporary).use { stream ->
        stream.write(bytes)
        stream.fd.sync()
      }
      Files.move(
          temporary.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
      )
    } finally {
      if (temporary.exists()) temporary.delete()
    }
  }
}
