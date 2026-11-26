package io.github.akiig.dose

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Notifications when doses are due: one at the time (doses due together share one), and one
 * more 30 minutes later if they aren't marked. "Taken" in the notification marks them; "Later"
 * asks again in 10 minutes. One alarm at a time, always the next one; planned again after
 * every change, after a restart and when the clock or time zone changes.
 */
object Reminders {
    const val CHANNEL = "doses"
    const val ACTION_ALARM = "io.github.akiig.dose.ALARM"
    const val ACTION_TAKEN = "io.github.akiig.dose.TAKEN"
    const val ACTION_LATER = "io.github.akiig.dose.LATER"
    const val OPEN_TODAY = "open_today"

    data class Alarm(val at: LocalDateTime, val day: String, val time: String, val again: Boolean)

    /** The next reminder after `now`, looking three days ahead. */
    fun next(store: AppStore, now: LocalDateTime, followUp: Boolean): Alarm? {
        val out = ArrayList<Alarm>()
        for (offset in 0..2) {
            val day = now.toLocalDate().plusDays(offset.toLong()).toString()
            val times = store.slots(day).filter { it.dose == null }.map { it.time }.distinct()
            for (t in times) {
                val at = LocalDate.parse(day).atTime(LocalTime.parse(t))
                out.add(Alarm(at, day, t, false))
                if (followUp) out.add(Alarm(at.plusMinutes(30), day, t, true))
            }
        }
        return out.filter { it.at.isAfter(now) }.minByOrNull { it.at }
    }

    private fun alarmIntent(context: Context, a: Alarm?, requestCode: Int, later: Boolean = false): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(ACTION_ALARM)
        if (a != null) i.putExtra("day", a.day).putExtra("time", a.time).putExtra("again", a.again).putExtra("later", later)
        return PendingIntent.getBroadcast(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAlarm(context: Context, millis: Long, pi: PendingIntent) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        else alarms.setWindow(AlarmManager.RTC_WAKEUP, millis, 60_000L, pi)
    }

    /** (Re)plans the next reminder, and takes notifications of marked doses off the screen. */
    fun plan(context: Context, store: AppStore) {
        val prefs = Prefs(context)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(alarmIntent(context, null, 1))
        tidy(context, store)
        if (!prefs.remind) return
        val a = next(store, LocalDateTime.now(), prefs.followUp) ?: return
        setAlarm(context, a.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarmIntent(context, a, 1))
    }

    private fun notificationId(day: String, time: String) = ("$day@$time").hashCode()

