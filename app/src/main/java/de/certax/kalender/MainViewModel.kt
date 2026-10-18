package de.certax.kalender

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/** Ansichten wie im Samsung Kalender. */
enum class CalView(val label: String) {
    YEAR("Jahr"), MONTH("Monat"), WEEK("Woche"), DAY("Tag"), LIST("Termine"), TASKS("Aufgaben")
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cx_kalender", Context.MODE_PRIVATE)

    var showBirthdays: Boolean
        get() = sp.getBoolean("birthdays", true)
        set(v) = sp.edit().putBoolean("birthdays", v).apply()
    var onlyGoogleBirthdays: Boolean
        get() = sp.getBoolean("only_google", false)
        set(v) = sp.edit().putBoolean("only_google", v).apply()
    var showTasks: Boolean
        get() = sp.getBoolean("tasks", true)
        set(v) = sp.edit().putBoolean("tasks", v).apply()
    var showDoneTasks: Boolean
        get() = sp.getBoolean("tasks_done", true)
        set(v) = sp.edit().putBoolean("tasks_done", v).apply()
    var lastView: CalView
        get() = runCatching { CalView.valueOf(sp.getString("view", "MONTH")!!) }.getOrDefault(CalView.MONTH)
        set(v) = sp.edit().putString("view", v.name).apply()
    var lastCalendarId: Long
        get() = sp.getLong("last_cal", -1L)
        set(v) = sp.edit().putLong("last_cal", v).apply()
    var defaultReminder: Int
        get() = sp.getInt("reminder", 15)
        set(v) = sp.edit().putInt("reminder", v).apply()

    /** Kalender, die der Nutzer ausgeblendet hat (alles andere ist sichtbar). */
    var hiddenCalendars: Set<Long>
        get() = sp.getStringSet("hidden", null)?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", v.map { it.toString() }.toSet()).apply()

    /** Kalender, die schon einmal gesehen wurden (für Standardwerte bei neuen Kalendern). */
    var knownCalendars: Set<Long>
        get() = sp.getStringSet("known", null)?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("known", v.map { it.toString() }.toSet()).apply()
}

