package de.certax.kalender

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class SourceType { CALENDAR, BIRTHDAY, TASK }

/** Ein Eintrag, egal aus welcher Quelle. */
data class Entry(
    val key: String,
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate,          // inklusive
    val startTime: LocalDateTime?,   // null = ganztägig
    val endTime: LocalDateTime?,
    val color: Int,
    val source: SourceType,
    val sourceName: String,
    val location: String? = null,
    val description: String? = null,
    val eventId: Long? = null,
    val beginMillis: Long = 0,
    val endMillis: Long = 0,
    val contactId: Long? = null,
    val completed: Boolean = false,
    val calendarId: Long? = null,
    val recurring: Boolean = false,
) {
    val allDay get() = startTime == null
    fun occursOn(d: LocalDate) = !d.isBefore(startDate) && !d.isAfter(endDate)
}

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    val color: Int,
    val isContactsBirthday: Boolean,
    val writable: Boolean = false,
    val isHoliday: Boolean = false,
)

data class Birthday(
    val contactId: Long,
    val name: String,
    val month: Int,
    val day: Int,
    val year: Int?,
    val accountType: String,
)

data class Task(
    val id: Long,
    val title: String,
    val due: LocalDate?,
    val completed: Boolean,
    val listName: String,
    val body: String?,
)

private fun Cursor.str(col: String): String? {
    val i = getColumnIndex(col); return if (i >= 0 && !isNull(i)) getString(i) else null
}
private fun Cursor.lng(col: String): Long? {
    val i = getColumnIndex(col); return if (i >= 0 && !isNull(i)) getLong(i) else null
}
private fun Cursor.int(col: String): Int? {
    val i = getColumnIndex(col); return if (i >= 0 && !isNull(i)) getInt(i) else null
}

const val COLOR_BIRTHDAY = 0xFFA8801E.toInt()
const val COLOR_TASK = 0xFF3A8A86.toInt()

class Repository(context: Context) {
    private val cr: ContentResolver = context.contentResolver
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // ---------------- Kalender (Google + Samsung, über den Android-Kalenderspeicher) ----------------

