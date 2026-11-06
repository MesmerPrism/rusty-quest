import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.rusty.quest.feedback.NativeFeedbackBridge;

/** Standalone trusted-file JNI proof; no Application, Activity, package or app-UID claim.
 * Args: bridge-file bridge-sha256 module-directory fresh-session-directory [interactive].
 * module-directory/artifacts.tsv has exactly four UTF-8 tab-separated rows:
 * a1\t1\t<sha256>\t<basename>, b2\t2\t<sha256>\t<basename>,
 * a3\t3\t<sha256>\t<basename>, bad-behavior\t7\t<sha256>\t<basename>.
 * Hashes must be independently retained trusted build outputs, not inferred here.
 * Interactive mode stages B2 and A3 only after READY and an exact stdin line
 * B2\t<trusted-sha256> or A3\t<trusted-sha256>. Their fixed manifest basenames
 * remain frozen; their files may be supplied after READY. The stdin digest replaces
 * that row's initial digest. No arbitrary command, path or class is accepted.
 * All image handles and the facade remain resident until this process exits.
 */
public final class AndroidBridgeProof {
    private static int assertions;
    private static long allocation;

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError("assertion " + assertions + ": " + description);
    }

    private static Path physical(Path path) throws IOException {
        Path result = path.toAbsolutePath().normalize();
        check(result.equals(result.toFile().getCanonicalFile().toPath()), "physical canonical path");
        for (Path cursor = result; cursor != null; cursor = cursor.getParent()) {
            check(!Files.isSymbolicLink(cursor), "no symbolic link");
        }
        return result;
    }

    private static byte[] digest(String text) {
        check(text.matches("[0-9a-f]{64}"), "exact lowercase SHA256");
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte)Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static byte[] hash(Path source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(source)) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        return digest.digest();
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(digits[(value & 255) >>> 4]).append(digits[value & 15]);
        return result.toString();
    }

    private static void copyReadonly(Path source, Path destination) throws IOException {
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            check(destination.toFile().setReadOnly(), "readonly before copy");
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
        }
        check(!Files.isWritable(destination), "incoming remains readonly");
    }

    private static void state(NativeFeedbackBridge bridge, String label, long completed,
                              long value, long generation, long retained) {
        NativeFeedbackBridge.Snapshot snapshot = bridge.snapshot();
        check(snapshot.completed == completed, label + " completed");
        check(snapshot.completedUnsigned().equals(Long.toString(completed)), label + " unsigned completed");
        check(snapshot.value == value, label + " value");
        check(snapshot.generation == generation, label + " generation");
        check(snapshot.retainedImages == retained, label + " retained images");
        if (allocation == 0) allocation = snapshot.allocationToken;
        check(allocation != 0 && snapshot.allocationToken == allocation, label + " stable allocation token");
        byte[] expected = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
        check(Arrays.equals(snapshot.payload(), expected), label + " payload");
        byte[] copy = snapshot.payload();
        copy[0] ^= 1;
        check(Arrays.equals(snapshot.payload(), expected), label + " snapshot owns defensive copy");
        System.out.println("RM030_NATIVE_STATE label=" + label + " completed=" + completed
                + " value=" + value + " generation=" + generation + " retained=" + retained
                + " allocation=" + allocation + " payload=" + hex(expected));
    }

    private static void receivePhase(String phase, String name, long generation,
                                     BufferedReader input, NativeFeedbackBridge bridge,
                                     Map<String, Path> sources, Map<String, byte[]> hashes,
                                     Path incoming) throws Exception {
        NativeFeedbackBridge.Snapshot snapshot = bridge.snapshot();
        System.out.println("READY phase=" + phase + " completed=" + snapshot.completedUnsigned()
                + " value=" + snapshot.value + " generation=" + snapshot.generation
                + " retained=" + snapshot.retainedImages + " allocation=" + snapshot.allocationToken
                + " payload=" + hex(snapshot.payload()));
        System.out.flush();
        String line = input.readLine();
        if (line == null) throw new IOException("EOF waiting for " + phase);
        check(line.matches(phase + "\t[0-9a-f]{64}"), "exact closed phase and trusted hash");
        byte[] expected = digest(line.substring(phase.length() + 1));
        Path source = physical(sources.get(name));
        check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "received real " + phase + " file");
        check(Files.size(source) > 0 && Files.size(source) <= NativeFeedbackBridge.IMAGE_LIMIT, "received bounded image");
        check(MessageDigest.isEqual(hash(source), expected), "received trusted " + phase + " digest");
        copyReadonly(source, incoming.resolve(generation + ".so"));
        hashes.put(name, expected);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 4 || (args.length == 5 && "interactive".equals(args[4])),
                "bridge-file bridge-sha256 module-directory fresh-session-directory [interactive] required");
        boolean interactive = args.length == 5;
        Path library = physical(Paths.get(args[0]));
        byte[] bridgeHash = digest(args[1]);
        check(Files.isRegularFile(library, LinkOption.NOFOLLOW_LINKS), "real bridge file");
        check(Files.size(library) <= NativeFeedbackBridge.STAGED_LIMIT, "bounded bridge");
        check(MessageDigest.isEqual(hash(library), bridgeHash), "trusted bridge digest");
        Path modules = physical(Paths.get(args[2]));
        Path manifest = physical(modules.resolve("artifacts.tsv"));
        check(Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                && Files.size(manifest) <= 4096, "bounded module manifest");
        List<String> rows = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        check(rows.size() == 4, "four real module entries");
        Map<String, Long> generations = new LinkedHashMap<String, Long>();
        generations.put("a1", 1L); generations.put("b2", 2L);
        generations.put("a3", 3L); generations.put("bad-behavior", 7L);
        Map<String, Path> sources = new LinkedHashMap<String, Path>();
        Map<String, byte[]> hashes = new LinkedHashMap<String, byte[]>();
        for (String row : rows) {
            String[] fields = row.split("\t", -1);
            check(fields.length == 4 && generations.containsKey(fields[0]), "known module row");
            String name = fields[0];
            check(fields[1].equals(Long.toString(generations.get(name))), "compiled generation identity");
            check(fields[3].matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,191}"), "fixed source basename");
            Path source = physical(modules.resolve(fields[3]));
            byte[] expected = digest(fields[2]);
            if (!interactive || (!"b2".equals(name) && !"a3".equals(name))) {
                check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "real module file");
                check(Files.size(source) > 0 && Files.size(source) <= NativeFeedbackBridge.IMAGE_LIMIT, "bounded module");
                check(MessageDigest.isEqual(hash(source), expected), "trusted module digest " + name);
            }
            check(sources.put(name, source) == null, "unique module entry");
            hashes.put(name, expected);
        }
        check(sources.keySet().equals(generations.keySet()), "complete module closure");
        Path session = physical(Paths.get(args[3]));
        check(!Files.exists(session, LinkOption.NOFOLLOW_LINKS), "fresh process-owned session");
        Files.createDirectory(session);
        Path incoming = Files.createDirectory(session.resolve("native-incoming"));
        for (Map.Entry<String, Long> entry : generations.entrySet()) {
            if (!interactive || (!"b2".equals(entry.getKey()) && !"a3".equals(entry.getKey()))) {
                copyReadonly(sources.get(entry.getKey()), incoming.resolve(entry.getValue() + ".so"));
            }
        }
        copyReadonly(sources.get("a1"), incoming.resolve("99.so"));
        final NativeFeedbackBridge bridge = new NativeFeedbackBridge(library.toFile(), bridgeHash, session.toFile());
        state(bridge, "initial", 0, 0, 0, 0);
        bridge.activate(1, hashes.get("a1"), 13);
        state(bridge, "a1-selected", 0, 0, 1, 1);
        bridge.compute(4);
        state(bridge, "a1-dispatched", 1, 5, 1, 1);
        byte[] incorrect = hashes.get("a1").clone(); incorrect[0] ^= 1;
        try {
            bridge.activate(99, incorrect, 13);
            throw new AssertionError("wrong digest accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("SHA256"), "digest refusal precedes native load");
        }
        state(bridge, "digest-rejected", 1, 5, 1, 1);
        bridge.compute(4);
        state(bridge, "a1-after-digest", 2, 10, 1, 1);
        try {
            bridge.activate(7, hashes.get("bad-behavior"), 13);
            throw new AssertionError("wrong semantic probe accepted");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("code 2"), "semantic probe refusal");
        }
        state(bridge, "probe-rejected", 2, 10, 1, 2);
        bridge.compute(4);
        state(bridge, "a1-after-probe", 3, 15, 1, 2);
        BufferedReader phaseInput = interactive
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)) : null;
        if (interactive) receivePhase("B2", "b2", 2, phaseInput, bridge, sources, hashes, incoming);
        bridge.activate(2, hashes.get("b2"), 19);
        state(bridge, "b2-selected", 3, 15, 2, 3);
        bridge.compute(4);
        state(bridge, "b2-dispatched", 4, 25, 2, 3);
        if (interactive) receivePhase("A3", "a3", 3, phaseInput, bridge, sources, hashes, incoming);
        bridge.activate(3, hashes.get("a3"), 13);
        state(bridge, "a3-selected", 4, 25, 3, 4);
        bridge.compute(4);
        state(bridge, "a3-dispatched", 5, 30, 3, 4);
        try {
            bridge.compute(Long.MAX_VALUE);
            throw new AssertionError("overflow dispatch accepted");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("code 3"), "overflow refusal");
        }
        state(bridge, "overflow-rejected", 5, 30, 3, 4);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Runnable work = new Runnable() {
            @Override public void run() {
                try { for (int i = 0; i < 3; i++) bridge.compute(1); }
                catch (Throwable caught) { failure.compareAndSet(null, caught); }
            }
        };
        Thread first = new Thread(work, "rm030-first"), second = new Thread(work, "rm030-second");
        first.start(); second.start(); first.join(); second.join();
        check(failure.get() == null, "same facade serializes calls from two Java threads");
        state(bridge, "threads-complete", 11, 42, 3, 4);
        check(MessageDigest.isEqual(hash(library), bridgeHash), "bridge bytes retained");
        for (String name : sources.keySet()) check(MessageDigest.isEqual(hash(sources.get(name)), hashes.get(name)), "module bytes retained");
        System.out.println("PASS AndroidBridgeProof JNI A1-B2-A3 assertions=" + assertions
                + " scope=standalone-process-only mode=" + (interactive ? "interactive" : "static"));
    }
}
