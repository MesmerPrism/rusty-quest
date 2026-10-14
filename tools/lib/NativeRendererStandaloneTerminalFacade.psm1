function New-NativeRendererStandaloneTerminalFacadeSource {
    @'
    private static ExperimentSessionAndroidShell.LocalTerminalScope standaloneTerminalScope;
    private static StandaloneTerminalExit standaloneTerminalExit;

    static boolean admitStandaloneTerminal(ExperimentSessionAndroidShell.LocalTerminalScope scope) {
        if (scope == null || scope.ownerId <= 0L || scope.runtimeEpoch <= 0L
                || scope.launchEpoch <= 0L || scope.sessionGeneration < 0L
                || !ExperimentSessionOwnerPanelModule.isLocalTerminalScopeCurrent(scope)) return false;
        synchronized (PRESENTATION_LOCK) {
            if (terminalIntentAdmitted) return standaloneTerminalScope == scope;
            terminalIntentAdmitted = true;
            terminalFinishConsumed = false;
            standaloneTerminalScope = scope;
        }
        NativeRendererExperimentLaunchAuthority.invalidatePending();
        return true;
    }

    static void finishStandaloneTerminal(android.app.Activity capturedPanel,
            ExperimentSessionAndroidShell.LocalTerminalScope scope,
            ExperimentSessionAndroidShell.TerminalResult result) {
        if (scope == null || result == null || !result.shouldFinish || !result.saved
                || result.sessionGeneration != scope.sessionGeneration
                || result.operationId == null || !result.operationId.startsWith("panel-save-and-exit-")
                || result.shutdownAckRevision <= 0L
                || !ExperimentSessionOwnerPanelModule.isLocalTerminalScopeCurrent(scope)) return;
        StandaloneTerminalExit continuation;
        synchronized (PRESENTATION_LOCK) {
            if (!terminalIntentAdmitted || standaloneTerminalScope != scope || terminalFinishConsumed) return;
            terminalFinishConsumed = true;
            continuation = new StandaloneTerminalExit(capturedPanel, scope, result);
            standaloneTerminalExit = continuation;
        }
        continuation.callbacks.post(continuation);
    }

    // Deliberately independent of the old View/Presentation attachment. Native
    // teardown retires that producer before this exact completion is observable.
    private static final class StandaloneTerminalExit implements Runnable {
        final java.lang.ref.WeakReference<android.app.Activity> panel;
        final ExperimentSessionAndroidShell.LocalTerminalScope scope;
        final ExperimentSessionAndroidShell.TerminalResult result;
        final android.os.Handler callbacks = new android.os.Handler(android.os.Looper.getMainLooper());
        final long deadline = android.os.SystemClock.elapsedRealtime() + 15_000L;
        boolean requested;
        StandaloneTerminalExit(android.app.Activity activity,
                ExperimentSessionAndroidShell.LocalTerminalScope scope,
                ExperimentSessionAndroidShell.TerminalResult result) {
            panel = new java.lang.ref.WeakReference<>(activity);
            this.scope = scope;
            this.result = result;
        }
        private boolean current() {
            synchronized (PRESENTATION_LOCK) {
                return standaloneTerminalExit == this && standaloneTerminalScope == scope
                    && terminalIntentAdmitted && terminalFinishConsumed;
            }
        }
        public void run() {
            if (!current() || !ExperimentSessionOwnerPanelModule.isLocalTerminalScopeCurrent(scope)) return;
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                fail("retirement-outcome-unknown-no-replay"); return;
            }
            try {
                String wire;
                boolean requestResponse = !requested;
                if (!requested) {
                    // Any exception or unknown response after this point is an
                    // ambiguous dispatch: never send the native request again.
                    requested = true;
                    wire = nativeRequestExperimentSessionRendererRetirement(scope.ownerId,
                        scope.runtimeEpoch, scope.sessionGeneration, result.operationId,
                        result.shutdownAckRevision);
                } else {
                    wire = nativeReadExperimentSessionRendererRetirement(scope.ownerId,
                        scope.runtimeEpoch, scope.sessionGeneration, result.operationId,
                        result.shutdownAckRevision);
                }
                org.json.JSONObject receipt = new org.json.JSONObject(wire);
                if (!"rusty.quest.native_renderer_retirement.v1".equals(receipt.optString("schema", ""))
                        || !exactInteger(receipt, "owner_identity", scope.ownerId)
                        || !exactInteger(receipt, "runtime_epoch", scope.runtimeEpoch)
                        || !exactInteger(receipt, "generation", scope.sessionGeneration)
                        || !result.operationId.equals(receipt.opt("operation_id"))
                        || !exactInteger(receipt, "shutdown_ack_revision", result.shutdownAckRevision)) {
                    fail("retirement-receipt-scope-mismatch"); return;
                }
                String status = receipt.optString("status", "");
                if (!current() || !ExperimentSessionOwnerPanelModule.isLocalTerminalScopeCurrent(scope)) return;
                if ("not-ready".equals(status)) {
                    if (!requestResponse) { fail("retirement-read-cannot-reopen-request"); return; }
                    // The native owner explicitly reports that no request was
                    // admitted yet while its canonical shutdown worker finishes.
                    requested = false;
                    callbacks.postDelayed(this, 100L);
                } else if ("pending".equals(status)) {
                    callbacks.postDelayed(this, 100L);
                } else if ("retired".equals(status)) {
                    android.app.Activity exact = panel.get();
                    if (exact instanceof ControlPanelActivity && !exact.isDestroyed()
                            && !exact.isFinishing()) exact.finishAndRemoveTask();
                    android.util.Log.i("RQNativeRenderer", "channel=session-terminal status=retired"
                        + " runtime_epoch=" + scope.runtimeEpoch
                        + " generation=" + scope.sessionGeneration
                        + " shutdown_ack_revision=" + result.shutdownAckRevision);
                    synchronized (PRESENTATION_LOCK) {
                        if (standaloneTerminalExit == this) standaloneTerminalExit = null;
                    }
                } else {
                    fail("retirement-rejected-or-unknown:" + status);
                }
            } catch (Throwable failure) {
                fail("retirement-outcome-unknown-no-replay:" + failure.getClass().getSimpleName());
            }
        }
        private static boolean exactInteger(org.json.JSONObject receipt, String name, long expected) {
            Object value = receipt.opt(name);
            return (value instanceof Long || value instanceof Integer)
                && ((Number) value).longValue() == expected;
        }
        private void fail(String reason) {
            callbacks.removeCallbacksAndMessages(null);
            android.util.Log.e("RQNativeRenderer", "channel=session-terminal status=failed reason=" + reason);
            // Keep the terminal latch: an unconfirmed retirement cannot become
            // an ordinary mutable app or a successful reusable shutdown.
            synchronized (PRESENTATION_LOCK) {
                if (standaloneTerminalExit == this) standaloneTerminalExit = null;
            }
        }
    }

'@
}
Export-ModuleMember -Function New-NativeRendererStandaloneTerminalFacadeSource