    fun loadCalendars(): List<CalendarInfo> {
        val out = mutableListOf<CalendarInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        cr.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val owner = c.str(CalendarContract.Calendars.OWNER_ACCOUNT) ?: ""
                val name = c.str(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME) ?: "Kalender"
                out += CalendarInfo(
                    id = c.lng(CalendarContract.Calendars._ID) ?: continue,
                    name = name,
                    account = c.str(CalendarContract.Calendars.ACCOUNT_NAME) ?: "",
                    accountType = c.str(CalendarContract.Calendars.ACCOUNT_TYPE) ?: "",
                    color = c.int(CalendarContract.Calendars.CALENDAR_COLOR) ?: 0xFF1F5FBF.toInt(),
                    isContactsBirthday = owner.contains("#contacts@") ||
                        owner.contains("addressbook#contacts"),
                    writable = (c.int(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL) ?: 0) >=
                        CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR &&
                        !owner.contains("#contacts@") && !owner.contains("#holiday@"),
                    isHoliday = owner.contains("#holiday@") || name.contains("feiertag", true) ||
                        name.contains("holiday", true),
                )
            }
        }
        return out.sortedWith(compareBy({ accountLabel(it.accountType, it.account) }, { it.name.lowercase() }))
    }

    fun loadEvents(from: LocalDate, to: LocalDate, calendarIds: Set<Long>): List<Entry> {
        if (calendarIds.isEmpty()) return emptyList()
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.RRULE,
        )
        val out = mutableListOf<Entry>()
        cr.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val calId = c.lng(CalendarContract.Instances.CALENDAR_ID) ?: continue
                if (calId !in calendarIds) continue
                val b = c.lng(CalendarContract.Instances.BEGIN) ?: continue
                val e = c.lng(CalendarContract.Instances.END) ?: b
                val allDay = (c.int(CalendarContract.Instances.ALL_DAY) ?: 0) == 1
                val eventId = c.lng(CalendarContract.Instances.EVENT_ID)
                val entry = if (allDay) {
                    // Ganztägige Termine sind in UTC gespeichert
                    val sd = Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate()
                    var ed = Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                    if (ed.isBefore(sd)) ed = sd
                    Entry("e$eventId-$b", c.str(CalendarContract.Instances.TITLE).orEmpty().ifBlank { "(ohne Titel)" },
                        sd, ed, null, null,
                        c.int(CalendarContract.Instances.DISPLAY_COLOR) ?: 0xFF1F5FBF.toInt(),
                        SourceType.CALENDAR, c.str(CalendarContract.Instances.CALENDAR_DISPLAY_NAME) ?: "",
                        c.str(CalendarContract.Instances.EVENT_LOCATION), c.str(CalendarContract.Instances.DESCRIPTION),
                        eventId, b, e, calendarId = calId,
                        recurring = !c.str(CalendarContract.Instances.RRULE).isNullOrBlank())
                } else {
                    val st = LocalDateTime.ofInstant(Instant.ofEpochMilli(b), zone)
                    val et = LocalDateTime.ofInstant(Instant.ofEpochMilli(maxOf(e, b)), zone)
                    var ed = LocalDateTime.ofInstant(Instant.ofEpochMilli(maxOf(e - 1, b)), zone).toLocalDate()
                    if (ed.isBefore(st.toLocalDate())) ed = st.toLocalDate()
                    Entry("e$eventId-$b", c.str(CalendarContract.Instances.TITLE).orEmpty().ifBlank { "(ohne Titel)" },
                        st.toLocalDate(), ed, st, et,
                        c.int(CalendarContract.Instances.DISPLAY_COLOR) ?: 0xFF1F5FBF.toInt(),
                        SourceType.CALENDAR, c.str(CalendarContract.Instances.CALENDAR_DISPLAY_NAME) ?: "",
                        c.str(CalendarContract.Instances.EVENT_LOCATION), c.str(CalendarContract.Instances.DESCRIPTION),
                        eventId, b, e, calendarId = calId,
                        recurring = !c.str(CalendarContract.Instances.RRULE).isNullOrBlank())
                }
                if (!entry.endDate.isBefore(from) && !entry.startDate.isAfter(to)) out += entry
            }
        }
        return out
    }

    // ---------------- Geburtstage aus den Kontakten ----------------

    fun loadBirthdays(onlyGoogle: Boolean): List<Birthday> {
        val proj = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Event.START_DATE,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
        )
        val sel = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
        val args = arrayOf(
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString(),
        )
        val map = LinkedHashMap<String, Birthday>()
        cr.query(ContactsContract.Data.CONTENT_URI, proj, sel, args, null)?.use { c ->
            while (c.moveToNext()) {
                val accType = c.str(ContactsContract.RawContacts.ACCOUNT_TYPE) ?: ""
                if (onlyGoogle && accType != "com.google") continue
                val name = c.str(ContactsContract.Data.DISPLAY_NAME) ?: continue
                val raw = c.str(ContactsContract.CommonDataKinds.Event.START_DATE) ?: continue
                val p = parseBirthday(raw) ?: continue
                val b = Birthday(c.lng(ContactsContract.Data.CONTACT_ID) ?: 0, name, p.first, p.second, p.third, accType)
                val k = "${name.lowercase()}|${b.month}|${b.day}"
                val old = map[k]
                if (old == null || (old.year == null && b.year != null) || (b.accountType == "com.google" && old.accountType != "com.google")) map[k] = b
            }
        }
        return map.values.toList()
    }

    /** Liefert (Monat, Tag, Jahr?) für die gängigen Formate. */
    private fun parseBirthday(raw: String): Triple<Int, Int, Int?>? {
        val s = raw.trim()
        Regex("""^--(\d{1,2})-?(\d{1,2})""").find(s)?.let { return check(it.groupValues[1].toInt(), it.groupValues[2].toInt(), null) }
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""").find(s)?.let { return check(it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[1].toInt()) }
        Regex("""^(\d{4})(\d{2})(\d{2})$""").find(s)?.let { return check(it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[1].toInt()) }
        Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})?""").find(s)?.let { return check(it.groupValues[2].toInt(), it.groupValues[1].toInt(), it.groupValues[3].toIntOrNull()) }
        Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})""").find(s)?.let { return check(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        return null
    }

    private fun check(m: Int, d: Int, y: Int?): Triple<Int, Int, Int?>? {
        if (m !in 1..12 || d !in 1..31) return null
        val year = y?.takeIf { it in 1850..2100 && it != 1604 } // 1604 = Platzhalter von Android für "ohne Jahr"
        return Triple(m, d, year)
    }

    // ---------------- Samsung Aufgaben ----------------

    /**
     * Samsung speichert die Aufgaben aus dem Samsung Kalender in einer eigenen Tabelle
     * des Kalenderspeichers. Die ist nicht offiziell dokumentiert, deshalb wird hier
     * vorsichtig gelesen und jede Spalte optional behandelt.
     */
    fun loadSamsungTasks(): Pair<List<Task>, String> {
        val listNames = mutableMapOf<Long, String>()
        for (u in listOf("content://com.android.calendar/TasksAccounts", "content://com.android.calendar/taskGroup")) {
            try {
                cr.query(Uri.parse(u), null, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.lng("_id") ?: c.lng("_sync_account_key") ?: continue
                        val n = c.str("groupName") ?: c.str("displayName") ?: c.str("_sync_account") ?: continue
                        listNames.putIfAbsent(id, n)
                    }
                }
            } catch (_: Exception) { }
        }

        val uri = Uri.parse("content://com.android.calendar/syncTasks")
        val cursor = try {
            cr.query(uri, null, null, null, null)
        } catch (e: Exception) {
            return emptyList<Task>() to "nicht verfügbar (${e.javaClass.simpleName})"
        } ?: return emptyList<Task>() to "nicht verfügbar auf diesem Gerät"

        val out = mutableListOf<Task>()
        cursor.use { c ->
            while (c.moveToNext()) {
                if ((c.int("deleted") ?: 0) == 1) continue
                val title = c.str("subject") ?: c.str("title") ?: continue
                val dueMs = c.lng("utc_due_date") ?: c.lng("due_date")
                val due = dueMs?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                val group = c.lng("groupId") ?: c.lng("accountKey")
                out += Task(
                    id = c.lng("_id") ?: out.size.toLong(),
                    title = title,
                    due = due,
                    completed = (c.int("complete") ?: 0) == 1,
                    listName = group?.let { listNames[it] } ?: "Samsung Aufgaben",
                    body = c.str("body"),
                )
            }
        }
        return out to "${out.size} Aufgaben gefunden"
    }

    companion object {
        fun accountLabel(type: String, name: String): String = when {
            type == "com.google" -> "Google · $name"
            type.startsWith("com.osp.app.signin") -> "Samsung-Konto"
            type == CalendarContract.ACCOUNT_TYPE_LOCAL || name.contains("local", true) ||
                name.contains("My calendar", true) || name.contains("Mein Kalender", true) -> "Auf dem Gerät (Samsung)"
            type.contains("exchange", true) || type.contains("eas", true) -> "Exchange · $name"
            else -> name.ifBlank { type }
        }

        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** Erzeugt Geburtstags-Einträge für einen Zeitraum. */
fun birthdayEntries(list: List<Birthday>, from: LocalDate, to: LocalDate): List<Entry> {
    val out = mutableListOf<Entry>()
    for (year in from.year..to.year) {
        for (b in list) {
            val day = if (b.month == 2 && b.day == 29 && !java.time.Year.isLeap(year.toLong())) 28 else b.day
            val date = try { LocalDate.of(year, b.month, day) } catch (_: Exception) { continue }
            if (date.isBefore(from) || date.isAfter(to)) continue
            val age = b.year?.let { year - it }?.takeIf { it in 0..130 }
            out += Entry(
                key = "b${b.contactId}-$year-${b.name}",
                title = "${b.name} hat Geburtstag",
                startDate = date, endDate = date, startTime = null, endTime = null,
                color = COLOR_BIRTHDAY, source = SourceType.BIRTHDAY, sourceName = "Kontakt-Geburtstage",
                contactId = b.contactId,
                description = age?.let { "wird $it Jahre alt" },
            )
        }
    }
    return out
}

fun taskEntries(list: List<Task>, from: LocalDate, to: LocalDate, showDone: Boolean): List<Entry> =
    list.filter { it.due != null && (showDone || !it.completed) && !it.due.isBefore(from) && !it.due.isAfter(to) }
        .map {
            Entry(
                key = "t${it.id}", title = it.title,
                startDate = it.due!!, endDate = it.due, startTime = null, endTime = null,
                color = COLOR_TASK, source = SourceType.TASK, sourceName = it.listName,
                description = it.body, completed = it.completed,
            )
        }

fun reminderEntries(list: List<ReminderProbe.Reminder>, from: LocalDate, to: LocalDate, showDone: Boolean): List<Entry> =
    list.filter { it.time != null && (showDone || !it.completed) }
        .filter { val d = it.time!!.toLocalDate(); !d.isBefore(from) && !d.isAfter(to) }
        .map {
            val t = it.time!!
            Entry(
                key = "r${it.id}", title = it.title,
                startDate = t.toLocalDate(), endDate = t.toLocalDate(),
                startTime = null, endTime = null,
                color = COLOR_TASK, source = SourceType.TASK, sourceName = "Samsung Reminder",
                description = if (!it.allDay) "Erinnerung um " + t.format(Repository.TIME) + " Uhr" else null,
                completed = it.completed,
            )
        }

/** Sortierung innerhalb eines Tages: Geburtstage, ganztägig, Aufgaben, dann nach Uhrzeit. */
fun List<Entry>.sortedForDay(): List<Entry> = sortedWith(
    compareBy<Entry>(
        { if (it.source == SourceType.BIRTHDAY) 0 else if (it.allDay && it.source == SourceType.CALENDAR) 1 else if (it.source == SourceType.TASK) 2 else 3 },
        { it.startTime },
        { it.title.lowercase() },
    )
)
