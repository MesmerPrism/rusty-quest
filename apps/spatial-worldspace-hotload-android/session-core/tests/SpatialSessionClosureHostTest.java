package io.github.mesmerprism.rustyquest.native_renderer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;

/** Exercises the selected, unchanged canonical verifier against external assets. */
public final class SpatialSessionClosureHostTest {
    interface Checked { void run() throws Exception; }
    static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    static void rejected(String label, String reason, Checked check) throws Exception {
        try { check.run(); } catch (Exception expected) {
            require(reason.equals(expected.getMessage()), "Wrong rejection for " + label + ": " + expected.getMessage());
            System.out.println("PASS rejected " + label); return;
        }
        throw new AssertionError("Accepted " + label);
    }
    static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 6, "assets/output/profile/provider/inventory/identities required");
        Path assets=Path.of(args[0]).toRealPath(), output=Path.of(args[1]).toAbsolutePath();
        ExperimentSessionPackagedClosure.AssetReader reader=logical -> {
            Path path=assets.resolve(logical).normalize();
            require(path.startsWith(assets), "asset escaped root");
            return Files.readAllBytes(path);
        };
        int count=0; HashSet<String> names=new HashSet<>();
        for (String line:Files.readAllLines(Path.of(args[5]),StandardCharsets.UTF_8)) {
            String[] fields=line.split("\t",-1);
            require(fields.length==3 && names.add(fields[0]), "asset inventory malformed");
            byte[] bytes=reader.read(fields[0]);
            require(bytes.length==Long.parseLong(fields[1]) && digest(bytes).equals(fields[2]), "asset byte identity mismatch");
            count++;
        }
        require(count==8, "selected test requires the actual eight-asset closure");
        byte[] lock=Files.readAllBytes(assets.resolve("feature-lock.json"));
        ExperimentSessionPackagedClosure.Anchors anchors=new ExperimentSessionPackagedClosure.Anchors(args[2],args[3],args[4]);
        Files.createDirectories(output);
        for (String name:new String[]{"positive","missing","anchor","mutated","selection"})Files.createDirectory(output.resolve(name));
        ExperimentSessionPackagedClosure.Result result=ExperimentSessionPackagedClosure.prepare(lock,reader,output.resolve("positive"),anchors,true);
        require(result.active && result.audioEntries.length==2 && result.guidanceEntries.length==2 && result.phaseAudioEntries.length==3, "canonical closure not active/complete");
        System.out.println("PASS canonical positive; eight exact assets; profile/guidance materialized");
        rejected("missing-lock","experiment-session-packaged-closure-input-missing",()->ExperimentSessionPackagedClosure.prepare(null,reader,output.resolve("missing"),anchors,true));
        rejected("wrong-provider-anchor","experiment-session-closure-anchor-mismatch",()->ExperimentSessionPackagedClosure.prepare(lock,reader,output.resolve("anchor"),
            new ExperimentSessionPackagedClosure.Anchors(args[2],"0".repeat(64),args[4]),true));
        rejected("mutated-profile-asset","experiment-session-profile-bytes-mismatch",()->ExperimentSessionPackagedClosure.prepare(lock,logical->{
            byte[] bytes=reader.read(logical);
            if(logical.equals("session-config/experiment-session.json"))bytes[0]^=1;
            return bytes;
        },output.resolve("mutated"),anchors,true));
        rejected("wrong-owner-selection","experiment-session-owner-selection-mismatch",()->ExperimentSessionPackagedClosure.prepare(lock,reader,output.resolve("selection"),anchors,false));
        System.out.println("PASS 5 canonical verifier cases; no Android/runtime acceptance");
    }
}
