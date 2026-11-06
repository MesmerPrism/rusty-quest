package io.github.mesmerprism.rustyquest.spatial_worldspace_hotload

import android.os.SystemClock
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.ControllerPose
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import java.util.concurrent.atomic.AtomicLong

/** An immutable copy of the SDK's right-controller acquisition, never a sensor timestamp. */
data class SdkControllerAcquisition(
    val sdkEpoch: Long, val entityId: Long, val sequence: Long,
    val requestedPoseTimeElapsedNs: Long, val acquiredAtElapsedNs: Long,
    val rawFlags: Int, val active: Boolean, val tracked: Boolean,
    val x: Double, val y: Double, val z: Double,
    val qx: Double, val qy: Double, val qz: Double, val qw: Double,
    val rejection: String, val buttonState: Int = 0, val changedButtons: Int = 0,
) {
    val sourceTimestampAvailable: Boolean get() = false
    val rightSecondaryPressed: Boolean get() = active && buttonState and ButtonBits.ButtonB != 0
}

/** Runs after the SDK's ordinary early controller update. Owns no XR action or frame loop. */
@OptIn(SpatialSDKExperimentalAPI::class)
class SdkControllerSamplingSystem(
    private val currentScene: () -> Scene,
    private val currentEpoch: () -> Long,
    private val publish: (SdkControllerAcquisition) -> Unit,
) : SystemBase() {
    private data class Side(val id: Long, val active: Boolean, val buttons: Int, val changed: Int)
    companion object {
        private val sampleSequence = AtomicLong(0)
        private const val MAX_QUERY_NS = 250_000_000L
    }

    private fun rightSide(): Side? {
        var match: Side? = null
        var matches = 0
        for (entity in Query.where { has(Controller.id, AvatarAttachment.id) }.filter { isLocal() }.eval()) {
            val attachment = entity.tryGetComponent<AvatarAttachment>() ?: continue
            try {
                if (attachment.type != "right_controller") continue
                val controller = entity.tryGetComponent<Controller>() ?: continue
                try {
                    if (controller.type != ControllerType.CONTROLLER) continue
                    matches++
                    if (matches > 1) return null
                    match = Side(entity.id, controller.isActive, controller.buttonState, controller.changedButtons)
                } finally { controller.recycle() }
            } finally { attachment.recycle() }
        }
        return match
    }

    override fun execute() {
        val epoch = currentEpoch()
        if (epoch <= 0 || epoch == Long.MAX_VALUE) return
        val sequence = sampleSequence.updateAndGet { if (it == Long.MAX_VALUE) it else it + 1 }
        if (sequence == Long.MAX_VALUE) {
            // Saturated identity must never wrap or revive an older sample.
            publish(SdkControllerAcquisition(epoch, 0, sequence, 0, SystemClock.elapsedRealtimeNanos(), 0,
                false, false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, "sequence_exhausted"))
            return
        }
        var side: Side? = null
        var requested = SystemClock.elapsedRealtimeNanos()
        var flags = 0
        var x = 0.0; var y = 0.0; var z = 0.0
        var qx = 0.0; var qy = 0.0; var qz = 0.0; var qw = 1.0
        var reason = "missing_or_ambiguous_right_controller"
        try {
            side = rightSide()
            if (side != null && side.id != 0L && side.active) {
                requested = SystemClock.elapsedRealtimeNanos()
                val located = currentScene().getControllerPoseAtTime(false, requested)
                flags = located.flags
                // SDK scene +Z basis to the canonical right-handed local basis.
                x = located.pose.t.x.toDouble(); y = located.pose.t.y.toDouble(); z = -located.pose.t.z.toDouble()
                qx = -located.pose.q.x.toDouble(); qy = -located.pose.q.y.toDouble()
                qz = located.pose.q.z.toDouble(); qw = located.pose.q.w.toDouble()
                val required = ControllerPose.ValidBits or ControllerPose.TrackedBits
                reason = if (flags and required != required) "sdk_pose_not_valid_and_tracked"
                    else if (!listOf(x, y, z, qx, qy, qz, qw).all { it.isFinite() }) "nonfinite_pose"
                    else if (qx*qx+qy*qy+qz*qz+qw*qw < 0.000001) "invalid_orientation"
                    else ""
            } else if (side != null) reason = "right_controller_inactive_or_zero_identity"
        } catch (_: Exception) { reason = "sdk_acquisition_failed" }
        val acquired = SystemClock.elapsedRealtimeNanos()
        if (acquired < requested || acquired - requested > MAX_QUERY_NS) reason = "sdk_query_time_unusable"
        if (currentEpoch() != epoch) return
        publish(SdkControllerAcquisition(epoch, side?.id ?: 0, sequence, requested, acquired, flags,
            side?.active == true, reason.isEmpty(), x, y, z, qx, qy, qz, qw, reason, side?.buttons ?: 0, side?.changed ?: 0))
    }
}
