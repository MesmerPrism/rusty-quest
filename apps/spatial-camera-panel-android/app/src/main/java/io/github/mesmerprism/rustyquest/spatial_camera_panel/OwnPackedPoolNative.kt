package io.github.mesmerprism.rustyquest.spatial_camera_panel

import io.github.mesmerprism.rustyquest.media.PackedStereoPoolExecutor
import io.github.mesmerprism.rustyquest.media.PackedStereoEncoderInput
import io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex.EmbeddedDuplexNative

/** Adapter injected only after the native capture owner installs its admitted hold contract. */
class OwnPackedPoolNative(private val onEncoderLease: (EncoderLease) -> Unit) : PackedStereoPoolExecutor {
    override fun createForCurrentContext(packedWidth: Int, height: Int): PackedStereoPoolExecutor.Pool {
        val id = nativeCreate(packedWidth, height)
        check(id > 0) { "native capture pool unavailable" }
        val actor = Thread.currentThread()
        return object : PackedStereoPoolExecutor.Pool {
            private fun onActor() { check(Thread.currentThread() === actor) { "wrong capture actor" } }
            private fun ticket(write: PackedStereoPoolExecutor.Write) {
                onActor(); check(write.poolGeneration == id) { "stale pool ticket" }
            }
            override fun beginWrite(): PackedStereoPoolExecutor.Write? {
                onActor()
                val words = nativeBegin(id)
                if (words.isEmpty()) return null
                check(words.size == 3 && words[0] == id && words[2] <= Int.MAX_VALUE)
                return PackedStereoPoolExecutor.Write(words[0], words[1], words[2].toInt())
            }
            override fun finishWrite(write: PackedStereoPoolExecutor.Write, pair: PackedStereoPoolExecutor.PairIdentity) {
                ticket(write)
                nativeFinish(id, write.slotSerial, write.framebuffer, pair.pairId, pair.leftFrame,
                    pair.rightFrame, pair.leftSensorNs, pair.rightSensorNs)
            }
            override fun quarantineWrite(write: PackedStereoPoolExecutor.Write, reason: String) {
                ticket(write); nativeQuarantine(id, write.slotSerial, write.framebuffer)
            }
            override fun pollReady() {
                onActor()
                val words = nativePoll(id)
                if (words.isEmpty()) return
                check(words.size == 6)
                val lease = EncoderLease(words[0], PackedStereoPoolExecutor.PairIdentity(
                    words[1], words[2], words[3], words[4], words[5]))
                try { onEncoderLease(lease) } catch (failure: Throwable) {
                    lease.close(); throw failure
                }
            }
            override fun stopAccepting() { onActor(); nativeStop(id) }
            override fun retireStopped(): Boolean { onActor(); return nativeRetire(id) }
        }
    }
    class EncoderLease internal constructor(private val token: Long,
        val pair: PackedStereoPoolExecutor.PairIdentity) : AutoCloseable, PackedStereoEncoderInput {
        override fun pair(): PackedStereoPoolExecutor.PairIdentity = pair
        private var worker: Thread? = null
        @Synchronized override fun importOnCurrentContext(): PackedStereoEncoderInput.Imported {
            check(worker == null) { "encoder token replay" }
            worker = Thread.currentThread()
            val words = nativeImportEncoder(token)
            check(words.size == 3 && words.all { it in 1..Int.MAX_VALUE.toLong() })
            return PackedStereoEncoderInput.Imported(words[0].toInt(), words[1].toInt(), words[2].toInt())
        }
        private fun onWorker() { check(worker === Thread.currentThread()) { "wrong encoder worker" } }
        override fun finishAfterSubmission() { onWorker(); nativeFinishEncoder(token) }
        override fun pollRetired(): Boolean { onWorker(); return nativePollEncoder(token) }
        // Imported input stays native-owned until actual fence retirement.
        @Synchronized override fun releaseUnsubmitted() { if (worker == null) nativeReleaseEncoder(token) }
        override fun close() { releaseUnsubmitted() }
    }
    override fun ownImageFresh(): Boolean = nativeOwnImageFresh()
    override fun awaitCameraOwnership() {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!EmbeddedDuplexNative.localCameraQuiescent()) {
            check(System.nanoTime() < deadline) { "old local camera ownership remains Pending" }
            Thread.sleep(10)
        }
    }
    companion object {
        init { System.loadLibrary("spatial_camera_panel_native_receipt") }
        @JvmStatic external fun concurrentPeerAdmission(routeGeneration: Long, launchChallenge: Long, surfaceGeneration: Long): LongArray
        @JvmStatic external fun captureConfigured(): Boolean
        @JvmStatic external fun captureRouteSelected(): Boolean
        @JvmStatic external fun nativeRetirePeerSource(route: Long, decoder: Long, reader: Long): Boolean
        @JvmStatic private external fun nativeOwnImageFresh(): Boolean
        @JvmStatic private external fun nativeCreate(width: Int, height: Int): Long
        @JvmStatic private external fun nativeBegin(id: Long): LongArray
        @JvmStatic private external fun nativeFinish(id: Long, serial: Long, fbo: Int, pair: Long,
            left: Long, right: Long, leftNs: Long, rightNs: Long)
        @JvmStatic private external fun nativeQuarantine(id: Long, serial: Long, fbo: Int)
        @JvmStatic private external fun nativeStop(id: Long)
        @JvmStatic private external fun nativePoll(id: Long): LongArray
        @JvmStatic private external fun nativeRetire(id: Long): Boolean
        @JvmStatic private external fun nativeImportEncoder(token: Long): LongArray
        @JvmStatic private external fun nativeFinishEncoder(token: Long)
        @JvmStatic private external fun nativePollEncoder(token: Long): Boolean
        @JvmStatic private external fun nativeReleaseEncoder(token: Long)
    }
}
