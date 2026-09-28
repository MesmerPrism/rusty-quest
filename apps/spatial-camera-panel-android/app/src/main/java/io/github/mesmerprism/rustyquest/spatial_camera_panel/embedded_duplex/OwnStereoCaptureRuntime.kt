package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import android.content.Context
import android.os.Looper
import io.github.mesmerprism.rustyquest.media.PackedStereoCaptureOwner
import io.github.mesmerprism.rustyquest.spatial_camera_panel.OwnPackedPoolNative
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Process-owned capture lifecycle. Peer subscription/cleanup never owns this owner. */
class OwnStereoCaptureRuntime private constructor(context: Context) {
    data class Configuration(val eyeWidth: Int, val eyeHeight: Int, val frameRate: Int,
        val leftCamera: String, val rightCamera: String, val maxPairDeltaNs: Long)
    enum class Phase { Idle, Starting, Live, StopPending }
    private val application = context.applicationContext
    private val control = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "rusty-own-stereo-capture-control")
    }
    private val lock = Any()
    @Volatile private var phase = Phase.Idle
    @Volatile private var failure: Throwable? = null
    private var configuration: Configuration? = null
    private var capture: PackedStereoCaptureOwner? = null
    private var lastCleanupStatus: PackedStereoCaptureOwner.CleanupStatus? = null
    @Volatile private var captureEverOwned = false
    private var startup: CompletableFuture<PackedStereoCaptureOwner>? = null

    init {
        control.scheduleWithFixedDelay({
            val owner = synchronized(lock) { if (phase == Phase.StopPending) capture else null }
            if (owner != null) try {
                if (owner.pollStopped()) synchronized(lock) {
                    if (capture === owner) {
                        lastCleanupStatus = owner.cleanupStatus()
                        capture = null; startup = null; phase = Phase.Idle
                    }
                }
            } catch (error: Throwable) { failure = error }
        }, 50, 50, TimeUnit.MILLISECONDS)
    }

    /** Called only from the process command lane using accepted native source configuration. */
    fun startAccepted(configuration: Configuration): PackedStereoCaptureOwner {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Own capture startup must not block Activity" }
        val ready = synchronized(lock) {
            check(phase != Phase.StopPending) { "prior Own capture remains physical Pending" }
            if (capture != null) {
                check(this.configuration == configuration) { "Own capture configuration changed" }
                checkNotNull(startup)
            } else {
                check(OwnPackedPoolNative.captureConfigured()) { "renderer Own hold contract unavailable" }
                lateinit var owner: PackedStereoCaptureOwner
                val pool = OwnPackedPoolNative { lease -> owner.offerEncoderFrame(lease) }
                owner = PackedStereoCaptureOwner(application, configuration.eyeWidth,
                    configuration.eyeHeight, configuration.frameRate, configuration.leftCamera,
                    configuration.rightCamera, configuration.maxPairDeltaNs, pool)
                val next = CompletableFuture<PackedStereoCaptureOwner>()
                this.configuration = configuration; capture = owner; startup = next; captureEverOwned = true
                lastCleanupStatus = null
                phase = Phase.Starting; failure = null
                control.execute {
                    try {
                        owner.start()
                        synchronized(lock) {
                            if (phase == Phase.Starting) phase = Phase.Live
                            else throw IllegalStateException("Own capture stopped during startup")
                        }
                        next.complete(owner)
                    } catch (error: Throwable) {
                        owner.requestStop()
                        synchronized(lock) { failure = error; phase = Phase.StopPending }
                        next.completeExceptionally(error)
                    }
                }
                next
            }
        }
        // Expiry is caller Pending; the registry retains the actual startup and physical owner.
        return ready.get(15, TimeUnit.SECONDS)
    }

    fun requestStopOwn() {
        val owner = synchronized(lock) {
            if (capture == null) return
            phase = Phase.StopPending; capture
        }
        control.execute { owner?.requestStop() }
    }
    fun pollStopped(): Boolean = phase == Phase.Idle
    fun physicalCleanupState(): String = if (!captureEverOwned) "unknown" else if (phase == Phase.Idle) "terminal" else "pending"
    fun cleanupStatus(): PackedStereoCaptureOwner.CleanupStatus? = synchronized(lock) {
        capture?.cleanupStatus() ?: lastCleanupStatus
    }
    fun phase(): Phase = phase
    fun failure(): Throwable? = failure
    fun retainedCapture(): PackedStereoCaptureOwner? = synchronized(lock) {
        if (phase == Phase.Live) capture else null
    }
    companion object {
        private var process: OwnStereoCaptureRuntime? = null
        @JvmStatic @Synchronized fun currentForApplication(): OwnStereoCaptureRuntime? = process
        @JvmStatic @Synchronized fun forApplication(context: Context): OwnStereoCaptureRuntime {
            return process ?: OwnStereoCaptureRuntime(context).also { process = it }
        }
    }
}
