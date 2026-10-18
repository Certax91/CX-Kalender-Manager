package de.certax.kalender

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class Repeat(val label: String) {
    NONE("Nicht wiederholen"),
    DAILY("Täglich"),
    WEEKLY("Wöchentlich"),
    MONTHLY("Monatlich"),
    YEARLY("Jährlich"),
}

/** Erinnerungen wie im Samsung Kalender (Minuten vorher, -1 = keine). */
val REMINDER_OPTIONS = listOf(
    -1 to "Keine Erinnerung",
    0 to "Zum Zeitpunkt des Termins",
    5 to "5 Minuten vorher",
    10 to "10 Minuten vorher",
    15 to "15 Minuten vorher",
    30 to "30 Minuten vorher",
    60 to "1 Stunde vorher",
    120 to "2 Stunden vorher",
    1440 to "1 Tag vorher",
    2880 to "2 Tage vorher",
    10080 to "1 Woche vorher",
)

fun reminderLabel(min: Int) = REMINDER_OPTIONS.firstOrNull { it.first == min }?.second ?: when {
    min % 1440 == 0 -> "${min / 1440} Tage vorher"
    min % 60 == 0 -> "${min / 60} Stunden vorher"
    else -> "$min Minuten vorher"
}

/** Ein Termin, wie er im Editor bearbeitet wird. */
data class EventDraft(
    val id: Long? = null,
    val title: String = "",
    val calendarId: Long? = null,
    val allDay: Boolean = false,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val repeat: Repeat = Repeat.NONE,
    val reminder: Int = 15,
    val location: String = "",
    val notes: String = "",
    val otherRrule: String? = null, // Wiederholung, die der Editor nicht kennt – bleibt erhalten
)

