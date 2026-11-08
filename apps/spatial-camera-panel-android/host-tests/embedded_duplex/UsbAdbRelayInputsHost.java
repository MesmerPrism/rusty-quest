package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real frozen-input owner and strict profile ingress; native derivation is a modeled data leaf. */
public final class UsbAdbRelayInputsHost {
    static String nativeDeriver;
    interface Attempt { void run() throws Exception; }
    static void check(boolean value) { if (!value) throw new AssertionError("USB frozen input check"); }
    static void refuse(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (Exception expected) { return; }
        throw new AssertionError("unexpected frozen input acceptance");
    }
    static String digest(String raw) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)))
            result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    static byte[] profile(EmbeddedDuplexPackagedInputs base) throws Exception {
        Class<?> fixture = Class.forName("io.github.mesmerprism.rustyquest.spatial_camera_panel.SpatialCameraControlProfileTest");
        java.lang.reflect.Method factory = fixture.getDeclaredMethod("validProfile");
        factory.setAccessible(true);
        JSONObject profile = new JSONObject(factory.invoke(fixture.getConstructor().newInstance()).toString());
        JSONArray actual = new JSONObject(base.json("route-configuration.json")).getJSONArray("peers");
        JSONArray endpoints = new JSONArray();
        for (int i = 0; i < 2; i++) endpoints.put(new JSONObject()
                .put("peer_id", actual.getJSONObject(i).getString("peer_id"))
                .put("media_endpoint", new JSONObject().put("protocol", "rmanvid-v4-packed-stereo").put("host", "127.0.0.1").put("port", 51000+i*10))
                .put("control_endpoint", new JSONObject().put("protocol", "rqod1").put("host", "127.0.0.1").put("port", 51001+i*10))
                .put("local_source_port", 51002+i*10));
        return profile.put("peer_network_endpoints", endpoints).put("peer_network_transport", FrozenEffectiveInputs.USB_ADB_RELAY)
                .toString().getBytes(StandardCharsets.UTF_8);
    }
    static String derived(EmbeddedDuplexPackagedInputs base, String request, int corrupt) throws Exception {
        JSONObject input = new JSONObject(request), profile = new JSONObject(input.getString("profile_json"));
        JSONObject actual = null;
        if (nativeDeriver != null) {
            Process child = new ProcessBuilder(nativeDeriver, "--derive").start();
            try (java.io.OutputStream stdin = child.getOutputStream()) { stdin.write(request.getBytes(StandardCharsets.UTF_8)); }
            String stdout = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (child.waitFor() != 0) throw new IllegalStateException(stderr);
            actual = new JSONObject(stdout);
            if (corrupt == 0) return stdout; // Exercise exact native documents and lineage without regeneration.
        }
        JSONObject docs = actual == null ? new JSONObject(base.documentSnapshot()) : actual.getJSONObject("documents");
        JSONObject route = new JSONObject(actual == null ? base.json("route-configuration.json") : docs.getString("route-configuration.json"));
        JSONArray peers = route.getJSONArray("peers"), selected = profile.getJSONArray("peer_network_endpoints");
        if (actual == null) {
            route.put("transport_contract_id", FrozenEffectiveInputs.USB_TRANSPORT_CONTRACT);
            for (int i = 0; i < peers.length(); i++) {
                JSONObject peer = peers.getJSONObject(i);
                for (int j = 0; j < selected.length(); j++) {
                    JSONObject endpoint = selected.getJSONObject(j);
                    if (peer.getString("peer_id").equals(endpoint.getString("peer_id"))) {
                        peer.put("media_endpoint", endpoint.getJSONObject("media_endpoint"));
                        peer.put("control_endpoint", endpoint.getJSONObject("control_endpoint"));
                        peer.put("local_source_port", endpoint.getInt("local_source_port"));
                    }
                }
            }
        }
        if (corrupt == 1) route.remove("transport_contract_id");
        if (corrupt == 2) peers.getJSONObject(0).put("local_source_port", 52000);
        docs.put("route-configuration.json", route.toString());
        JSONObject lineage = actual == null ? new JSONObject().put("$schema", "rusty.quest.embedded_duplex.effective_input_lineage.v1")
                .put("base_manifest_sha256", digest(base.manifestJson()))
                .put("base_route_sha256", base.digest("route-configuration.json"))
                .put("profile_sha256", digest(input.getString("profile_json"))) : actual.getJSONObject("lineage");
        if (corrupt == 3) lineage.put("profile_sha256", "0".repeat(64));
        // Rehash intentional route mutations so semantic checks, not only byte custody, must reject them.
        lineage.put("effective_route_sha256", digest(route.toString()));
        return new JSONObject().put("$schema", "rusty.quest.embedded_duplex.effective_inputs_result.v1")
                .put("documents", docs).put("lineage", lineage).toString();
    }
    public static void main(String[] args) throws Exception {
        nativeDeriver = args.length == 2 ? args[1] : null;
        Path assets = Path.of(args[0]);
        String manifest = Files.readString(assets.resolve("product-input-manifest.json"));
        JSONObject identity = new JSONObject(manifest).getJSONObject("package");
        EmbeddedDuplexPackagedInputs base = EmbeddedDuplexPackagedInputs.load(new EmbeddedDuplexPackagedInputs.AssetReader() {
            public InputStream open(String name) throws Exception { return Files.newInputStream(assets.resolve(name)); }
            public String[] list() throws Exception {
                try (java.util.stream.Stream<Path> files = Files.list(assets)) { return files.map(p -> p.getFileName().toString()).toArray(String[]::new); }
            }
        }, digest(manifest), identity.getString("application_id"), identity.getString("signing_certificate_sha256"), EmbeddedDuplexPackagedInputs.InstalledRole.PEER_A);
        byte[] profile = profile(base);
        int[] calls = {0}, corruption = {0};
        FrozenEffectiveInputs.Deriver deriver = request -> { calls[0]++; return derived(base, request, corruption[0]); };
        FrozenEffectiveInputs.Selector disabled = new FrozenEffectiveInputs.Selector(base, 40, deriver);
        refuse(() -> disabled.select(profile)); check(calls[0] == 0);
        FrozenEffectiveInputs.Selector capabilityOnly = new FrozenEffectiveInputs.Selector(base, 42, deriver, true);
        FrozenEffectiveInputs packaged = capabilityOnly.freeze();
        check(!packaged.developmentUsbRelayForRoute(packaged.digest("route-configuration.json")) && calls[0] == 0);
        FrozenEffectiveInputs.Selector owner = new FrozenEffectiveInputs.Selector(base, 41, deriver, true);
        FrozenEffectiveInputs selected = owner.select(profile);
        refuse(() -> selected.developmentUsbRelayForRoute(selected.digest("route-configuration.json")));
        for (int bad : new int[]{1,2,3}) {
            corruption[0] = bad; refuse(() -> owner.select(profile)); check(owner.current() == selected);
        }
        corruption[0] = 0;
        FrozenEffectiveInputs frozen = owner.freeze();
        check(frozen == selected && frozen.developmentUsbRelayForRoute(frozen.digest("route-configuration.json")));
        refuse(() -> frozen.developmentUsbRelayForRoute("0".repeat(64)));
        JSONObject peer = new JSONObject(frozen.json("route-configuration.json")).getJSONArray("peers").getJSONObject(0);
        JSONObject endpoint = peer.getJSONObject("control_endpoint");
        frozen.requireControlEndpoint(peer.getString("peer_id"), endpoint.getString("host"), endpoint.getInt("port"));
        refuse(() -> frozen.requireControlEndpoint(peer.getString("peer_id"), "127.0.0.1", endpoint.getInt("port")+5));
        refuse(() -> owner.select(profile));
        owner.retire();
        refuse(() -> frozen.developmentUsbRelayForRoute(frozen.digest("route-configuration.json")));
        System.out.println("PASS real strict profile/frozen selection/seal/digests; capability absence before native leaf, forged contract/producer-port/profile digest refused without publication, exact frozen control endpoint, no sockets/native runtime; derivation="+(nativeDeriver == null ? "modeled" : "actual pure native function"));
    }
}
