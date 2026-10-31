package com.saathi.app

import com.saathi.app.core.net.TrafficStats
import com.saathi.app.core.net.UsageMeter
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageMeterTest {
    @Test
    fun countsPerFeatureWithoutIds() {
        UsageMeter.reset()
        assertEquals("care", UsageMeter.keyFor("/care/s_ab12cd/events"))
        assertEquals("agent", UsageMeter.keyFor("/agent/step"))
        UsageMeter.record(UsageMeter.keyFor("/agent/step"), 4000, 300)
        UsageMeter.record(UsageMeter.keyFor("/agent/step"), 3800, 280)
        UsageMeter.record("live_voice", 1600, 0, request = false) // a mic chunk is not a request
        assertEquals(TrafficStats(2, 7800, 580), UsageMeter.snapshot()["agent"])
        assertEquals(TrafficStats(2, 9400, 580), UsageMeter.total())
        UsageMeter.reset()
        assertEquals(TrafficStats(0, 0, 0), UsageMeter.total())
    }
}
