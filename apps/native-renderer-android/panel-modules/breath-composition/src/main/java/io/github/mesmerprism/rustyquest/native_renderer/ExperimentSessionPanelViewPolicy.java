package io.github.mesmerprism.rustyquest.native_renderer;

/** Pure rendering policy for labels, button admission, and honest unavailable states. */
final class ExperimentSessionPanelViewPolicy {
    enum Tone { READY, WAITING, ATTENTION, NEUTRAL }
    enum Page {
        PREPARE("Prepare"), CONTROLS("Controls"), CONDITION("Choose condition"), SESSION("Session");
        final String title;
        Page(String title) { this.title = title; }
    }

    /** Presentation only: status refreshes never advance a page or reset its scroll. */
    static final class Navigation {
        private Page page = Page.PREPARE;
        private final int[] scroll = new int[Page.values().length];

        Page page() { return page; }
        void select(Page next) { if (next != null) page = next; }
        void rememberScroll(int y) { rememberScroll(page, y); }
        void rememberScroll(Page rendered, int y) { scroll[rendered.ordinal()] = Math.max(0, y); }
        int scrollY() { return scroll[page.ordinal()]; }
        int[] savedScroll() { return scroll.clone(); }
        void restore(String token, int[] saved) {
            try { page = Page.valueOf(token); }
            catch (RuntimeException invalid) { page = Page.PREPARE; }
            for (int i = 0; i < scroll.length; i++) {
                scroll[i] = saved != null && i < saved.length ? Math.max(0, saved[i]) : 0;
            }
        }
    }

    static Tone bluetoothTone(ExperimentSessionPanelState state) {
        if (state.polar.bluetooth == ExperimentSessionPanelState.Bluetooth.ON) return Tone.READY;
        if (state.polar.bluetooth == ExperimentSessionPanelState.Bluetooth.UNKNOWN
                || state.polar.bluetooth == ExperimentSessionPanelState.Bluetooth.TURNING) return Tone.WAITING;
        return Tone.ATTENTION;
    }

    static Tone polarTone(ExperimentSessionPanelState state) {
        if (state.polar.polar == ExperimentSessionPanelState.Polar.CONNECTED
                && state.polar.fresh && bluetoothTone(state) == Tone.READY) return Tone.READY;
        if (state.polar.polar == ExperimentSessionPanelState.Polar.SCANNING
                || state.polar.polar == ExperimentSessionPanelState.Polar.CONNECTING
                || state.polar.polar == ExperimentSessionPanelState.Polar.UNKNOWN) return Tone.WAITING;
        return Tone.ATTENTION;
    }

    static Tone storageTone(ExperimentSessionPanelState state) {
        if (state.recovery || state.phase == ExperimentSessionPanelState.Phase.ERROR) return Tone.ATTENTION;
        return "ready".equals(state.storageStatus) ? Tone.READY : Tone.WAITING;
    }

    static final class ReadinessCard {
        final String label;
        final String detail;
        final Tone tone;
        final boolean showAction;

        ReadinessCard(String label, String detail, Tone tone, boolean showAction) {
            this.label = label;
            this.detail = detail;
            this.tone = tone;
            this.showAction = showAction;
        }
    }

    static ReadinessCard storageCard(ExperimentSessionPanelState state) {
        Tone tone = storageTone(state);
        if (tone == Tone.READY) {
            return new ReadinessCard("✓ Recording ready", "", tone, false);
        }
        if (tone == Tone.ATTENTION || "error".equals(state.storageStatus)) {
            return new ReadinessCard(
                "! Recording unavailable",
                "Restart the app before the study.",
                Tone.ATTENTION,
                false
            );
        }
        return new ReadinessCard("… Checking recording storage", "", Tone.WAITING, false);
    }

    static ReadinessCard kioskCard(String status) {
        if ("ready".equals(status)) {
            return new ReadinessCard("✓ Background return ready", "", Tone.READY, false);
        }
        if ("starting".equals(status)) {
            return new ReadinessCard("… Starting background return", "", Tone.WAITING, false);
        }
        if ("permission-required".equals(status)) {
            return new ReadinessCard(
                "! Permission needed",
                "Allow display over other apps.",
                Tone.ATTENTION,
                true
            );
        }
        if ("ending".equals(status)) {
            return new ReadinessCard("… Saving and closing", "", Tone.WAITING, false);
        }
        return new ReadinessCard(
            "! Background return unavailable",
            "Restart the app before the study.",
            Tone.ATTENTION,
            false
        );
    }

