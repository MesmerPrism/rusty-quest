package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Real host OS-lock regressions; no Android/device or teardown claims. */
public final class EmbeddedDuplexProcessFenceHostTest {
    interface Attempt { void run() throws Exception; }
    static void rejects(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("expected fail-closed rejection");
    }
    static EmbeddedDuplexProcessFence acquire(Path path) throws Exception {
        return EmbeddedDuplexProcessFence.acquire(path.toFile(), null, null, () -> {});
    }
    static Process holder(Path path, boolean pending) throws Exception {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                EmbeddedDuplexProcessFenceHostTest.class.getName(), "hold", path.toString(),
                Boolean.toString(pending)).redirectErrorStream(true).start();
        String ready = new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();
        if (!"held".equals(ready)) throw new AssertionError("child failed: " + ready);
        return process;
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            EmbeddedDuplexProcessFence fence = acquire(Path.of(args[1]));
            if (Boolean.parseBoolean(args[2])) fence.beforeRuntimeEffects(null, null);
            System.out.println("held"); System.out.flush();
            System.in.read();
            fence.close();
            return;
        }
        Path directory = Files.createTempDirectory("duplex-fence-test");
        EmbeddedDuplexProcessFence first = acquire(directory);
        if (first.generation() != 1) throw new AssertionError("initial generation");
        rejects(() -> acquire(directory));
        EmbeddedDuplexProcessFence.CallbackGuard liveCallback = first.callbacks();
        EmbeddedDuplexProcessFence.CallbackGuard retiredCallback = first.callbacks();
        liveCallback.requireLive();
        retiredCallback.retire();
        rejects(retiredCallback::requireLive);
        long retired = first.generation();
        first.close();
        rejects(() -> first.requireLive(retired));
        rejects(liveCallback::requireLive);
        EmbeddedDuplexProcessFence second = acquire(directory);
        if (second.generation() != 2 || second.recoveryOnly()) throw new AssertionError("durable generation");
        rejects(() -> second.requireLive(retired));
        second.beforeRuntimeEffects(null, null);
        rejects(() -> second.beforeRuntimeEffects(null, null));
        second.afterVerifiedNoMediaCleanup(null, null); // Simulated caller proof; no device assertion.
        second.close();
        Process live = holder(directory, false);
        rejects(() -> acquire(directory));
        live.getOutputStream().write(1); live.getOutputStream().flush();
        if (live.waitFor() != 0) throw new AssertionError("child release");
        Process crashed = holder(directory, true);
        crashed.destroyForcibly().waitFor();
        try (EmbeddedDuplexProcessFence recovered = acquire(directory)) {
            if (!recovered.recoveryOnly() || recovered.generation() != 5) throw new AssertionError("crash pending generation");
            rejects(recovered::requireFresh);
            rejects(recovered::callbacks);
            rejects(() -> recovered.afterVerifiedNoMediaCleanup(null, null));
        }
        // Releasing a pending fence never changes pending to clear.
        try (EmbeddedDuplexProcessFence recovered = acquire(directory)) {
            if (!recovered.recoveryOnly()) throw new AssertionError("release masqueraded as cleanup");
        }
        Path migration = Files.createTempDirectory("duplex-fence-migration");
        rejects(() -> EmbeddedDuplexProcessFence.acquire(migration.toFile(), "retained", null, () -> {}));
        rejects(() -> acquire(migration)); // Failed migration leaves missing state fail closed.
        Path binding = Files.createTempDirectory("duplex-fence-binding");
        try (EmbeddedDuplexProcessFence bound = acquire(binding)) {
            bound.beforeRuntimeEffects("checkpoint exact bytes", "evidence exact bytes");
        }
        rejects(() -> acquire(binding));
        try (EmbeddedDuplexProcessFence bound = EmbeddedDuplexProcessFence.acquire(binding.toFile(),
                "checkpoint exact bytes", "evidence exact bytes", () -> {})) {
            if (!bound.recoveryOnly()) throw new AssertionError("journal binding lost pending");
        }
        Path corrupt = Files.createTempDirectory("duplex-fence-corrupt");
        try (EmbeddedDuplexProcessFence unused = acquire(corrupt)) { }
        Path record = corrupt.resolve("process-fence.v1.lock");
        Files.write(record, "corrupt".getBytes(StandardCharsets.US_ASCII));
        rejects(() -> acquire(corrupt));
        Files.write(record, new byte[0]);
        rejects(() -> acquire(corrupt));
        Path missing = Files.createTempDirectory("duplex-fence-missing");
        try (EmbeddedDuplexProcessFence unused = acquire(missing)) { }
        Files.delete(missing.resolve("process-fence.v1.lock"));
        rejects(() -> acquire(missing));
        System.out.println("App process fence host regressions passed; native/effect/device fencing unproven.");
    }
}
