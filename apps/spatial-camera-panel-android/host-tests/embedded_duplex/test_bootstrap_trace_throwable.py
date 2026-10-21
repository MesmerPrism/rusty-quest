"""Real JDK typing of the production asynchronous bootstrap diagnostic callback.

Extracts production diagnostic bodies, not Android/media owner implementations.
No Android, JNI, native authority, APK, device or physical cleanup is exercised.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


PACKAGE = "io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex"


def block(text, marker):
    if text.count(marker) != 1:
        raise ValueError("production block boundary changed")
    start = text.index(marker)
    opening = text.index("{", start)
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
    raise ValueError("production block incomplete")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pin(path):
    return dict(path=str(path), bytes=path.stat().st_size, sha256=digest(path))


def generate(bootstrap, host):
    failure = block(bootstrap, "    enum Failure {")
    trace = block(bootstrap, "    static final class Trace {")
    marker = "        }).thenCompose(next->next).whenComplete((value,failure)->{"
    callback = block(host, marker)
    callback = callback[callback.index(".whenComplete(") + len(".whenComplete("):]
    source = HARNESS.replace("FAILURE_BODY", failure).replace("TRACE_BODY", trace)
    source = source.replace("CALLBACK_BODY", callback)
    pieces = {name: hashlib.sha256(value.encode()).hexdigest()
              for name, value in (("failure_enum", failure), ("trace", trace),
                                  ("actual_when_complete_callback", callback))}
    return source, pieces


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--javac", required=True, type=Path)
    parser.add_argument("--java", required=True, type=Path)
    parser.add_argument("--expected-javac-sha256", required=True)
    parser.add_argument("--expected-java-sha256", required=True)
    args = parser.parse_args()
    for path in (args.root, args.output, args.javac, args.java):
        if not path.is_absolute():
            parser.error("explicit absolute source, output and selected JDK paths required")
    root = args.root.resolve(strict=True)
    out = args.output.resolve()
    if out.exists() or not out.parent.is_dir() or out == root or root in out.parents:
        parser.error("fresh output with existing parent outside source root required")
    javac = args.javac.resolve(strict=True)
    java = args.java.resolve(strict=True)
    if (javac.parent != java.parent or javac.stem != "javac" or java.stem != "java"
            or digest(javac) != args.expected_javac_sha256
            or digest(java) != args.expected_java_sha256):
        parser.error("exact existing selected JDK javac/java pair required")
    base = root / ("apps/spatial-camera-panel-android/app/src/main/java/"
                   "io/github/mesmerprism/rustyquest/spatial_camera_panel/embedded_duplex")
    paths = [base / name for name in ("EmbeddedDuplexBootstrap.java",
             "EmbeddedDuplexProcessHost.java", "EmbeddedDuplexOwnProjectionFailure.java")]
    raw = [path.read_bytes() for path in paths]
    texts = [value.decode("utf-8-sig").replace("\r\n", "\n") for value in raw]
    unit, pieces = generate(texts[0], texts[1])
    out.mkdir()
    classes = out / "classes"
    classes.mkdir()
    harness = out / "TraceThrowableHarness.java"
    harness.write_text(unit, encoding="utf-8", newline="\n")
    metadata = dict(source_pins=[pin(path) for path in paths],
                    javac=pin(javac), java=pin(java), generated=pin(harness),
                    extracted_bodies=pieces,
                    scope="production diagnostic/JDK callback typing and pure-JDK behavior only")
    print(json.dumps(metadata), flush=True)
    compiled = subprocess.run([str(javac), "--release", "17", "-encoding", "UTF-8",
        "-d", str(classes), str(paths[2]), str(harness)], check=False)
    result = dict(metadata, javac_exit_code=compiled.returncode, java_exit_code=None,
                  android_qualified=False, native_or_device_effects=0)
    if compiled.returncode:
        (out / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        return compiled.returncode
    executed = subprocess.run([str(java), "-cp", str(classes),
                               PACKAGE + ".TraceThrowableHarness"], check=False)
    result["java_exit_code"] = executed.returncode
    (out / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return executed.returncode


HARNESS = r'''package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;
import java.util.concurrent.*;
public final class TraceThrowableHarness {
    static class EmbeddedDuplexBootstrap {
FAILURE_BODY
TRACE_BODY
    }
    private EmbeddedDuplexBootstrap.Failure lastBootstrapFailure;
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("production diagnostic control failed");
    }
    CompletableFuture<String> observe(CompletableFuture<String> actual,
                                     EmbeddedDuplexBootstrap.Trace trace) {
        return actual.whenComplete(CALLBACK_BODY);
    }
    private void failure(Throwable original, EmbeddedDuplexBootstrap.Failure stage,
                         EmbeddedDuplexBootstrap.Failure expected) {
        EmbeddedDuplexBootstrap.Trace trace = new EmbeddedDuplexBootstrap.Trace();
        trace.mark(stage);
        lastBootstrapFailure = null;
        CompletableFuture<String> actual = new CompletableFuture<>();
        CompletableFuture<String> observed = observe(actual, trace);
        check(actual.completeExceptionally(original));
        check(observed.isCompletedExceptionally());
        try { observed.join(); throw new AssertionError("failure was swallowed"); }
        catch (CompletionException retained) {
            check(retained == original || retained.getCause() == original);
        }
        check(trace.failure() == expected);
        check(lastBootstrapFailure == expected);
        check(expected.stage.matches("[a-z_]+") && expected.code.matches("[a-z_]+"));
    }
    public static void main(String[] ignored) {
        TraceThrowableHarness h = new TraceThrowableHarness();
        EmbeddedDuplexBootstrap.Failure projection = EmbeddedDuplexBootstrap.Failure.RESOURCE_OWN_PROJECTION;
        h.failure(new IllegalStateException("TEST_MARKER_NOT_EXPORTED"), projection, projection);
        h.failure(new AssertionError("TEST_MARKER_NOT_EXPORTED"), projection, projection);
        h.failure(new Throwable("TEST_MARKER_NOT_EXPORTED"), projection, projection);
        h.failure(new CompletionException(new ExecutionException(new TimeoutException("TEST_MARKER_NOT_EXPORTED"))),
                  projection, EmbeddedDuplexBootstrap.Failure.RESOURCE_PROJECTION_TIMEOUT);
        h.failure(new InterruptedException("TEST_MARKER_NOT_EXPORTED"), projection,
                  EmbeddedDuplexBootstrap.Failure.RESOURCE_PROJECTION_INTERRUPTED);
        EmbeddedDuplexBootstrap.Failure[] expected = {
            EmbeddedDuplexBootstrap.Failure.RESOURCE_CAPTURE_UNAVAILABLE,
            EmbeddedDuplexBootstrap.Failure.RESOURCE_CAPTURE_FRESHNESS,
            EmbeddedDuplexBootstrap.Failure.RESOURCE_PROJECTION_CARRIER,
            EmbeddedDuplexBootstrap.Failure.RESOURCE_PROJECTION_SUPERSEDED,
            EmbeddedDuplexBootstrap.Failure.RESOURCE_PROJECTION_INTERRUPTED
        };
        int index = 0;
        for (EmbeddedDuplexOwnProjectionFailure.Reason reason : EmbeddedDuplexOwnProjectionFailure.Reason.values()) {
            h.failure(new CompletionException(new EmbeddedDuplexOwnProjectionFailure(reason)), projection,
                      expected[index++]);
        }
        Throwable four = new TimeoutException("TEST_MARKER_NOT_EXPORTED");
        for (int i = 0; i < 4; i++) four = new CompletionException(four);
        h.failure(four, projection, EmbeddedDuplexBootstrap.Failure.RESOURCE_PROJECTION_TIMEOUT);
        h.failure(new CompletionException(four), projection, projection);
        AssertionError suppressed = new AssertionError("TEST_MARKER_NOT_EXPORTED");
        suppressed.addSuppressed(new EmbeddedDuplexOwnProjectionFailure(
            EmbeddedDuplexOwnProjectionFailure.Reason.CARRIER_NOT_READY));
        h.failure(suppressed, projection, projection);
        h.failure(new EmbeddedDuplexOwnProjectionFailure(EmbeddedDuplexOwnProjectionFailure.Reason.CARRIER_NOT_READY),
                  EmbeddedDuplexBootstrap.Failure.CONTROL_ENDPOINT, EmbeddedDuplexBootstrap.Failure.CONTROL_ENDPOINT);
        EmbeddedDuplexBootstrap.Trace trace = new EmbeddedDuplexBootstrap.Trace();
        trace.mark(EmbeddedDuplexBootstrap.Failure.NATIVE_INITIALIZE);
        h.lastBootstrapFailure = null;
        CompletableFuture<String> success = new CompletableFuture<>();
        CompletableFuture<String> observed = h.observe(success, trace);
        String value = new String("result");
        check(success.complete(value));
        check(observed.join() == value);
        check(trace.failure() == EmbeddedDuplexBootstrap.Failure.NATIVE_INITIALIZE);
        check(h.lastBootstrapFailure == null);
        System.out.println("PASS production Trace + real JDK whenComplete controls=" + checks
            + "; no Android/native/device qualification");
    }
}
'''


if __name__ == "__main__":
    raise SystemExit(main())