    static String stageTitle(ExperimentSessionPanelState state) {
        String phase = state.phase.name();
        if ("ARMED".equals(phase)) return "Armed · ready for the experimenter";
        if ("PAUSED".equals(phase)) return "Paused · resume when ready";
        if ("RUNNING".equals(phase) || "RECORDING".equals(phase)) return "Running · session in progress";
        if ("ARMING".equals(phase) || "STARTING".equals(phase)) return "Preparing the selected condition…";
        if ("FINALIZING".equals(phase) || "SAVING".equals(phase)) return "Saving…";
        if (state.recovery || "RECOVERY".equals(phase)) return "Recovery needs attention";
        if ("ERROR".equals(phase)) return state.recording
            ? "Session needs attention" : "Condition was not armed";
        if ("UNAVAILABLE".equals(phase)) return "Session status unavailable";
        return "No active session";
    }

    static String stageInstruction(ExperimentSessionPanelState state) {
        String phase = state.phase.name();
        if ("ARMED".equals(phase)) return "Fit the headset and verify the participant is settled. The experimenter then holds Right Grip + A for about 0.75 seconds to start the official run.";
        if ("PAUSED".equals(phase)) return "Hold Right Grip + A for about 0.75 seconds to resume. Press B three times without grip to open or close this menu without ending the run.";
        if ("RUNNING".equals(phase) || "RECORDING".equals(phase)) return "Hold Right Grip + B for about 0.75 seconds to pause. Audio ending does not finish the session. Press B three times without grip to open this menu; use Save and exit only when the run is complete.";
        if ("ARMING".equals(phase) || "STARTING".equals(phase)) return "Wait for the condition to be armed before using the start gesture.";
        if ("FINALIZING".equals(phase) || "SAVING".equals(phase)) return "Wait for the recording to finish saving before preparing another run.";
        if ("ERROR".equals(phase) && state.recording) return "Audio entered a technical hold. Do not use resume. Use Save and exit to preserve the incomplete run, then restart the app and re-arm it.";
        if ("ERROR".equals(phase)) return "Go back to Choose condition and try again. If the condition is rejected again, restart the app before fitting the headset.";
        if (state.recovery || "RECOVERY".equals(phase)) return "Review the session and storage status before preparing another run.";
        return "Review the saved-session totals, then prepare the next run.";
    }

    static Tone stageTone(ExperimentSessionPanelState state) {
        String phase = state.phase.name();
        if (state.recovery || "ERROR".equals(phase) || "RECOVERY".equals(phase)) return Tone.ATTENTION;
        if ("ARMED".equals(phase) || "RUNNING".equals(phase) || "RECORDING".equals(phase)) return Tone.READY;
        return "IDLE".equals(phase) ? Tone.NEUTRAL : Tone.WAITING;
    }

    static boolean canReturnToImmersive(ExperimentSessionPanelState state) {
        return state.phase == ExperimentSessionPanelState.Phase.ARMED
            || state.phase == ExperimentSessionPanelState.Phase.RUNNING
            || state.phase == ExperimentSessionPanelState.Phase.PAUSED
            || state.phase == ExperimentSessionPanelState.Phase.RECORDING
            || (state.phase == ExperimentSessionPanelState.Phase.ERROR && state.recording);
    }
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
            case LOCATION_SERVICES_DISABLED:
                polarLine = "Polar automatic connection: location services must be enabled";
                break;
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
        boolean saving = state.phase == ExperimentSessionPanelState.Phase.SAVING
            || "FINALIZING".equals(state.phase.name());
        String recovery = "awaiting-native-readback".equals(state.storageStatus)
                || "unavailable".equals(state.storageStatus)
            ? "status unavailable" : (state.recovery ? "required" : "none");
        String condition = "condition-a".equals(state.activeCondition) ? "Condition 1 · "
            : "condition-b".equals(state.activeCondition) ? "Condition 2 · " : "";
        String status = state.phase == ExperimentSessionPanelState.Phase.ERROR && !state.recording
            ? condition + "Not armed · " + emptyAs(state.detail, "Native arm command rejected.")
            : condition + (saving ? "Saving…"
                : "Recording: " + (state.recording ? "active" : "idle")
                    + " · Recovery: " + recovery
                    + " · Storage: " + state.storageStatus);
        boolean polarOk = polar.bluetooth == ExperimentSessionPanelState.Bluetooth.ON
            && polar.polar == ExperimentSessionPanelState.Polar.CONNECTED && polar.fresh;
        boolean fallback = !polarOk;
        boolean startEnabled = !saving && !state.hasActiveSession()
            && !(state.phase == ExperimentSessionPanelState.Phase.ERROR && state.recording)
            && state.phase != ExperimentSessionPanelState.Phase.UNAVAILABLE
            && state.pendingOperationId.isEmpty();
        return new ViewState(bluetooth, polarLine, counts, status, fallback, startEnabled, saving);
    }

    private static String emptyAs(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
