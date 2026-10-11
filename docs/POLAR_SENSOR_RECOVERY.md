# Polar connection and stream recovery

A successful app-owned connection enables continued connection intent for that
exact sensor instance. Transport failure retains this intent. Discovery during
recovery may not substitute another sensor, even when it is the only candidate.
Each connection attempt times out after 15 seconds; retries wait 2 seconds within
a two-attempt cycle and 10 seconds between cycles. Cycles continue while the owner
and connection intent remain active. Permission or radio unavailability does not
authorize changing those settings.

Intentional Disconnect and owner shutdown cancel connection and stream recovery.
Panel recreation cannot rearm an intentional disconnect; an explicit successful
Connect or a genuinely new process owner can begin a new connection intent.
Closing the panel only detaches presentation. Stop PMD cancels the selected
stream's resume intent; Stop all PMD cancels both PMD streams while keeping the
HR/RR connection intent. A stop before the first connection also overrides the
automatic PMD default. A new explicit sensor selection starts a new stream intent.

The retained stream intent expresses requested acquisition, not evidence of a
running stream. A failed START remains inactive unless actual incoming data proves
otherwise. Its requested intent remains available for a later connection recovery;
an explicit stop cancels it. ACC and ECG requests can both be restored after the
same sensor reconnects. Subscription and command acknowledgements remain distinct
from fresh samples.

If Stop arrives after START dispatch, its actual START acknowledgement is retained.
The owner then dispatches one STOP when the preceding write completes. The delayed
operation checks the current GATT owner and the selected stream's intent revision.
An ineffective or rejected STOP is unconfirmed and is not automatically replayed.
A successful matching STOP acknowledgement, rather than write completion alone,
marks the stream stopped.

The app-owned v2 status adds `connection_recovery_requested`,
`acc_resume_requested`, and `ecg_resume_requested`. These are intent diagnostics;
the existing running/data fields retain their effective meaning. The Polar
operator and status sampler consume named fields and allow additional fields.
The production compact phone-control status encoding remains unchanged.

Run `tools/checks/Test-NativeRendererExperimentPanel.ps1` for Android compilation
and production policy tests. Run `python tools/checks/test_polar_recovery_callbacks.py`
for extracted production callback, timeout, discovery, stop and restore decisions
with inert Android transport boundaries. These host controls do not qualify range,
physical reconnection, sample recovery, or simultaneous Bluetooth links; those need
separate device observations.

The selected Polar composition requests automatic acquisition from the exact admitted
NativeActivity MAIN/LAUNCHER cold launch, on the Android main thread. It does not
wait for a 2D experimenter panel or the selected breath-input source. Application
preparation, provider reads, internal routes and recreation do not create this
request. Missing permissions remain an explicit prerequisite; the visible panel
continues to own permission prompts and manual Connect remains a fallback. The
existing process-owner intentional Disconnect suppression also applies to this
request. Host controls qualify routing, not discovery or sensor timing.
