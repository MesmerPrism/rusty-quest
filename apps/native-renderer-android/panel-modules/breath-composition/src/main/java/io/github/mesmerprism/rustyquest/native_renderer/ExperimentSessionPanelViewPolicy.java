package io.github.mesmerprism.rustyquest.native_renderer;

/** Pure rendering policy for labels, button admission, and honest unavailable states. */
final class ExperimentSessionPanelViewPolicy {
    static final class ViewState {
        final String bluetoothLine;
        final String polarLine;
        final String countLine;
        final String statusLine;
        final boolean showPolarFallback;
        final boolean startEnabled;
        final boolean saving;

        ViewState(
            String bluetoothLine,
            String polarLine,
            String countLine,
            String statusLine,
            boolean showPolarFallback,
            boolean startEnabled,
            boolean saving
        ) {
            this.bluetoothLine = bluetoothLine;
            this.polarLine = polarLine;
            this.countLine = countLine;
            this.statusLine = statusLine;
            this.showPolarFallback = showPolarFallback;
            this.startEnabled = startEnabled;
            this.saving = saving;
        }
    }

    private ExperimentSessionPanelViewPolicy() {}

    static ViewState project(ExperimentSessionPanelState state) {
        ExperimentSessionPanelState.PolarProjection polar = state.polar;
        String bluetooth;
        switch (polar.bluetooth) {
            case ON: bluetooth = "Bluetooth: on"; break;
            case OFF: bluetooth = "Bluetooth: off"; break;
            case UNSUPPORTED: bluetooth = "Bluetooth: unsupported"; break;
            case PERMISSION_REQUIRED: bluetooth = "Bluetooth: permission required"; break;
            case TURNING: bluetooth = "Bluetooth: changing state"; break;
            default: bluetooth = "Bluetooth: status unavailable"; break;
        }
        String polarLine;
        switch (polar.polar) {
            case CONNECTED: polarLine = "Polar automatic connection: connected"; break;
            case SCANNING: polarLine = "Polar automatic connection: scanning"; break;
            case CONNECTING: polarLine = "Polar automatic connection: connecting"; break;
            case MULTIPLE: polarLine = "Polar automatic connection: multiple sensors; choose one"; break;
            case NOT_FOUND: polarLine = "Polar automatic connection: no compatible sensor found"; break;
            case FAILED: polarLine = "Polar automatic connection: failed"; break;
            case STALE: polarLine = "Polar automatic connection: stale status"; break;
            default: polarLine = "Polar automatic connection: not yet observed"; break;
        }
        if (!polar.fresh && polar.polar != ExperimentSessionPanelState.Polar.UNKNOWN) {
            polarLine += " (stale)";
        }
        String counts = state.counts.invalid
            ? "Recorded session counts: unavailable (invalid native readback)"
            : state.counts.totalsAvailable
            ? "Completed " + state.counts.completedTotal()
                + " · Stopped early " + state.counts.stoppedEarlyTotal()
            : "Recorded session counts: unavailable";
        if (state.counts.perConditionAvailable) {
            counts += " · Per condition: completed " + state.counts.completedOne + "/"
                + state.counts.completedTwo + ", stopped early " + state.counts.stoppedEarlyOne
                + "/" + state.counts.stoppedEarlyTwo;
            long unclassifiedCompleted = Math.max(
                0L,
                state.counts.completedTotal()
                    - state.counts.completedOne - state.counts.completedTwo
            );
            long unclassifiedStopped = Math.max(
                0L,
                state.counts.stoppedEarlyTotal()
                    - state.counts.stoppedEarlyOne - state.counts.stoppedEarlyTwo
            );
            if (unclassifiedCompleted > 0L || unclassifiedStopped > 0L) {
                counts += " · Unclassified/recovered: completed " + unclassifiedCompleted
                    + ", stopped early " + unclassifiedStopped;
            }
        } else {
            counts += " · Per-condition breakdown unavailable from native readback";
        }
        if (state.counts.errors > 0L) {
            counts += " · Recording errors " + state.counts.errors;
        }
        boolean saving = state.phase == ExperimentSessionPanelState.Phase.SAVING;
        String recovery = "awaiting-native-readback".equals(state.storageStatus)
                || "unavailable".equals(state.storageStatus)
            ? "status unavailable" : (state.recovery ? "required" : "none");
        String status = saving ? "Saving…"
            : "Recording: " + (state.recording ? "active" : "idle")
                + " · Recovery: " + recovery
                + " · Storage: " + state.storageStatus
                + " · Soft kiosk requested: " + (state.kioskRequested ? "yes (not yet enforced)" : "no");
        boolean polarOk = polar.bluetooth == ExperimentSessionPanelState.Bluetooth.ON
            && polar.polar == ExperimentSessionPanelState.Polar.CONNECTED && polar.fresh;
        boolean fallback = !polarOk;
        boolean startEnabled = !state.hasActiveSession()
            && state.phase != ExperimentSessionPanelState.Phase.ERROR
            && state.pendingOperationId.isEmpty();
        return new ViewState(bluetooth, polarLine, counts, status, fallback, startEnabled, saving);
    }
}
