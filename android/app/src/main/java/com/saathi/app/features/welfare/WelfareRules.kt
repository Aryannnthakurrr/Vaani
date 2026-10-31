package com.saathi.app.features.welfare

import java.time.LocalTime
import java.time.ZonedDateTime

/** Pure welfare timing rules (SPEC §7.5). */
object WelfareRules {
    const val HEARTBEAT_MIN_GAP_MS = 10 * 60 * 1000L
    private val eveningCutoff = LocalTime.of(20, 0)

    /** True when the phone has not been unlocked since 20:00 on the previous day. */
    fun needsCheckIn(lastUnlockMs: Long, now: ZonedDateTime): Boolean {
        val cutoff = now.toLocalDate().minusDays(1).atTime(eveningCutoff).atZone(now.zone)
        return lastUnlockMs < cutoff.toInstant().toEpochMilli()
    }

    fun shouldSendHeartbeat(lastSentMs: Long, nowMs: Long): Boolean = nowMs - lastSentMs >= HEARTBEAT_MIN_GAP_MS

    /** Next occurrence of "HH:mm" strictly after [now]; falls back to 11:30 on a malformed setting. */
    fun nextAt(hhmm: String, now: ZonedDateTime): ZonedDateTime {
        val time = runCatching { LocalTime.parse(hhmm) }.getOrDefault(LocalTime.of(11, 30))
        val today = now.with(time).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }
}
