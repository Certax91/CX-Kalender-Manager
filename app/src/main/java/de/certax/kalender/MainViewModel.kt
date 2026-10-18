package de.certax.kalender

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

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
        get() = sp.getBoolean("tasks_done", false)
        set(v) = sp.edit().putBoolean("tasks_done", v).apply()

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
    val prefs = Prefs(app)

    var hasCalendarPermission by mutableStateOf(false)
    var hasContactsPermission by mutableStateOf(false)

    var month by mutableStateOf(YearMonth.now())
    var selectedDay by mutableStateOf(LocalDate.now())

    var calendars by mutableStateOf<List<CalendarInfo>>(emptyList())
    var hidden by mutableStateOf(prefs.hiddenCalendars)

    var showBirthdays by mutableStateOf(prefs.showBirthdays)
    var onlyGoogleBirthdays by mutableStateOf(prefs.onlyGoogleBirthdays)
    var showTasks by mutableStateOf(prefs.showTasks)
    var showDoneTasks by mutableStateOf(prefs.showDoneTasks)

    var monthEntries by mutableStateOf<List<Entry>>(emptyList())
    var agendaEntries by mutableStateOf<List<Entry>>(emptyList())
    var tasks by mutableStateOf<List<Task>>(emptyList())
    var taskStatus by mutableStateOf("noch nicht geladen")
    var birthdayCount by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private var birthdays: List<Birthday> = emptyList()

    val agendaDays = 120L

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

    /** Nur die Einträge für Monat und Liste neu berechnen. */
    fun refreshEntries() {
        viewModelScope.launch {
            try {
                val m = month
                val gridFrom = m.atDay(1).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
                val gridTo = gridFrom.plusDays(41)
                val agFrom = LocalDate.now()
                val agTo = agFrom.plusDays(agendaDays)
                val visibleIds = calendars.map { it.id }.filter { it !in hidden }.toSet()
                val (mon, ag) = withContext(Dispatchers.IO) {
                    collect(gridFrom, gridTo, visibleIds) to collect(agFrom, agTo, visibleIds)
                }
                monthEntries = mon
                agendaEntries = ag
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

    fun changeMonth(delta: Long) {
        month = month.plusMonths(delta)
        selectedDay = if (month == YearMonth.now()) LocalDate.now() else month.atDay(1)
        refreshEntries()
    }

    fun goToday() {
        month = YearMonth.now(); selectedDay = LocalDate.now(); refreshEntries()
    }

    fun select(day: LocalDate) {
        selectedDay = day
        val ym = YearMonth.from(day)
        if (ym != month) { month = ym; refreshEntries() }
    }

    fun setCalendarVisible(id: Long, visible: Boolean) {
        val h = hidden.toMutableSet()
        if (visible) h -= id else h += id
        hidden = h; prefs.hiddenCalendars = h
        refreshEntries()
    }

    fun setShowBirthdays(v: Boolean) { showBirthdays = v; prefs.showBirthdays = v; refreshEntries() }
    fun setOnlyGoogle(v: Boolean) { onlyGoogleBirthdays = v; prefs.onlyGoogleBirthdays = v; reloadAll() }
    fun setShowTasks(v: Boolean) { showTasks = v; prefs.showTasks = v; refreshEntries() }
    fun setShowDone(v: Boolean) { showDoneTasks = v; prefs.showDoneTasks = v; refreshEntries() }
}
