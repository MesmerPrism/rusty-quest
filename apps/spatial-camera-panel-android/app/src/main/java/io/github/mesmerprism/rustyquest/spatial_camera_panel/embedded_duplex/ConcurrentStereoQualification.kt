package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex

import io.github.mesmerprism.rustyquest.spatial_camera_panel.StereoBankControls
import io.github.mesmerprism.rustyquest.spatial_camera_panel.StereoBankControlPolicy
import io.github.mesmerprism.rustyquest.spatial_camera_panel.StereoImageOrigin
import io.github.mesmerprism.rustyquest.spatial_camera_panel.StereoGuideOrigin
import org.json.JSONArray
import org.json.JSONObject

/** Called only by the serialized process owner after checking its live fence. */
internal object ConcurrentStereoQualification {
    private var challenge: String? = null
    private var processEpoch: String? = null
    private var arm: Long = 0
    private var nativeProcess: Long = 0
    private var runtimeConfig: String? = null
    private var featureLock: String? = null
    private var installedApk: String? = null
    private var armEntryElapsedNs: Long = 0
    private var armExitElapsedNs: Long = 0

    @JvmStatic fun arm(challenge: String, processEpoch: String, runtimeSha: String,
        featureSha: String, apkSha: String): String {
        val armEntry = android.os.SystemClock.elapsedRealtimeNanos()
        val next = StereoBankControls.armConcurrentQualification(challenge)
        require(next != 0L) { "qualification unavailable" }
        val snapshot = StereoBankControls.concurrentQualification()
        val bits = StereoBankControls.challengeWords(challenge)
        require(snapshot[2] != 0L && snapshot[3] == bits[0] && snapshot[4] == bits[1] &&
            snapshot[5] == next && snapshot[6] == 1L) { "native arm lineage differs" }
        this.challenge = challenge
        this.processEpoch = processEpoch
        this.arm = next
        this.nativeProcess = snapshot[2]
        this.runtimeConfig = runtimeSha
        this.featureLock = featureSha
        this.installedApk = apkSha
        this.armEntryElapsedNs = armEntry
        this.armExitElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()
        return receipt("arm", challenge, processEpoch, runtimeSha, featureSha, apkSha, snapshot)
    }

    @JvmStatic fun status(challenge: String, processEpoch: String, runtimeSha: String,
        featureSha: String, apkSha: String): String {
        require(this.challenge == challenge && this.processEpoch == processEpoch && arm != 0L)
        return receipt("status", challenge, processEpoch, runtimeSha, featureSha, apkSha,
            StereoBankControls.concurrentQualification())
    }

    /** Internal diagnostic context; never extends the acceptance receipt schema. */
    @JvmStatic fun diagnosticArmContext(challenge: String, processEpoch: String): String {
        require(this.challenge == challenge && this.processEpoch == processEpoch && arm != 0L)
        return JSONObject().put("arm_generation", arm)
            .put("arm_entry_elapsed_ns", armEntryElapsedNs)
            .put("arm_exit_elapsed_ns", armExitElapsedNs).toString()
    }

    @JvmStatic fun policy(challenge: String, processEpoch: String, runtimeSha: String,
        featureSha: String, apkSha: String, update: LongArray?): String {
        require(this.challenge == challenge && this.processEpoch == processEpoch && arm != 0L)
        status(challenge, processEpoch, runtimeSha, featureSha, apkSha)
        val observed = if (update == null) StereoBankControls.snapshot() else {
            require(update.size == 6 && update.take(4).all { it in 0L..1L } &&
                update.drop(4).all { it in 0L..2L })
            StereoBankControls.update(StereoBankControlPolicy(
                StereoImageOrigin.entries[update[0].toInt()], StereoImageOrigin.entries[update[1].toInt()],
                StereoImageOrigin.entries[update[2].toInt()], StereoImageOrigin.entries[update[3].toInt()],
                StereoGuideOrigin.entries[update[4].toInt()], StereoGuideOrigin.entries[update[5].toInt()]))
        }
        require(observed.enabled) { "live renderer policy unavailable" }
        val readbackRequested = update != null &&
            StereoBankControls.requestConcurrentQualificationReadback(challenge, arm)
        return JSONObject(receipt(if (update == null) "policy_read" else "policy_update", challenge,
            processEpoch, runtimeSha, featureSha, apkSha, StereoBankControls.concurrentQualification()))
            .put("configured_policy", JSONArray(observed.policy.words().toList()))
            .put("configured_policy_revision", observed.revision)
            .put("readback_request_accepted", readbackRequested)
            .put("policy_configuration_scope", "configuration_only").toString()
    }

    @JvmStatic fun lifecycle(action: String, challenge: String, processEpoch: String,
        runtimeSha: String, featureSha: String, apkSha: String, nativeResult: String): String {
        require(action in setOf("start", "renew_authority", "peer_stop", "peer_revoke", "peer_status", "whole_app_close"))
        require(this.challenge == challenge && this.processEpoch == processEpoch && arm != 0L)
        require(nativeResult.length in 2..(256 * 1024))
        return JSONObject(receipt(action, challenge, processEpoch, runtimeSha, featureSha, apkSha,
            StereoBankControls.concurrentQualification())).put("peer_lifecycle", JSONObject(nativeResult)).toString()
    }

    private fun receipt(action: String, challenge: String, processEpoch: String,
        runtimeSha: String, featureSha: String, apkSha: String, snapshot: LongArray): String {
        val bits = StereoBankControls.challengeWords(challenge)
        require(runtimeConfig == runtimeSha && featureLock == featureSha && installedApk == apkSha) {
            "authenticated observation inputs changed"
        }
        require(snapshot[2] == nativeProcess && snapshot[3] == bits[0] && snapshot[4] == bits[1] &&
            snapshot[5] == arm && snapshot[6] == 1L) { "native observation lineage differs" }
        for (sha in arrayOf(runtimeSha, featureSha, apkSha)) require(sha.matches(Regex("[0-9a-f]{64}")))
        val words = JSONArray()
        snapshot.forEach { words.put(it) }
        return JSONObject().put("schema", "rusty.quest.stereo.concurrent_qualification_receipt.v1")
            .put("action", action).put("challenge", challenge).put("process_epoch_id", processEpoch)
            .put("arm_generation", arm).put("native_process_generation", nativeProcess)
            .put("runtime_config_sha256", runtimeSha).put("feature_lock_sha256", featureSha)
            .put("apk_sha256", apkSha).put("native_snapshot", words).toString()
    }
}