    private fun tidy(context: Context, store: AppStore) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (n in nm.activeNotifications) {
            val extras = n.notification.extras
            val day = extras.getString("dose.day") ?: continue
            val time = extras.getString("dose.time") ?: continue
            if (store.slots(day).none { it.time == time && it.dose == null }) nm.cancel(n.id)
        }
    }

    val PRIORITIES = listOf("urgent", "high", "normal", "quiet")
    val SOUNDS = listOf("default", "chime", "bell", "soft", "custom", "none")

    /** The sound a choice plays, or null for none. */
    fun soundUri(context: Context, sound: String, custom: String): android.net.Uri? = when (sound) {
        "none" -> null
        "chime", "bell", "soft" -> android.net.Uri.parse("android.resource://${context.packageName}/raw/dose_$sound")
        "custom" -> if (custom.isNotEmpty()) android.net.Uri.parse(custom) else android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
        else -> android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
    }

    /**
     * Android fixes a channel's sound and importance once it's made, so each choice of priority
     * and sound has its own channel; the old ones are removed when you change them.
     */
    fun createChannel(context: Context): String {
        val prefs = Prefs(context)
        val priority = prefs.priority.takeIf { it in PRIORITIES } ?: "high"
        val sound = if (priority == "quiet") "none" else prefs.sound
        val key = if (sound == "custom") "custom" + Integer.toHexString(prefs.soundUri.hashCode()) else sound
        val id = "dose-$priority-$key"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) == null) {
            val importance = when (priority) {
                "urgent", "high" -> NotificationManager.IMPORTANCE_HIGH
                "normal" -> NotificationManager.IMPORTANCE_DEFAULT
                else -> NotificationManager.IMPORTANCE_LOW
            }
            val channel = NotificationChannel(id, tr("Doses"), importance).apply {
                description = tr("When a dose is due, and once more 30 minutes later if it isn't marked.")
                val uri = soundUri(context, sound, prefs.soundUri)
                // urgent sounds use the alarm volume, so they're heard when the ringer is low
                val usage = if (priority == "urgent") android.media.AudioAttributes.USAGE_ALARM
                            else android.media.AudioAttributes.USAGE_NOTIFICATION
                setSound(uri, if (uri == null) null else android.media.AudioAttributes.Builder()
                    .setUsage(usage).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(priority != "quiet")
            }
            nm.createNotificationChannel(channel)
        }
        for (c in nm.notificationChannels) if (c.id != id && (c.id == CHANNEL || c.id.startsWith("dose-"))) nm.deleteNotificationChannel(c.id)
        return id
    }

    /** Plays a sound choice once, to hear it in Settings. */
    fun preview(context: Context, sound: String, custom: String) {
        val uri = soundUri(context, sound, custom) ?: return
        try { android.media.RingtoneManager.getRingtone(context, uri)?.play() } catch (e: Exception) { }
    }

    fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, store: AppStore, day: String, time: String, again: Boolean) {
        val meds = store.slots(day).filter { it.time == time && it.dose == null }.map { it.med }
        if (meds.isEmpty() || !canNotify(context)) return
        val channel = createChannel(context)
        val prefs = Prefs(context)
        val title: String; val body: String
        if (prefs.discreet) {
            title = "Dose"; body = tr("Time for your %@ dose", time)
        } else {
            title = if (again) tr("%@ · still to take", time) else tr("%@ · time for your medicine", time)
            body = meds.joinToString(", ") { it.title } + if (meds.size == 1) " · " + Units.amount(meds[0].amountAt(time), meds[0].unit) else ""
        }
        val id = notificationId(day, time)
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(context, id * 4 + code,
            Intent(context, ReminderReceiver::class.java).setAction(action).putExtra("day", day).putExtra("time", time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, id * 4 + 3,
            Intent(context, MainActivity::class.java).putExtra(OPEN_TODAY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(io.github.akiig.dose.ui.Tones.all.firstOrNull { it.key == prefs.tone }?.accent?.let {
                android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
                ?: 0xff5c56b4.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(when (prefs.priority) { "normal" -> NotificationCompat.PRIORITY_DEFAULT; "quiet" -> NotificationCompat.PRIORITY_LOW
                else -> NotificationCompat.PRIORITY_HIGH })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, tr("Taken"), action(ACTION_TAKEN, 1))
            .addAction(0, tr("Later (10 minutes)"), action(ACTION_LATER, 2))
            .addExtras(android.os.Bundle().apply { putString("dose.day", day); putString("dose.time", time) })
            .build()
        // urgent: the sound repeats until you answer, open or swipe it away
        if (prefs.priority == "urgent") n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        try { NotificationManagerCompat.from(context).notify(id, n) } catch (e: SecurityException) { }
    }

    /** "Taken" from a notification: every dose of that time still unmarked. */
    fun takeAll(store: AppStore, day: String, time: String) {
        for (s in store.slots(day)) if (s.time == time && s.dose == null) store.mark(s.med.id, day, time, "taken", notify = false)
        store.onChange?.invoke()
    }

    fun later(context: Context, day: String, time: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(day, time))
        val a = Alarm(LocalDateTime.now().plusMinutes(10), day, time, true)
        setAlarm(context, System.currentTimeMillis() + 10 * 60_000L, alarmIntent(context, a, 2, later = true))
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        loadTranslations(context)
        val state = AppState.get(context.applicationContext as Application)
        val store = state.store
        store.load()                     // the app may have been asleep: read the file first
        val day = intent.getStringExtra("day") ?: Day.today
        val time = intent.getStringExtra("time") ?: ""
        when (intent.action) {
            Reminders.ACTION_ALARM -> {
                if (Prefs(context).remind) Reminders.show(context, store, day, time, intent.getBooleanExtra("again", false))
                if (!intent.getBooleanExtra("later", false)) Reminders.plan(context, store)
            }
            Reminders.ACTION_TAKEN -> Reminders.takeAll(store, day, time)
            Reminders.ACTION_LATER -> Reminders.later(context, day, time)
            else -> Reminders.plan(context, store)   // boot, update, time or time-zone change
        }
    }
}