class EventStore(private val cr: ContentResolver) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun load(eventId: Long): EventDraft? {
        val proj = arrayOf(
            Events.TITLE, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY,
            Events.RRULE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.CALENDAR_ID,
        )
        val draft = cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), proj, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val allDay = c.getInt(4) == 1
            val dtStart = c.getLong(1)
            val dtEnd = if (!c.isNull(2) && c.getLong(2) > 0) c.getLong(2)
                else dtStart + parseDurationMs(c.getString(3))
            val rrule = c.getString(5)
            val repeat = rruleToRepeat(rrule)
            val (s, e) = if (allDay) {
                val sd = Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate()
                var ed = Instant.ofEpochMilli(dtEnd).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                if (ed.isBefore(sd)) ed = sd
                sd.atStartOfDay() to ed.atStartOfDay()
            } else {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(dtStart), zone) to
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(maxOf(dtEnd, dtStart)), zone)
            }
            EventDraft(
                id = eventId,
                title = c.getString(0) ?: "",
                calendarId = c.getLong(8),
                allDay = allDay,
                start = s, end = e,
                repeat = repeat ?: Repeat.NONE,
                location = c.getString(6) ?: "",
                notes = c.getString(7) ?: "",
                otherRrule = if (repeat == null && !rrule.isNullOrBlank()) rrule else null,
            )
        } ?: return null
        var minutes = -1
        cr.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()), "${Reminders.MINUTES} ASC")?.use { c ->
            if (c.moveToFirst()) minutes = c.getInt(0)
        }
        return draft.copy(reminder = minutes)
    }

    /** Speichert den Termin (neu oder geändert) und gibt die Event-ID zurück. */
    fun save(d: EventDraft): Long {
        val calId = d.calendarId ?: error("Kein Kalender gewählt")
        val v = ContentValues()
        v.put(Events.CALENDAR_ID, calId)
        v.put(Events.TITLE, d.title.ifBlank { "(ohne Titel)" })
        v.put(Events.EVENT_LOCATION, d.location)
        v.put(Events.DESCRIPTION, d.notes)

        val startMs: Long
        val endMs: Long
        if (d.allDay) {
            val sd = d.start.toLocalDate()
            val ed = maxOf(d.end.toLocalDate(), sd)
            startMs = sd.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            endMs = ed.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            v.put(Events.ALL_DAY, 1)
            v.put(Events.EVENT_TIMEZONE, "UTC")
        } else {
            startMs = d.start.atZone(zone).toInstant().toEpochMilli()
            endMs = maxOf(d.end.atZone(zone).toInstant().toEpochMilli(), startMs)
            v.put(Events.ALL_DAY, 0)
            v.put(Events.EVENT_TIMEZONE, zone.id)
        }
        v.put(Events.DTSTART, startMs)

        val rrule = repeatToRrule(d.repeat, d.start.toLocalDate()) ?: d.otherRrule
        if (rrule != null) {
            // Serientermine brauchen DURATION statt DTEND
            v.put(Events.RRULE, rrule)
            v.putNull(Events.DTEND)
            v.put(Events.DURATION, if (d.allDay) "P${(endMs - startMs) / 86_400_000L}D" else "P${(endMs - startMs) / 1000L}S")
        } else {
            v.putNull(Events.RRULE)
            v.putNull(Events.DURATION)
            v.put(Events.DTEND, endMs)
        }
        v.put(Events.HAS_ALARM, if (d.reminder >= 0) 1 else 0)

        val id = if (d.id == null) {
            val uri = cr.insert(Events.CONTENT_URI, v) ?: error("Termin konnte nicht gespeichert werden")
            ContentUris.parseId(uri)
        } else {
            cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, d.id), v, null, null)
            cr.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(d.id.toString()))
            d.id
        }
        if (d.reminder >= 0) {
            val r = ContentValues()
            r.put(Reminders.EVENT_ID, id)
            r.put(Reminders.MINUTES, d.reminder)
            r.put(Reminders.METHOD, Reminders.METHOD_ALERT)
            try { cr.insert(Reminders.CONTENT_URI, r) } catch (_: Exception) { }
        }
        return id
    }

    /** Löscht den ganzen Termin (bei Serien: alle Wiederholungen). */
    fun delete(eventId: Long) {
        cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }

    /** Löscht bei einer Serie nur einen einzelnen Termin (Ausnahme anlegen). */
    fun deleteOccurrence(eventId: Long, beginMillis: Long) {
        val v = ContentValues()
        v.put(Events.ORIGINAL_INSTANCE_TIME, beginMillis)
        v.put(Events.STATUS, Events.STATUS_CANCELED)
        val uri = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId)
        cr.insert(uri, v) ?: error("Einzeltermin konnte nicht gelöscht werden")
    }

    private fun parseDurationMs(d: String?): Long {
        if (d.isNullOrBlank()) return 3_600_000L
        val m = Regex("""P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").find(d) ?: return 3_600_000L
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        return (((g(1) * 7 + g(2)) * 24 + g(3)) * 60 + g(4)) * 60_000L + g(5) * 1000L
    }

    private fun rruleToRepeat(r: String?): Repeat? {
        if (r.isNullOrBlank()) return Repeat.NONE
        val parts = r.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
        val simple = parts.keys.all { it in setOf("FREQ", "WKST", "BYDAY", "BYMONTHDAY", "BYMONTH") } &&
            (parts["INTERVAL"] == null)
        if (!simple) return null
        return when (parts["FREQ"]) {
            "DAILY" -> Repeat.DAILY
            "WEEKLY" -> if ((parts["BYDAY"] ?: "").contains(',')) null else Repeat.WEEKLY
            "MONTHLY" -> if (parts["BYDAY"] != null) null else Repeat.MONTHLY
            "YEARLY" -> Repeat.YEARLY
            else -> null
        }
    }

    private fun repeatToRrule(r: Repeat, start: LocalDate): String? = when (r) {
        Repeat.NONE -> null
        Repeat.DAILY -> "FREQ=DAILY"
        Repeat.WEEKLY -> "FREQ=WEEKLY;WKST=MO;BYDAY=" + listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")[start.dayOfWeek.value - 1]
        Repeat.MONTHLY -> "FREQ=MONTHLY;BYMONTHDAY=${start.dayOfMonth}"
        Repeat.YEARLY -> "FREQ=YEARLY"
    }

    companion object {
        /** Startzeit für einen neuen Termin am gewählten Tag (wie Samsung: nächste volle Stunde). */
        fun newDraft(day: LocalDate, hour: Int? = null, calendarId: Long?, reminder: Int): EventDraft {
            val now = LocalDateTime.now()
            val start = when {
                hour != null -> day.atTime(hour, 0)
                day == now.toLocalDate() -> now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
                else -> day.atTime(LocalTime.of(9, 0))
            }
            return EventDraft(start = start, end = start.plusHours(1), calendarId = calendarId, reminder = reminder)
        }
    }
}
