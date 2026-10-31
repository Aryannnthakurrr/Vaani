package com.saathi.app

import com.saathi.app.core.voice.VoiceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s -> b[2 * i] = (s and 0xFF).toByte(); b[2 * i + 1] = (s shr 8).toByte() }
    }

    @Test
    fun loudnessFromPcm() {
        assertEquals(0f, VoiceLevel.rms16(pcm(0, 0, 0, 0), 8), 0f)
        val speech = VoiceLevel.rms16(pcm(3000, -3000, 3000, -3000), 8)
        assertEquals(0.5f, speech, 0.01f)
        assertEquals(1f, VoiceLevel.rms16(pcm(32000, -32000), 4), 0f) // capped
    }

    @Test
    fun barsSwellWithTheVoiceAndPeakInTheMiddle() {
        val quiet = VoiceLevel.bars(0f, 0f, 9)
        val loud = VoiceLevel.bars(1f, 0f, 9)
        assertTrue(quiet.all { it in 0.08f..0.2f })
        assertTrue(loud.sum() > quiet.sum() * 2)
        assertTrue(loud[4] >= loud[0] && loud[4] >= loud[8])
    }
}
