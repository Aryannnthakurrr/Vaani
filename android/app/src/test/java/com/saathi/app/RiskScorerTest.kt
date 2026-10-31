package com.saathi.app

import com.saathi.app.features.scam.CallSignals
import com.saathi.app.features.scam.RiskScorer
import com.saathi.app.features.scam.ScamLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class RiskScorerTest {

    private fun score(s: CallSignals) = RiskScorer.score(s)

    @Test fun baseWeights() {
        assertEquals(3, score(CallSignals(savedContact = false)).score)
        assertEquals(-3, score(CallSignals(savedContact = true)).score)
        assertEquals(4, score(CallSignals(savedContact = false, durationMs = 5 * 60_000L + 1)).score)
        assertEquals(3, score(CallSignals(savedContact = false, durationMs = 5 * 60_000L)).score)
    }

    @Test fun signalWeights() {
        assertEquals(7, score(CallSignals(false, otpDuringCall = true)).score)
        assertEquals(6, score(CallSignals(false, moneyAppDuringCall = true)).score)
        assertEquals(9, score(CallSignals(false, remoteAppDuringCall = true)).score)
        assertEquals(5, score(CallSignals(false, recentMessageLevel = ScamLevel.AMBER)).score)
        assertEquals(7, score(CallSignals(false, recentMessageLevel = ScamLevel.RED)).score)
    }

    @Test fun unknownCallerPlusOtpIsRed() {
        assertEquals(ScamLevel.RED, score(CallSignals(savedContact = false, otpDuringCall = true)).level)
    }

    @Test fun savedContactPlusOtpRaisesNothing() {
        val r = score(CallSignals(savedContact = true, otpDuringCall = true))
        assertEquals(1, r.score)
        assertEquals(ScamLevel.NONE, r.level)
    }

    @Test fun unknownCallerPlusMoneyAppIsAmber() {
        assertEquals(ScamLevel.AMBER, score(CallSignals(savedContact = false, moneyAppDuringCall = true)).level)
    }

    @Test fun thresholds() {
        assertEquals(ScamLevel.NONE, RiskScorer.levelFor(3))
        assertEquals(ScamLevel.AMBER, RiskScorer.levelFor(4))
        assertEquals(ScamLevel.AMBER, RiskScorer.levelFor(6))
        assertEquals(ScamLevel.RED, RiskScorer.levelFor(7))
    }

    @Test fun everythingAtOnce() {
        val r = score(CallSignals(false, 10 * 60_000L, true, true, true, ScamLevel.RED))
        assertEquals(3 + 1 + 4 + 3 + 6 + 4, r.score)
        assertEquals(6, r.reasons.size)
    }
}
