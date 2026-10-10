# Build-selected Java composition; no assets, device effects or admission authority.
function New-NativeRendererAppStatusBridgeSource {
    [CmdletBinding()]
    param([switch]$Selected)
    $body = if ($Selected) {
@'
    private static ExperimentSessionAppStatusOwner owner;
    static synchronized void prepare(android.content.Context context) {
        if (owner != null) return;
        final android.content.Context appContext = context.getApplicationContext();
        ControlPanelActivity.prepareExperimentSessionAppOwner(appContext);
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        owner = new ExperimentSessionAppStatusOwner(
            new ExperimentSessionAppStatusOwner.Scheduler() {
                public void post(Runnable action) { main.post(action); }
                public void later(Runnable action, long delay) { main.postDelayed(action, delay); }
            }, new ExperimentSessionAppStatusOwner.Poller() {
                public void opened(long identity) { ExperimentSessionAndroidShell.process().openStatusObserver(identity); }
                public void closed(long identity) { ExperimentSessionAndroidShell.process().closeStatusObserver(identity); }
                public void read(final java.util.function.Consumer<ExperimentSessionAppStatusOwner.Observation> callback) {
                    ExperimentSessionAndroidShell.process().observeStatus(
                        new java.util.function.Consumer<ExperimentSessionAndroidShell.SessionReadback>() {
                            public void accept(ExperimentSessionAndroidShell.SessionReadback value) {
                                callback.accept(value == null ? null : new ExperimentSessionAppStatusOwner.Observation(
                                    value.receipt, value.runtimeEpoch, value.statusReadCompletedNanos,
                                    "accepted".equals(value.commandStatus), "ready".equals(value.initializationStatus),
                                    value.receipt != null && "closed".equals(value.receipt.phase),
                                    "session-worker-closed".equals(value.statusResponseReason)));
                            }
                        });
                }
            }, new ExperimentSessionAppStatusOwner.Clock() {
                public long nowNanos() { return android.os.SystemClock.elapsedRealtimeNanos(); }
            }, new ExperimentSessionHubLifetime.Factory() {
                public ExperimentSessionHubLifetime.Driver create(long epoch,
                        java.util.function.Supplier<ExperimentSessionPanelCoordinator.NativeStatusSnapshot> source) {
                    return new ExperimentSessionHubSurfaceClient(appContext, "experimenter-status", epoch, source);
                }
            }, new java.util.function.Consumer<String>() {
                public void accept(String marker) {
                    android.util.Log.i("RQNativeRenderer",
                        "RUSTY_QUEST_NATIVE_RENDERER channel=hub-status " + marker);
                }
            });
    }
    public static synchronized void changed(long identity, long epoch, boolean live) {
        if (owner == null) return;
        if (live) owner.started(identity, epoch); else owner.ended(identity, epoch);
    }
'@
    } else {
@'
    static void prepare(android.content.Context context) { }
    public static void changed(long identity, long epoch, boolean live) { }
'@
    }
    return "package io.github.mesmerprism.rustyquest.native_renderer;`n/** Selected application composition; feature off is inert. */`npublic final class NativeRendererAppStatusBridge {`n" + $body + "`n}"
}
function Write-NativeRendererJavaSourceResponse {
    param([Parameter(Mandatory)][string[]]$SourceFiles,
        [Parameter(Mandatory)][string]$GeneratedAppStatusBridgePath,
        [Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $GeneratedAppStatusBridgePath -PathType Leaf)) {
        throw 'Generated app-status bridge is required for every native renderer Java compilation.'
    }
    $sources = @(@($SourceFiles) + @($GeneratedAppStatusBridgePath) | Select-Object -Unique)
    $sources | Set-Content -Encoding ASCII -LiteralPath $Path
    return $sources
}
Export-ModuleMember -Function New-NativeRendererAppStatusBridgeSource, Write-NativeRendererJavaSourceResponse
