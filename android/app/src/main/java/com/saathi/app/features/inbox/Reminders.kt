package com.saathi.app.features.inbox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.saathi.app.SaathiApp
import com.saathi.app.core.Notifier
import com.saathi.app.core.data.ReminderDao
import com.saathi.app.core.data.ReminderEntity

/** Schedules reminder alarms. Exact when the user allowed exact alarms, otherwise inexact (noted in NOTES.md). */
class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Inserts [r] and schedules it; returns the new row id. */
    suspend fun add(dao: ReminderDao, r: ReminderEntity): Long {
        val id = dao.insert(r)
        schedule(r.copy(id = id))
        return id
    }

    fun schedule(r: ReminderEntity) {
        if (r.fired || r.id == 0L) return
        val pi = PendingIntent.getBroadcast(
            context, r.id.toInt(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(context, alarms, r.triggerAt, pi)
    }

    fun rescheduleAll(pending: List<ReminderEntity>) = pending.forEach(::schedule)

    companion object {
        const val EXTRA_ID = "reminder_id"

        /** Exact-while-idle if permitted (API 31+ needs the user's OK), else the inexact equivalent. */
        fun setAlarm(context: Context, alarms: AlarmManager, at: Long, pi: PendingIntent) {
            val trigger = maxOf(at, System.currentTimeMillis() + 1_000)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            runCatching {
                if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }.onFailure { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi) }
        }
    }
}

/** Posts the reminder notification and speaks it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, 0L)
        if (id == 0L) return
        launchAsync {
            val c = SaathiApp.container
            val r = c.db.reminders().byId(id) ?: return@launchAsync
            if (r.fired) return@launchAsync
            c.db.reminders().update(r.copy(fired = true))
            c.notifier.post(Notifier.CH_REMINDERS, (id % Int.MAX_VALUE).toInt(), r.title, r.body,
                tap = c.notifier.openAppIntent("digest"))
            c.speaker.say("${r.title}. ${r.body}", c.settings.current.replyStyle)
        }
    }
}
