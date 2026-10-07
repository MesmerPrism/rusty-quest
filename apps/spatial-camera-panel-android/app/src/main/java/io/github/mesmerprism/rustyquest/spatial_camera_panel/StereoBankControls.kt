package io.github.mesmerprism.rustyquest.spatial_camera_panel

/** Camera origin is independent of raw/processed/mix and video content choice. */
enum class StereoImageOrigin(val word: Long) { Own(0), Peer(1) }
enum class StereoGuideOrigin(val word: Long) { Own(0), Peer(1), FollowRegion(2) }

data class StereoBankControlPolicy(
    val center: StereoImageOrigin = StereoImageOrigin.Own,
    val middle: StereoImageOrigin = StereoImageOrigin.Own,
    val outer: StereoImageOrigin = StereoImageOrigin.Own,
    val geometry: StereoImageOrigin = StereoImageOrigin.Own,
    val brightness: StereoGuideOrigin = StereoGuideOrigin.FollowRegion,
    val strength: StereoGuideOrigin = StereoGuideOrigin.FollowRegion,
) {
    fun words() = longArrayOf(center.word, middle.word, outer.word, geometry.word, brightness.word, strength.word)
}
data class StereoBankControlSnapshot(val enabled: Boolean, val policy: StereoBankControlPolicy, val revision: Long)

/** Explicit neutral v1 test selection; no luminance formula is owned here. */
data class NeutralMaskPolicy(val version: Int = 1, val enabled: Boolean = false,
    val threshold: Float = 0.5f, val softness: Float = 0.1f, val amount: Float = 0f,
    val invert: Boolean = false, val diagnostic: Boolean = false) {
    fun words(): LongArray {
        require(version == 1)
        require(listOf(threshold, softness, amount).all { it.isFinite() && it.toRawBits() >= 0 })
        require(threshold in 0f..1f && softness in 0.001f..0.5f && amount in 0f..1f)
        require(enabled || (threshold == 0.5f && softness == 0.1f && amount == 0f && !invert && !diagnostic))
        return longArrayOf(1, if(enabled) 1 else 0, threshold.toRawBits().toLong(), softness.toRawBits().toLong(),
            amount.toRawBits().toLong(), if(invert) 1 else 0, if(diagnostic) 1 else 0)
    }
}
object StereoBankControls {
    fun updateMask(policy: NeutralMaskPolicy): Long = nativeApplyMask(policy.words()).also { require(it > 0) }
    fun maskSnapshot(): LongArray = nativeReadMask().also { require(it.size == 12 && it[0] == 1L && it[1] == 1L && it[2] > 0) }
    fun maskReadback(): String = requireNotNull(nativeReadMaskReadback()).also { require(it.toByteArray(Charsets.UTF_8).size <= 8192) }
    @JvmStatic private external fun nativeReadMask(): LongArray
    @JvmStatic private external fun nativeApplyMask(policy: LongArray): Long
    @JvmStatic private external fun nativeReadMaskReadback(): String?
    /** Opaque native epoch; arming does not establish frame or pixel adoption. */
    fun armConcurrentQualification(challenge: String): Long {
        val words = challengeWords(challenge)
        return nativeArmConcurrentStereoQualification(words[0], words[1])
    }
    fun concurrentQualification(): LongArray {
        val words = nativeReadConcurrentStereoQualification()
        require(words.size == 160 && words[0] == 1L && words[1] == 160L)
        return words
    }
    /** Bounded observation only; this never supplies qualification words. */
    fun concurrentDropoutObservation(challenge: String, armGeneration: Long): String {
        require(armGeneration > 0)
        val words = challengeWords(challenge)
        val report = requireNotNull(nativeReadConcurrentStereoDropouts(words[0], words[1], armGeneration))
        require(report.toByteArray(Charsets.UTF_8).size <= 6144)
        return report
    }
    fun requestConcurrentQualificationReadback(challenge: String, armGeneration: Long): Boolean {
        val words = challengeWords(challenge)
        return nativeRequestConcurrentStereoReadback(words[0], words[1], armGeneration)
    }
    fun disarmConcurrentQualification(challenge: String, armGeneration: Long): Boolean {
        val words = challengeWords(challenge)
        return nativeDisarmConcurrentStereoQualification(words[0], words[1], armGeneration)
    }
    fun challengeWords(challenge: String): LongArray {
        require(challenge.matches(Regex("[0-9a-f]{32}")) && challenge.any { it != '0' })
        return longArrayOf(java.lang.Long.parseUnsignedLong(challenge.substring(0, 16), 16),
            java.lang.Long.parseUnsignedLong(challenge.substring(16), 16))
    }
    fun snapshot(): StereoBankControlSnapshot {
        val words = try { nativeRead() } catch (_: UnsatisfiedLinkError) { longArrayOf() }
        if (words.size != 8 || words[0] != 1L || words[1] <= 0)
            return StereoBankControlSnapshot(false, StereoBankControlPolicy(), 0)
        val images = words.copyOfRange(2, 6).map { word ->
            StereoImageOrigin.entries.firstOrNull { it.word == word } ?: return disabled()
        }
        val guides = words.copyOfRange(6, 8).map { word ->
            StereoGuideOrigin.entries.firstOrNull { it.word == word } ?: return disabled()
        }
        return StereoBankControlSnapshot(true, StereoBankControlPolicy(
            images[0], images[1], images[2], images[3], guides[0], guides[1]), words[1])
    }
    private fun disabled() = StereoBankControlSnapshot(false, StereoBankControlPolicy(), 0)
    /** Native accepts configuration; only later rendering can establish frame/pixel adoption. */
    fun update(policy: StereoBankControlPolicy): StereoBankControlSnapshot {
        if (!snapshot().enabled) return disabled()
        val revision = nativeApply(policy.words())
        if (revision <= 0) return snapshot() // shutdown may fence the feature between read and submit
        return snapshot()
    }
    @JvmStatic private external fun nativeRead(): LongArray
    @JvmStatic private external fun nativeApply(policy: LongArray): Long
    @JvmStatic private external fun nativeArmConcurrentStereoQualification(hi: Long, lo: Long): Long
    @JvmStatic private external fun nativeReadConcurrentStereoQualification(): LongArray
    @JvmStatic private external fun nativeReadConcurrentStereoDropouts(hi: Long, lo: Long, armGeneration: Long): String?
    @JvmStatic private external fun nativeRequestConcurrentStereoReadback(hi: Long, lo: Long, armGeneration: Long): Boolean
    @JvmStatic private external fun nativeDisarmConcurrentStereoQualification(hi: Long, lo: Long, armGeneration: Long): Boolean
}
