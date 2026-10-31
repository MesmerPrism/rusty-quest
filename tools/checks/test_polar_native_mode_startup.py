"""Execute production native-mode choice/resume and Polar startup owner bodies.

Only Android/JNI and BLE acquisition effects are doubled. This is host evidence,
not a permission-dialog, sensor-discovery or native-session device claim.
"""
import argparse
from pathlib import Path

from test_experiment_control_mode import run_java
from test_polar_location_settings import method


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--json-jar", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--jdk-bin", type=Path, required=True)
    args = parser.parse_args()
    package = Path("io/github/mesmerprism/rustyquest/native_renderer")
    panels = args.repo / "apps/native-renderer-android/panel-modules"
    composition = panels / "breath-composition/src/main/java" / package
    polar = panels / "polar/src/main/java" / package
    module = (composition / "BreathCompositionPanelModule.java").read_text(encoding="utf-8")
    runtime = (polar / "PolarSensorRuntime.java").read_text(encoding="utf-8")
    panel = (polar / "PolarSensorPanel.java").read_text(encoding="utf-8")
    fixture = (args.repo / "apps/native-renderer-android/tests/java" / package /
               "PolarNativeModeStartupTest.java").read_text(encoding="utf-8")
    sections = {
        "__MODULE__": (module, ["static BreathCompositionPanelModule controllerFor(",
                                 "private void selectExperimentControlMode(",
                                 "private void hideRemoteControlPanel(", "protected void onResume()"]),
        "__RUNTIME__": (runtime, ["void ensureAutoConnection()", "boolean requestStartupPermissions(",
                                   "void onHostResume()", "JSONObject experimenterStatusProjection()"]),
        "__PANEL__": (panel, ["void ensureAutoConnection()", "boolean requestStartupPermissions(",
                               "void onHostResume()", "private void runAutomaticConnectionAttempt()",
                               "private void beginAutomaticConnectionCycle()",
                               "private void resumeAutomaticConnectionIfRequested()",
                               "private void scheduleAutomaticRecovery(String detail) {",
                               "private void scheduleAutomaticRecovery(String detail, boolean announceRetry)",
                               "private void finishAutomaticConnectionCycle()",
                               "private void cancelAutomaticRecovery(", "private void disconnect()"]),
        "__SUPPORT__": (runtime, ["final class PolarRecoveryIntent", "final class PolarAutoConnectionPolicy"]),
    }
    # Baseline lacks the new entrypoints: the real old selection still compiles
    # and fails on the missing startup effect, rather than a missing symbol.
    for token, source, anchor in (
        ("__MODULE__", module, "private void requestPolarStartupFromModeChoice()"),
        ("__RUNTIME__", runtime, "void requestStartupFromModeChoice("),
        ("__PANEL__", panel, "void requestStartupFromModeChoice("),
    ):
        if anchor in source:
            sections[token][1].append(anchor)
    for token, (source, anchors) in sections.items():
        fixture = fixture.replace(token, "\n".join(method(source, anchor) for anchor in anchors))
    main_source = args.repo / "apps/native-renderer-android/src/main/java" / package
    run_java(args, [composition / "ExperimentControlMode.java",
                    main_source / "NativeRendererExperimentLauncherPolicy.java"],
             fixture, "PolarNativeModeStartupTest")


if __name__ == "__main__":
    main()
