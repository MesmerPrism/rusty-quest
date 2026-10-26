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
    @Volatile private var cleanupObserver: CleanupObserverRegistration? = null

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
            // Observe the same held whole-close lifetime, never dispatch Stop again.
            if (phase == Phase.Idle) cleanupObserver?.invokeObserved()
        }, 50, 50, TimeUnit.MILLISECONDS)
    }

    /** Called only from the process command lane using accepted native source configuration. */
    fun startAccepted(configuration: Configuration): PackedStereoCaptureOwner =
        startAcceptedAsync(configuration).get(15, TimeUnit.SECONDS)

    fun startAcceptedAsync(configuration: Configuration): CompletableFuture<PackedStereoCaptureOwner> {
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
        // Same actual startup is observed; the process command lane need not wait here.
        return ready
    }

    fun requestStopOwn() {
        val owner = synchronized(lock) {
            if (capture == null) return
            phase = Phase.StopPending; capture
        }
        // requestStop only fences/posts to the actual camera/GL owners. It must not
        // queue behind owner.start while that same control worker awaits callbacks.
        owner?.requestStop()
    }
    fun pollStopped(): Boolean = phase == Phase.Idle
    class CleanupObserverRegistration internal constructor(private var callback: Runnable?) : AutoCloseable {
        private val gate = Any()
        private var open = true
        private var entered = 0
        private val drained = CompletableFuture<Void>()
        @Volatile private var callbackFailure: Throwable? = null
        internal fun invokeObserved() {
            val action = synchronized(gate) {
                if (!open) return
                entered++
                checkNotNull(callback)
            }
            try { action.run() }
            catch (failure: Throwable) { callbackFailure = failure }
            finally { synchronized(gate) {
                entered--
                if (!open && entered == 0) { callback = null; drained.complete(null) }
            } }
        }
        fun unregister(): CompletableFuture<Void> {
            synchronized(gate) {
                open = false
                if (entered == 0) { callback = null; drained.complete(null) }
            }
            return drained
        }
        fun failure(): Throwable? = callbackFailure
        override fun close() { unregister() } // Nonblocking. Caller still joins drained then its own lane marker.
    }
    fun observePhysicalCleanup(observer: Runnable): CleanupObserverRegistration {
        synchronized(lock) {
            check(cleanupObserver == null) { "prior whole-close observer retained" }
            return CleanupObserverRegistration(observer).also { cleanupObserver = it }
        }
    }
    fun unregisterPhysicalCleanup(registration: CleanupObserverRegistration): CompletableFuture<Void> {
        // The producer fences entry before clearing the slot, asynchronously joins
        // actually entered callbacks, then the host posts its command-lane drain.
        val drained = registration.unregister()
        synchronized(lock) { if (cleanupObserver === registration) cleanupObserver = null }
        return drained
    }
    fun whenFresh(expected: PackedStereoCaptureOwner): CompletableFuture<Void> =
        whenFresh(expected,java.util.function.BooleanSupplier { true })
    fun whenFresh(expected: PackedStereoCaptureOwner, ownerAlive: java.util.function.BooleanSupplier): CompletableFuture<Void> {
        val result = CompletableFuture<Void>()
        val warned = java.util.concurrent.atomic.AtomicBoolean()
        val began = System.nanoTime()
        val poll = object : Runnable {
            override fun run() {
                if (result.isDone) return
                try {
                    val current = ownerAlive.asBoolean && synchronized(lock) { capture === expected && phase == Phase.Live }
                    check(current) { "Own freshness owner stopped or superseded" }
                    failure?.let { throw IllegalStateException("Own capture failed", it) }
                    expected.failure()?.let { throw IllegalStateException("Own camera/compositor failed",it) }
                    if (expected.fresh()) { result.complete(null); return }
                    if (System.nanoTime() - began >= TimeUnit.SECONDS.toNanos(10) && warned.compareAndSet(false,true))
                        android.util.Log.w("RQSpatialCameraPanel","Own freshness remains Pending")
                    control.schedule(this, 10, TimeUnit.MILLISECONDS)
                } catch (failure: Throwable) { result.completeExceptionally(failure) }
            }
        }
        control.execute(poll)
        return result
    }
    /** Construction-owned absence and actual retained cleanup, never null-as-success. */
    fun physicalRetirementObservation(): org.json.JSONObject = synchronized(lock) {
        val status = capture?.cleanupStatus() ?: lastCleanupStatus
        org.json.JSONObject().put("phase",phase.name).put("capture_ever_owned",captureEverOwned)
            .put("state",if(!captureEverOwned && capture==null && startup==null && phase==Phase.Idle) "not_created"
                else if(captureEverOwned && capture==null && phase==Phase.Idle && status!=null &&
                    status.stopRequested && status.startupSettled && !status.cameraThreadAlive &&
                    !status.compositorThreadAlive && !status.compositorCleanupRejected &&
                    status.leftCallbackBarrier in setOf("NOT_CREATED","TERMINAL") &&
                    status.rightCallbackBarrier in setOf("NOT_CREATED","TERMINAL") &&
                    ((status.compositorBarrier=="NOT_CREATED" && !status.compositorPhysicallyRetired) ||
                        (status.compositorBarrier=="TERMINAL" && status.compositorPhysicallyRetired))) "retired"
                else if(phase==Phase.Live && capture!=null && failure==null) "live_independent" else "pending")
    }
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