private class LoadResult(
    val calendars: List<CalendarInfo>,
    val hidden: Set<Long>,
    val tasks: List<Task>,
    val taskStatus: String,
    val birthdays: List<Birthday>,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository(app)
    val store = EventStore(app.contentResolver)
    val prefs = Prefs(app)

    var hasCalendarPermission by mutableStateOf(false)
    var hasContactsPermission by mutableStateOf(false)
    var hasWritePermission by mutableStateOf(false)

    var view by mutableStateOf(prefs.lastView)
        private set
    var selectedDay by mutableStateOf(LocalDate.now())
        private set
    /** Vorherige Ansicht, damit „Zurück“ aus Tag/Woche wieder dorthin führt. */
    private val viewStack = ArrayDeque<CalView>()

    var calendars by mutableStateOf<List<CalendarInfo>>(emptyList())
    var hidden by mutableStateOf(prefs.hiddenCalendars)

    var showBirthdays by mutableStateOf(prefs.showBirthdays)
    var onlyGoogleBirthdays by mutableStateOf(prefs.onlyGoogleBirthdays)
    var showTasks by mutableStateOf(prefs.showTasks)
    var showDoneTasks by mutableStateOf(prefs.showDoneTasks)

    /** Einträge für den sichtbaren Zeitraum der aktuellen Ansicht. */
    var entries by mutableStateOf<List<Entry>>(emptyList())
    var rangeFrom by mutableStateOf(LocalDate.now())
    var rangeTo by mutableStateOf(LocalDate.now())
    var tasks by mutableStateOf<List<Task>>(emptyList())
    var taskStatus by mutableStateOf("noch nicht geladen")
    var birthdayCount by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private var birthdays: List<Birthday> = emptyList()
    private var refreshJob: Job? = null

    val agendaDays = 120L
    val month: YearMonth get() = YearMonth.from(selectedDay)

    val writableCalendars: List<CalendarInfo> get() = calendars.filter { it.writable }
    val holidayCalendarIds: Set<Long> get() = calendars.filter { it.isHoliday }.map { it.id }.toSet()

    /** Suche in Terminen (1 Jahr zurück, 2 Jahre voraus), Geburtstagen und Aufgaben. */
    suspend fun search(query: String): List<Entry> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val from = LocalDate.now().minusYears(1)
        val to = LocalDate.now().plusYears(2)
        val ids = calendars.map { it.id }.filter { it !in hidden }.toSet()
        return withContext(Dispatchers.IO) {
            val all = mutableListOf<Entry>()
            if (hasCalendarPermission) all += repo.loadEvents(from, to, ids)
            all += birthdayEntries(birthdays, LocalDate.now(), LocalDate.now().plusYears(1).minusDays(1))
            all += taskEntries(tasks, from, to, true)
            all.filter {
                it.title.contains(q, true) || (it.location?.contains(q, true) == true) ||
                    (it.description?.contains(q, true) == true)
            }.sortedWith(compareBy({ it.startDate }, { it.startTime }))
        }
    }

    /** Standardkalender für neue Termine: zuletzt benutzt, sonst Samsung/Gerät, sonst Google. */
    fun defaultCalendarId(): Long? {
        val w = writableCalendars
        w.firstOrNull { it.id == prefs.lastCalendarId }?.let { return it.id }
        return (w.firstOrNull { it.accountType.startsWith("com.osp.app.signin") }
            ?: w.firstOrNull { it.accountType == "com.google" && it.name == it.account }
            ?: w.firstOrNull())?.id
    }

    /** Alles neu einlesen (beim Start / Zurückkehren in die App). */
    fun reloadAll() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val calPerm = hasCalendarPermission
                val conPerm = hasContactsPermission
                val onlyGoogle = onlyGoogleBirthdays
                val r = withContext(Dispatchers.IO) {
                    var cals = emptyList<CalendarInfo>()
                    var t: Pair<List<Task>, String> = emptyList<Task>() to "Kalender-Berechtigung fehlt"
                    if (calPerm) {
                        cals = repo.loadCalendars()
                        // Neue Kalender: Kontakt-Geburtstagskalender standardmäßig aus (sonst doppelt)
                        val known = prefs.knownCalendars
                        val newOnes = cals.filter { it.id !in known }
                        if (newOnes.isNotEmpty()) {
                            val h = prefs.hiddenCalendars.toMutableSet()
                            newOnes.filter { it.isContactsBirthday }.forEach { h += it.id }
                            prefs.hiddenCalendars = h
                            prefs.knownCalendars = known + newOnes.map { it.id }
                        }
                        t = try { repo.loadSamsungTasks() } catch (e: Exception) {
                            emptyList<Task>() to "Fehler: ${e.message}"
                        }
                    }
                    val b = if (conPerm) {
                        try { repo.loadBirthdays(onlyGoogle) } catch (_: Exception) { emptyList() }
                    } else emptyList()
                    LoadResult(cals, prefs.hiddenCalendars, t.first, t.second, b)
                }
                calendars = r.calendars
                hidden = r.hidden
                tasks = r.tasks
                taskStatus = r.taskStatus
                birthdays = r.birthdays
                birthdayCount = r.birthdays.size
                refreshEntries()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    /** Zeitraum, den die aktuelle Ansicht braucht. */
    private fun range(): Pair<LocalDate, LocalDate> {
        val d = selectedDay
        return when (view) {
            CalView.YEAR -> LocalDate.of(d.year, 1, 1) to LocalDate.of(d.year, 12, 31)
            CalView.MONTH -> {
                val first = d.withDayOfMonth(1)
                val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
                start to start.plusDays(41)
            }
            CalView.WEEK -> {
                val start = weekStart(d)
                start to start.plusDays(6)
            }
            CalView.DAY -> d to d
            CalView.LIST, CalView.TASKS -> LocalDate.now() to LocalDate.now().plusDays(agendaDays)
        }
    }

    /** Einträge für den sichtbaren Zeitraum neu laden. */
    fun refreshEntries() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val (from, to) = range()
                val visibleIds = calendars.map { it.id }.filter { it !in hidden }.toSet()
                val list = withContext(Dispatchers.IO) { collect(from, to, visibleIds) }
                rangeFrom = from
                rangeTo = to
                entries = list
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    private fun collect(from: LocalDate, to: LocalDate, ids: Set<Long>): List<Entry> {
        val list = mutableListOf<Entry>()
        if (hasCalendarPermission) list += repo.loadEvents(from, to, ids)
        if (showBirthdays) list += birthdayEntries(birthdays, from, to)
        if (showTasks) list += taskEntries(tasks, from, to, showDoneTasks)
        return list
    }

    fun entriesOn(day: LocalDate): List<Entry> = entries.filter { it.occursOn(day) }.sortedForDay()

    // ---------------- Navigation ----------------

    fun switchView(v: CalView, keepStack: Boolean = false) {
        if (!keepStack) viewStack.clear()
        view = v
        prefs.lastView = v
        refreshEntries()
    }

    /** Wechsel in eine Detailansicht (Jahr → Monat, Monat → Tag …), „Zurück“ kehrt zurück. */
    fun drillDown(v: CalView, day: LocalDate) {
        viewStack.addLast(view)
        selectedDay = day
        view = v
        refreshEntries()
    }

    /** true, wenn „Zurück“ verarbeitet wurde. */
    fun back(): Boolean {
        val prev = viewStack.removeLastOrNull() ?: return false
        view = prev
        refreshEntries()
        return true
    }

    fun step(delta: Int) {
        selectedDay = when (view) {
            CalView.YEAR -> selectedDay.plusYears(delta.toLong())
            CalView.MONTH -> selectedDay.withDayOfMonth(1).plusMonths(delta.toLong())
                .let { if (YearMonth.from(it) == YearMonth.now()) LocalDate.now() else it }
            CalView.WEEK -> selectedDay.plusWeeks(delta.toLong())
            CalView.DAY -> selectedDay.plusDays(delta.toLong())
            else -> selectedDay
        }
        refreshEntries()
    }

    fun goToday() {
        selectedDay = LocalDate.now(); refreshEntries()
    }

    fun select(day: LocalDate) {
        val (from, to) = range()
        selectedDay = day
        if (day.isBefore(from) || day.isAfter(to) || (view == CalView.MONTH && YearMonth.from(day) != month)) refreshEntries()
    }

    // ---------------- Einstellungen ----------------

    fun setCalendarVisible(id: Long, visible: Boolean) {
        val h = hidden.toMutableSet()
        if (visible) h -= id else h += id
        hidden = h; prefs.hiddenCalendars = h
        refreshEntries()
    }

    fun changeShowBirthdays(v: Boolean) { showBirthdays = v; prefs.showBirthdays = v; refreshEntries() }
    fun changeOnlyGoogle(v: Boolean) { onlyGoogleBirthdays = v; prefs.onlyGoogleBirthdays = v; reloadAll() }
    fun changeShowTasks(v: Boolean) { showTasks = v; prefs.showTasks = v; refreshEntries() }
    fun changeShowDone(v: Boolean) { showDoneTasks = v; prefs.showDoneTasks = v; refreshEntries() }

    // ---------------- Termine bearbeiten ----------------

    suspend fun loadDraft(eventId: Long): EventDraft? = withContext(Dispatchers.IO) { store.load(eventId) }

    suspend fun saveDraft(d: EventDraft): Result<Long> = withContext(Dispatchers.IO) {
        runCatching { store.save(d) }
    }.also {
        if (it.isSuccess) {
            d.calendarId?.let { id -> prefs.lastCalendarId = id }
            prefs.defaultReminder = d.reminder
            // ausgeblendeten Zielkalender sichtbar machen, damit der neue Termin auftaucht
            d.calendarId?.let { id -> if (id in hidden) setCalendarVisible(id, true) }
            selectedDay = d.start.toLocalDate()
            refreshEntries()
        }
    }

    suspend fun deleteEvent(e: Entry, onlyThis: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = e.eventId ?: error("Kein Termin")
            if (onlyThis && e.recurring) store.deleteOccurrence(id, e.beginMillis) else store.delete(id)
        }
    }.also { refreshEntries() }
}

fun weekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())
