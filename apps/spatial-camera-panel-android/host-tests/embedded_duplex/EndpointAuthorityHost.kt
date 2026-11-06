package io.github.mesmerprism.rustyquest.spatial_camera_panel

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Real native assembly/admission fixture. No Android or authenticated enrollment store. */
internal object EndpointAuthorityHost {
  @JvmStatic
  fun main(args: Array<String>) {
    val assets = Path.of(args[0])
    val binary = args[1]
    val out = Path.of(args[2])
    val base = Files.list(assets).use { stream -> stream.iterator().asSequence().associate {
      it.fileName.toString() to String(Files.readAllBytes(it), Charsets.UTF_8)
    } }
    val route = JSONObject(base.getValue("route-configuration.json"))
    val peers = route.getJSONArray("peers")
    val active = out.resolve("control-profiles/active.profile.json")
    Files.createDirectories(active.parent)
    val runtime = JSONObject().put("adapter_id", "adapter.endpoint.host")
        .put("admission_authority_id", "authority.endpoint.host").put("grant_id", "grant.endpoint.host")
        .put("grant_expires_at_ms", 2592001000L).put("lease_expires_at_ms", 2592001000L)
        .put("setup_credential_horizon_ms", 2592000000L).put("max_token_ttl_ms", 30000)
        .put("validation_wall_unix_ms", 1000).put("validation_monotonic_elapsed_ns", 1000000)
        .put("validation_epoch_entropy_hex", "04".repeat(32))
        .put("embedded_duplex", JSONObject()
            .put("\$schema", "rusty.quest.embedded_duplex.development_authority_config.v1")
            .put("runtime_host_id", "host." + peers.getJSONObject(0).getString("peer_id"))
            .put("trusted_operator_ids", JSONArray(listOf("operator.endpoint.host")))
            .put("trusted_key_fingerprints", JSONArray(listOf("fingerprint.endpoint.host")))
            .put("trusted_adapter_ids", JSONArray(listOf("adapter.endpoint.host")))
            .put("trusted_media_revoker_ids", JSONArray(listOf("revoker.endpoint.host"))))
    fun native(request: JSONObject): JSONObject {
      val p = ProcessBuilder(binary).redirectError(out.resolve("native-runtime.stderr").toFile()).start()
      p.outputStream.use { it.write(request.toString().toByteArray()) }
      val raw = p.inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
      check(p.waitFor() == 0) { "native probe process failed" }
      val response = JSONObject(raw)
      require(response.getBoolean("accepted")) { response.optString("error") }
      return response.getJSONObject("readback")
    }
    var corruptAuthority = false
    val reader = object : EndpointRouteCandidate.NativeReader {
      override fun canonicalBinding(json: String) = native(JSONObject().put("action", "canonicalize_binding")
          .put("binding", json)).getString("binding")
      override fun validate(documents: Map<String, String>, profile: SpatialPeerNetworkProfileSelection, profileJson: String): String {
        val derived = documents.filterKeys { it != "product-input-manifest.json" }.toMutableMap()
        if (corruptAuthority) {
          val lock = JSONObject(derived.getValue("client-lock.json")).put("feature_lock_id", "foreign.lock")
          derived["client-lock.json"] = lock.toString()
        }
        // The synthetic manifest from the earlier route fixture is never sent as authority.
        return native(JSONObject().put("base", JSONObject(base)).put("effective", JSONObject(derived))
            .put("profile", profileJson).put("runtime", runtime)).toString()
      }
    }
    val candidate = EndpointRouteCandidate(base, reader)
    val fixture = SpatialCameraControlProfileTest::class.java.getDeclaredMethod("validProfile").also { it.isAccessible = true }
    fun publish(host: String, port: Int, revision: Int) {
      val profile = fixture.invoke(SpatialCameraControlProfileTest()) as JSONObject
      val selected = JSONArray()
      for (i in 0..1) {
        val old = peers.getJSONObject(i)
        selected.put(JSONObject().put("peer_id", old.getString("peer_id"))
            .put("media_endpoint", JSONObject(old.getJSONObject("media_endpoint").toString()))
            .put("control_endpoint", JSONObject(old.getJSONObject("control_endpoint").toString())))
      }
      selected.getJSONObject(0).getJSONObject("media_endpoint").put("host", host).put("port", port)
      selected.getJSONObject(0).getJSONObject("control_endpoint").put("host", host).put("port", port + 1)
      profile.put("revision", revision).put("peer_network_endpoints", selected)
      val temporary = Files.createTempFile(active.parent, ".profile-", ".pending")
      Files.write(temporary, profile.toString().toByteArray())
      Files.move(temporary, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    fun refusal(action: () -> Unit): String {
      try { action() } catch (error: Exception) { return error.message.orEmpty() }
      error("expected refusal")
    }
    publish("10.20.0.11", 41000, 7)
    val first = candidate.select(active)
    publish("10.21.0.11", 43000, 8)
    val second = candidate.select(active)
    fun checked(selection: EndpointRouteCandidate.Selection, host: String, port: Int): JSONObject {
      val readback = JSONObject(selection.nativeReadback)
      val lineage = readback.getJSONObject("lineage")
      check(lineage.getString("\$schema") == "rusty.quest.embedded_duplex.effective_input_lineage.v1")
      check(lineage.getString("base_manifest_sha256") == EndpointRouteCandidate.sha256(base.getValue("product-input-manifest.json")))
      check(lineage.getString("base_route_sha256") == EndpointRouteCandidate.sha256(base.getValue("route-configuration.json")))
      check(lineage.getString("profile_sha256") == selection.profile.profileSha256)
      check(lineage.getString("effective_route_sha256") == EndpointRouteCandidate.sha256(selection.documents.getValue("route-configuration.json")))
      val assembly = readback.getJSONObject("assembly")
      check(assembly.getJSONObject("packaged_route").getJSONObject("local_control").getString("host") == host)
      check(assembly.getJSONObject("packaged_route").getJSONObject("local_control").getInt("port") == port)
      check(readback.getJSONObject("admission").getBoolean("token_issued"))
      check(readback.getJSONObject("admission").getBoolean("capability_use_authorized"))
      check(readback.getJSONObject("admission").getBoolean("source_development_policy"))
      check(readback.getJSONObject("admission").getLong("source_policy_horizon_ms") == 2592000000L)
      check(readback.getJSONObject("admission").getBoolean("foreign_caller_refused"))
      check(readback.getJSONObject("admission").getBoolean("rejected_caller_preserved_state"))
      check(readback.getJSONObject("admission").getBoolean("refusal_audit_retained"))
      return readback
    }
    val a = checked(first, "10.20.0.11", 41001)
    val b = checked(second, "10.21.0.11", 43001)
    check(a.getJSONObject("lineage").getString("base_route_sha256") == b.getJSONObject("lineage").getString("base_route_sha256"))
    check(a.getJSONObject("lineage").getString("effective_route_sha256") != b.getJSONObject("lineage").getString("effective_route_sha256"))
    check(a.getJSONObject("admission").similar(b.getJSONObject("admission")))
    val configA = JSONObject(a.getJSONObject("assembly").getString("runtime_config_json"))
    val configB = JSONObject(b.getJSONObject("assembly").getString("runtime_config_json"))
    check(configA.getJSONObject("embedded_duplex").similar(configB.getJSONObject("embedded_duplex")))
    check(configA.getJSONObject("admission").getJSONObject("snapshot").getJSONArray("grants")
        .similar(configB.getJSONObject("admission").getJSONObject("snapshot").getJSONArray("grants")))
    corruptAuthority = true
    publish("10.22.0.11", 45000, 9)
    val incompatible = refusal { candidate.select(active) }
    check(incompatible == "immutable packaged ceiling differs" && candidate.current === second)
    corruptAuthority = false
    val frozen = candidate.freezeForBootstrap()
    check(frozen === second)
    // One immutable snapshot supplies both proposed consumers; no old route-hash field is rewritten.
    val enrollmentDraft = frozen
    val bootstrapDraft = frozen
    check(enrollmentDraft === bootstrapDraft)
    val activeRefusal = refusal { candidate.select(active) }
    check(activeRefusal == "configuration_frozen_for_bootstrap" && candidate.current === second)
    val result = JSONObject().put("passed", true).put("first", a).put("second", b)
        .put("incompatible_refusal", incompatible).put("active_refusal", activeRefusal)
        .put("limits", "Real current native assembly/broker construction/token issue/use under source development policy. Host identity/time/trust fixtures; enrollment/bootstrap production shared-input API remains unadopted. No Android/process-fence/peer/session/media effect.")
    Files.write(out.resolve("BEHAVIOR.json"), result.toString(2).toByteArray())
    println("A->B actual native authority assembly and token issue/use passed; immutable ceiling and frozen snapshot refusals preserved B")
  }
}
