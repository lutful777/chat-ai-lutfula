package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object ReminderScheduler {
    private val zoneId: ZoneId = ZoneId.of("Asia/Jakarta")

    fun looksLikeReminder(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("ingatkan") || lower.contains("pengingat") || lower.contains("remind me") || lower.contains("reminder")
    }

    fun scheduleFromTextIfPossible(context: Context, text: String): Boolean {
        if (!looksLikeReminder(text)) return false
        val now = getOnlineNow()
        val target = parseTargetDateTime(text, now) ?: return false
        if (!target.isAfter(now)) return false
        schedule(context.applicationContext, target.toInstant().toEpochMilli())
        return true
    }

    private fun schedule(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmEvent::class.java)
        val requestCode = (triggerAtMillis % Int.MAX_VALUE).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun parseTargetDateTime(text: String, now: ZonedDateTime): ZonedDateTime? {
        val lower = text.lowercase(Locale.ROOT)
        val parsedTime = parseTime(lower) ?: return null
        var date: LocalDate = now.toLocalDate()
        when {
            lower.contains("lusa") -> date = date.plusDays(2)
            lower.contains("besok") || lower.contains("tomorrow") -> date = date.plusDays(1)
        }
        var target = ZonedDateTime.of(date, parsedTime, zoneId)
        if (!lower.contains("besok") && !lower.contains("tomorrow") && !lower.contains("lusa") && !target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return target
    }

    private fun parseTime(text: String): LocalTime? {
        val patterns = listOf(
            Regex("""(?:jam|pukul)\s+(\d{1,2})(?:[:.](\d{2}))?\s*(pagi|siang|sore|malam)?""", RegexOption.IGNORE_CASE),
            Regex("""(?:at)\s+(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,2})[:.](\d{2})\b""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            var hour = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
            val minute = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val marker = match.groupValues.getOrNull(3)?.lowercase(Locale.ROOT).orEmpty()
            if (marker == "pm" || marker == "malam" || marker == "sore" || marker == "siang") {
                if (hour in 1..11) hour += 12
            }
            if (marker == "am" || marker == "pagi") {
                if (hour == 12) hour = 0
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            return LocalTime.of(hour, minute)
        }
        return null
    }

    private fun getOnlineNow(): ZonedDateTime {
        return try {
            val url = URL("https://worldtimeapi.org/api/timezone/Asia/Jakarta")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = "GET"
            connection.inputStream.bufferedReader().use { reader ->
                val body = reader.readText()
                val datetime = JSONObject(body).optString("datetime")
                OffsetDateTime.parse(datetime).atZoneSameInstant(zoneId)
            }
        } catch (e: Exception) {
            ZonedDateTime.now(zoneId)
        }
    }

    fun onlineTimeContext(): String {
        val now = getOnlineNow()
        val date = now.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", Locale.ENGLISH))
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH))
        return "Online current time: $date, $time, timezone Asia/Jakarta"
    }
}
