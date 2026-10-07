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

    @JvmStatic fun currentHubChallenge(processEpoch: String, runtimeSha: String, featureSha: String): String {
        require(arm != 0L && this.processEpoch == processEpoch && runtimeConfig == runtimeSha && featureLock == featureSha)
        return requireNotNull(challenge)
    }

    @JvmStatic fun hubSnapshot(processEpoch: String, runtimeSha: String, featureSha: String, lifecycle: String, pair: EmbeddedDuplexPairStatus): String {
        val current = currentHubChallenge(processEpoch, runtimeSha, featureSha)
        val snapshot = JSONObject(status(current,processEpoch,runtimeSha,featureSha,requireNotNull(installedApk))).getJSONArray("native_snapshot")
        val configured = StereoBankControls.snapshot()
        val maskEnabled = StereoBankControls.maskSnapshot()[3] != 0L
        val life = JSONObject(lifecycle)
        require(life.getString("\$schema") == "rusty.quest.embedded_duplex.concurrent_peer_lifecycle.v1" && life.getString("action") == "peer_status")
        require(life.getString("config_sha256") == runtimeSha && life.getString("status") == "active" && !life.getBoolean("renewal_pending"))
        val projection = life.getJSONObject("authority_projection")
        val observed = life.getLong("observed_at_ms")
        val expiry = minOf(projection.getLong("expires_at_ms"),pair.localSessionExpiresAtMs)
        require(pair.localSessionCurrent && pair.sessionId == projection.getString("peer_session_id") && expiry > observed+5000L)
        val policy = configured.policy.words()
        val pixelCurrent = io.github.mesmerprism.rustyquest.spatial_camera_panel.ConcurrentStereoHubContract.currentPolicyPixels(
            LongArray(snapshot.length()) { snapshot.getLong(it) }, policy, configured.revision, nativeProcess, arm, maskEnabled)
        require(listOf(expiry,configured.revision,snapshot.getLong(39),snapshot.getLong(55),snapshot.getLong(56)).all { it in 0L..9_007_199_254_740_991L }) { "Hub scalar exceeds exact browser integer range" }
        val token = if(policy.all { it==0L }) "own" else if(policy.all { it==1L }) "peer" else "mixed"
        return JSONObject().put("running",true).put("pair_current",true).put("expires_at_ms",expiry)
            .put("process_epoch",processEpoch).put("runtime_config",runtimeSha).put("arm",arm.toString()).put("native_process",nativeProcess.toString())
            .put("revision",configured.revision).put("policy",token).put("policy_pixel_current",pixelCurrent)
            .put("gpu_frame",snapshot.getLong(39)).put("own_adoptions",snapshot.getLong(55)).put("peer_adoptions",snapshot.getLong(56))
            .put("mask_enabled",maskEnabled).put("sample_elapsed_ms",android.os.SystemClock.elapsedRealtime()).put("observation_status","current_owner_observation").toString()
    }

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

    /** Fixed debug observation, independently checked against this actual arm. */
    @JvmStatic fun dropoutObservation(challenge: String, processEpoch: String): String {
        require(this.challenge == challenge && this.processEpoch == processEpoch && arm != 0L)
        val report = JSONObject(StereoBankControls.concurrentDropoutObservation(challenge, arm))
        require(report.getLong("process_generation") == nativeProcess && report.getLong("arm_generation") == arm)
        return report.toString()
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

    @JvmStatic fun mask(challenge: String, processEpoch: String, runtimeSha: String,
        featureSha: String, apkSha: String, update: LongArray?): String {
        require(this.challenge == challenge && this.processEpoch == processEpoch && arm != 0L)
        status(challenge, processEpoch, runtimeSha, featureSha, apkSha)
        if(update != null) {
            require(update.size == 7 && update[0] == 1L && listOf(update[1],update[5],update[6]).all { it in 0L..1L }
                && update.slice(2..4).all { it in 0L..0xffffffffL })
            StereoBankControls.updateMask(io.github.mesmerprism.rustyquest.spatial_camera_panel.NeutralMaskPolicy(
                1,update[1]==1L,Float.fromBits(update[2].toInt()),Float.fromBits(update[3].toInt()),Float.fromBits(update[4].toInt()),update[5]==1L,update[6]==1L))
        }
        val configured=StereoBankControls.maskSnapshot()
        val requested=update != null && StereoBankControls.requestConcurrentQualificationReadback(challenge,arm)
        val report=JSONObject(StereoBankControls.maskReadback())
        val bits=StereoBankControls.challengeWords(challenge)
        val observed=report.getJSONArray("challenge_words")
        require(report.getString("schema")=="rusty.quest.stereo.neutral_mask_readback.v1" && report.getInt("version")==1
            && report.getLong("process_generation")==nativeProcess && report.getLong("arm_generation")==arm
            && report.getBoolean("armed") && observed.length()==2 && observed.getLong(0)==bits[0] && observed.getLong(1)==bits[1])
        val pixel=report.optJSONObject("pixel_frame")
        val matches=pixel != null && pixel.getLong("arm_generation")==arm && pixel.getLong("control_revision")==configured[2]
            && (0..2).all { pixel.getJSONArray("mask_words").getLong(it)==configured[3+it] }
            && (0..5).all { pixel.getJSONArray("policy").getLong(it)==configured[6+it] }
        return JSONObject().put("schema","rusty.quest.stereo.neutral_mask_receipt.v1")
            .put("action",if(update==null) "blend_read" else "blend_update").put("challenge",challenge)
            .put("process_epoch_id",processEpoch).put("runtime_config_sha256",runtimeSha)
            .put("feature_lock_sha256",featureSha).put("installed_apk_sha256",apkSha)
            .put("configured_mask",JSONArray(configured.take(6))).put("configured_policy",JSONArray(configured.drop(6))).put("configuration_scope","configuration_only")
            .put("readback_request_accepted",requested).put("pixel_frame_matches_current_configuration",matches).put("gpu_readback",report).toString()
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
