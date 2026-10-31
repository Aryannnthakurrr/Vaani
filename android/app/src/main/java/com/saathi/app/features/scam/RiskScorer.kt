package com.saathi.app.features.scam

/** Everything CallGuard knows about the current call (SPEC §7.6 F3). */
data class CallSignals(
    val savedContact: Boolean,
    val durationMs: Long = 0,
    val otpDuringCall: Boolean = false,
    val moneyAppDuringCall: Boolean = false,
    val remoteAppDuringCall: Boolean = false,
    /** Worst message event in the last 24 h. */
    val recentMessageLevel: ScamLevel = ScamLevel.NONE,
)

data class RiskScore(val score: Int, val level: ScamLevel, val reasons: List<String>)

/** Pure cross-signal scorer. Weights and thresholds are fixed by the spec and covered by RiskScorerTest. */
object RiskScorer {
    const val RED_AT = 7
    const val AMBER_AT = 4
    private const val LONG_CALL_MS = 5 * 60 * 1000L

    fun score(s: CallSignals): RiskScore {
        var score = 0
        val reasons = mutableListOf<String>()
        if (s.savedContact) score -= 3 else { score += 3; reasons += "unknown_caller" }
        if (s.durationMs > LONG_CALL_MS) { score += 1; reasons += "long_call" }
        if (s.otpDuringCall) { score += 4; reasons += "otp_during_call" }
        if (s.moneyAppDuringCall) { score += 3; reasons += "money_app_during_call" }
        if (s.remoteAppDuringCall) { score += 6; reasons += "remote_app_during_call" }
        when (s.recentMessageLevel) {
            ScamLevel.RED -> { score += 4; reasons += "recent_scam_message" }
            ScamLevel.AMBER -> { score += 2; reasons += "recent_scam_message" }
            else -> Unit
        }
        return RiskScore(score, levelFor(score), reasons)
    }

    fun levelFor(score: Int): ScamLevel = when {
        score >= RED_AT -> ScamLevel.RED
        score >= AMBER_AT -> ScamLevel.AMBER
        else -> ScamLevel.NONE
    }
}
