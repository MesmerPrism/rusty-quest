package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import io.github.mesmerprism.rustyquest.spatial_camera_panel.EndpointRouteCandidate
import io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraControlProfileTest
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Actual APK-asset/bootstrap/enrollment readers; runtime-authority assembler is an explicit model. */
internal object EndpointRouteDerivationHost {
  @JvmStatic
  fun main(args: Array<String>) {
    val assets = Path.of(args[0])
    val nativeBinary = args[1]
    val out = Path.of(args[2])
    val base: Map<String, String> = Files.list(assets).use { paths -> paths.iterator().asSequence().associate {
      it.fileName.toString() to String(Files.readAllBytes(it), Charsets.UTF_8)
    } }
    val manifest = JSONObject(base.getValue("product-input-manifest.json"))
    val identity = manifest.getJSONObject("package")
    val originalRoute = JSONObject(base.getValue("route-configuration.json"))
    val originalPeers = originalRoute.getJSONArray("peers")
    val originalManifestSha = EndpointRouteCandidate.sha256(base.getValue("product-input-manifest.json"))
    val profilePath = out.resolve("control-profiles/active.profile.json")
    Files.createDirectories(profilePath.parent)
    val expected = JSONObject().put("route_schema", originalRoute.getString("schema"))
        .put("profile_schema", JSONObject(base.getValue("packed-stereo-profile.json")).getString("schema"))
        .put("product_id", originalRoute.getString("product_id"))
        .put("package_name", identity.getString("application_id"))
        .put("installed_role_id", originalPeers.getJSONObject(0).getString("installed_role_id"))
    fun native(request: JSONObject): JSONObject {
      val process = ProcessBuilder(nativeBinary).redirectError(out.resolve("native-runtime.stderr").toFile()).start()
      process.outputStream.use { it.write(request.toString().toByteArray()) }
      val result = process.inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
      check(process.waitFor() == 0) { "native probe failed" }
      val decoded = JSONObject(result)
      require(decoded.getBoolean("accepted")) { decoded.optString("error") }
      return decoded.getJSONObject("readback")
    }
    val reader = object : EndpointRouteCandidate.NativeReader {
      override fun canonicalBinding(json: String) = native(JSONObject()
          .put("action", "canonicalize_binding").put("binding", json)).getString("binding")
      override fun validate(route: String, profile: String, bindings: List<String>) = native(JSONObject()
          .put("action", "validate_route").put("route", route).put("profile", profile)
          .put("bindings", JSONArray(bindings)).put("expected", expected)).toString()
    }
    fun loader(documents: Map<String, String>, expectedSha: String): EmbeddedDuplexPackagedInputs {
      val source = object : EmbeddedDuplexPackagedInputs.AssetReader {
        override fun open(name: String) = ByteArrayInputStream(documents.getValue(name).toByteArray())
        override fun list() = documents.keys.toTypedArray()
      }
      return EmbeddedDuplexPackagedInputs.load(source, expectedSha,
          identity.getString("application_id"), identity.getString("signing_certificate_sha256"),
          EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A)
    }
    val originalInputs = loader(base, originalManifestSha)
    val candidate = EndpointRouteCandidate(base, reader)
    val fixture = SpatialCameraControlProfileTest::class.java.getDeclaredMethod("validProfile")
    fixture.isAccessible = true
    fun profile(host: String, port: Int, revision: Int): JSONObject {
      val root = fixture.invoke(SpatialCameraControlProfileTest()) as JSONObject
      val peers = JSONArray()
      for (i in 0..1) {
        val old = originalPeers.getJSONObject(i)
        peers.put(JSONObject().put("peer_id", old.getString("peer_id"))
            .put("media_endpoint", JSONObject(old.getJSONObject("media_endpoint").toString()))
            .put("control_endpoint", JSONObject(old.getJSONObject("control_endpoint").toString())))
      }
      peers.getJSONObject(0).getJSONObject("media_endpoint").put("host", host).put("port", port)
      peers.getJSONObject(0).getJSONObject("control_endpoint").put("host", host).put("port", port + 1)
      return root.put("revision", revision).put("peer_network_endpoints", peers)
    }
    fun publish(root: JSONObject) {
      val staged = Files.createTempFile(profilePath.parent, ".profile-", ".pending")
      Files.write(staged, root.toString().toByteArray())
      Files.move(staged, profilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    fun rejected(action: () -> Unit): String {
      try { action() } catch (failure: Exception) { return failure.message.orEmpty() }
      error("incompatible input was accepted")
    }
    publish(profile("10.20.0.11", 41000, 7))
    val first = candidate.select(profilePath)
    check(JSONObject(first.nativeReadback).getJSONArray("peers").getJSONObject(0).getString("media_host") == "10.20.0.11")
    publish(profile("10.21.0.11", 43000, 8))
    val second = candidate.select(profilePath)
    check(candidate.current === second)
    check(JSONObject(second.nativeReadback).getJSONObject("local_control").getString("host") == "10.21.0.11")
    check(JSONObject(second.nativeReadback).getJSONObject("local_control").getInt("port") == 43001)
    check(JSONObject(second.nativeReadback).getJSONArray("peers").getJSONObject(0).getInt("media_port") == 43000)
    // Compare all non-endpoint binding fields, rather than assuming regeneration preserves policy.
    fun bindingInvariant(raw: String): JSONObject {
      val binding = JSONObject(raw)
      val quest = binding.getJSONObject("quest")
      quest.remove("runtime_spec_canonical_sha256")
      val plan = quest.getJSONObject("spec").getJSONObject("plan")
      val endpoints = plan.getJSONArray("runtime_endpoints")
      for (i in 0 until endpoints.length()) {
        val endpoint = endpoints.getJSONObject(i)
        endpoint.remove("transport_bind_host")
        for (array in listOf("source_bindings", "receiver_ports", "transport_receive_ports")) {
          val rows = endpoint.getJSONArray(array)
          for (j in 0 until rows.length()) rows.getJSONObject(j).remove(if (array == "source_bindings") "source_port" else "port")
        }
      }
      val routes = plan.getJSONArray("transport_routes")
      for (i in 0 until routes.length()) {
        routes.getJSONObject(i).remove("connect_host")
        routes.getJSONObject(i).remove("connect_port")
      }
      return binding
    }
    for (name in listOf("peer_a_to_peer_b.media-binding.json", "peer_b_to_peer_a.media-binding.json"))
      check(bindingInvariant(base.getValue(name)).similar(bindingInvariant(second.documents.getValue(name))))
    for (name in listOf("product-spec.json", "accepted-product-lock.json", "client-lock.json", "planning-feature-lock.json", "packed-stereo-profile.json"))
      check(base.getValue(name) == second.documents.getValue(name))
    val routeInvariant = JSONObject(second.documents.getValue("route-configuration.json"))
    for (i in 0..1) {
      val candidatePeer = routeInvariant.getJSONArray("peers").getJSONObject(i)
      candidatePeer.put("media_endpoint", originalPeers.getJSONObject(i).getJSONObject("media_endpoint"))
      candidatePeer.put("control_endpoint", originalPeers.getJSONObject(i).getJSONObject("control_endpoint"))
    }
    check(routeInvariant.similar(originalRoute))
    val bad = profile("10.22.0.11", 45000, 9)
    bad.getJSONArray("peer_network_endpoints").getJSONObject(0).getJSONObject("media_endpoint").put("protocol", "foreign")
    publish(bad)
    val incompatible = rejected { candidate.select(profilePath) }
    check(candidate.current === second)

    // The new coherent closure can be loaded only against its new fixture manifest.
    val candidateSha = EndpointRouteCandidate.sha256(second.documents.getValue("product-input-manifest.json"))
    val installedRefusal = rejected { loader(second.documents, originalManifestSha) }
    check(installedRefusal == "manifest bytes differ")
    val candidateInputs = loader(second.documents, candidateSha)
    check(candidateSha != originalManifestSha)
    check(candidateInputs.digest("route-configuration.json") ==
        EndpointRouteCandidate.sha256(second.documents.getValue("route-configuration.json")))

    // This executes the real Bootstrap projection checker, not the native authority assembler.
    val runtime = JSONObject()
    for (key in listOf("adapter_id", "admission_authority_id", "grant_id", "validation_epoch_entropy_hex")) runtime.put(key, "host.probe")
    for (key in listOf("grant_expires_at_ms", "lease_expires_at_ms", "max_token_ttl_ms", "validation_wall_unix_ms", "validation_monotonic_elapsed_ns")) runtime.put(key, 1)
    runtime.put("embedded_duplex", JSONObject())
    val projection = JSONObject(second.nativeReadback).also { it.remove("peers") }
    fun modeledAssembly(requestJson: String): String {
      val request = JSONObject(requestJson)
      val hashes = JSONObject()
      for (key in listOf("product_spec", "product_lock", "client_lock", "media_lifecycle_lock", "app_feature_lock"))
        hashes.put(key, request.getJSONObject(key).getString("sha256"))
      hashes.put("media_bindings", JSONArray((0..1).map { request.getJSONArray("media_bindings").getJSONObject(it).getString("sha256") }))
      val modeledConfig = "{\"host_probe_only\":true}"
      return JSONObject().put("\$schema", "rusty.quest.embedded_duplex.packaged_config_result.v1")
          .put("runtime_config_json", modeledConfig).put("runtime_config_sha256", EndpointRouteCandidate.sha256(modeledConfig))
          .put("exact_input_sha256", hashes).put("packaged_route", projection).toString()
    }
    val originalBootstrapRefusal = rejected {
      EmbeddedDuplexBootstrap.prepare(originalInputs, runtime, ::modeledAssembly)
    }
    check(originalBootstrapRefusal == "native route projection differs from installed role")
    val prepared = EmbeddedDuplexBootstrap.prepare(candidateInputs, runtime, ::modeledAssembly)
    check(prepared.localControlHost == "10.21.0.11" && prepared.localControlPort == 43001)
    check(prepared.routeConfigurationSha256 == "sha256:" + candidateInputs.digest("route-configuration.json"))

    // Exercise actual enrollment equality with unchanged identity/key facts and only a changed route digest.
    val ctor = EmbeddedDuplexEnrollmentRequest::class.java.declaredConstructors.single().also { it.isAccessible = true }
    fun enrollment(routeDigest: String) = ctor.newInstance("peer_a", identity.getString("application_id"),
        identity.getString("signing_certificate_sha256"), originalManifestSha, routeDigest,
        "host.fixture.same-key", "host.fixture.same-public-key", originalPeers.getJSONObject(0).getString("device_id"),
        originalPeers.getJSONObject(0).getString("peer_id"), originalPeers.getJSONObject(1).getString("device_id"),
        originalPeers.getJSONObject(1).getString("peer_id")) as EmbeddedDuplexEnrollmentRequest
    val priorEnrollment = enrollment(EndpointRouteCandidate.sha256(base.getValue("route-configuration.json")))
    val changedEnrollment = enrollment(candidateInputs.digest("route-configuration.json"))
    check(!priorEnrollment.sameFacts(changedEnrollment))
    check(priorEnrollment.localKeyId == changedEnrollment.localKeyId)

    check(candidate.freezeForBootstrap() === second)
    publish(profile("10.22.0.11", 45000, 9))
    val activeRefusal = rejected { candidate.select(profilePath) }
    check(activeRefusal == "configuration_frozen_for_bootstrap")
    check(candidate.current === second)
    // Incompatible derivation must not secretly admit a route-only endpoint override.
    val staleBindings = listOf(base.getValue("peer_a_to_peer_b.media-binding.json"), base.getValue("peer_b_to_peer_a.media-binding.json"))
    rejected { reader.validate(second.documents.getValue("route-configuration.json"), base.getValue("packed-stereo-profile.json"), staleBindings) }
    val result = JSONObject().put("passed", true).put("source_manifest_sha256", originalManifestSha)
        .put("candidate_manifest_sha256", candidateSha).put("first", JSONObject(first.nativeReadback))
        .put("second", JSONObject(second.nativeReadback)).put("profile_sha256", second.profile.profileSha256)
        .put("incompatible_refusal", incompatible).put("active_refusal", activeRefusal)
        .put("original_manifest_refusal", installedRefusal).put("same_enrollment_facts", false)
        .put("original_bootstrap_refusal", originalBootstrapRefusal)
        .put("unchanged_nonendpoint_identity_policy_fields", true)
        .put("frozen_profile_revision", second.profile.profile.revision)
        .put("scope", "host candidate only; real route/binding/APK asset/bootstrap projection/enrollment equality readers; modeled native runtime-authority assembler; no Android/APK/device effects")
    Files.write(out.resolve("BEHAVIOR.json"), result.toString(2).toByteArray())
    println("endpoint A->B native route readback; incompatible/active/stale-binding/installed-manifest/enrollment refusals verified")
  }
}
