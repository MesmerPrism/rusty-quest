package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real identity parser and local-file controls; proc leaves model Linux, not Android execution. */
public final class EmbeddedDuplexProcessIncarnationHostTest {
    interface Attempt { void run() throws Exception; }
    static void rejects(Attempt action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("expected invalid identity rejection");
    }
    static String stat(int pid, long ticks) {
        return pid + " (worker ) name) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 " + ticks + " 21 22\n";
    }
    public static void main(String[] args) throws Exception {
        String boot = "00112233-4455-6677-8899-aabbccddeeff";
        Path proc = Files.createTempDirectory("duplex-proc-leaves");
        Files.createDirectories(proc.resolve("sys/kernel/random"));
        Files.createDirectories(proc.resolve("42"));
        Files.writeString(proc.resolve("sys/kernel/random/boot_id"), boot + "\n");
        Files.writeString(proc.resolve("42/stat"), stat(42, 987654321));
        EmbeddedDuplexProcessIncarnation.Identity identity = EmbeddedDuplexProcessIncarnation.current(42, 10001, proc);
        if (identity.pid != 42 || identity.uid != 10001 || identity.ticks != 987654321 || !identity.boot.equals(boot))
            throw new AssertionError("birth identity differs");
        rejects(() -> EmbeddedDuplexProcessIncarnation.current(43, 10001, proc));
        Files.writeString(proc.resolve("42/stat"), stat(43, 123));
        rejects(() -> EmbeddedDuplexProcessIncarnation.current(42, 10001, proc));
        Files.writeString(proc.resolve("42/stat"), "42 (short) S 1\n");
        rejects(() -> EmbeddedDuplexProcessIncarnation.current(42, 10001, proc));
        Files.writeString(proc.resolve("42/stat"), stat(42, 0));
        rejects(() -> EmbeddedDuplexProcessIncarnation.current(42, 10001, proc));
        Files.writeString(proc.resolve("42/stat"), stat(42, 123));
        Files.writeString(proc.resolve("sys/kernel/random/boot_id"), "unknown\n");
        rejects(() -> EmbeddedDuplexProcessIncarnation.current(42, 10001, proc));
        rejects(() -> new EmbeddedDuplexProcessIncarnation.Identity(boot, 0, 10001, 1));
        rejects(() -> new EmbeddedDuplexProcessIncarnation.Identity(boot, 42, 0, 1));
        JSONObject canonical = new JSONObject().put("z", new JSONArray().put(1L).put(true).put(JSONObject.NULL))
                .put("a", "line\n\uD83D\uDE00");
        if (!"{\"a\":\"line\\n\uD83D\uDE00\",\"z\":[1,true,null]}".equals(EmbeddedDuplexCanonicalJson.encode(canonical)))
            throw new AssertionError("canonical ordering/escaping");
        if (!"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".equals(
                EmbeddedDuplexCanonicalJson.sha256Bytes(new byte[0]))) throw new AssertionError("canonical digest");
        rejects(() -> EmbeddedDuplexCanonicalJson.encode(1.5));
        rejects(() -> EmbeddedDuplexCanonicalJson.encode("\uD800"));
        byte[] owned = EmbeddedDuplexCanonicalJson.encode(canonical).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EmbeddedDuplexCanonicalJson.requireOwnedBytes(owned,canonical);
        // Android JSONTokener overwrites earlier duplicate keys. Supply that exact
        // last-key-wins result directly so the strict host parser cannot mask this guard.
        JSONObject lastWins = new JSONObject().put("sha256","conflicting").put("sha256","valid");
        byte[] conflicting = "{\"sha256\":\"conflicting\",\"sha256\":\"valid\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        rejects(() -> EmbeddedDuplexCanonicalJson.requireOwnedBytes(conflicting,lastWins));
        rejects(() -> EmbeddedDuplexCanonicalJson.requireOwnedBytes("{ \"sha256\":\"valid\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),lastWins));
        rejects(() -> EmbeddedDuplexCanonicalJson.readOwned(new byte[]{(byte)0xff}));
        System.out.println("Process incarnation and canonical JSON host controls passed; explicit Android last-key-wins checkpoint control; OS leaves modeled.");
    }
}
