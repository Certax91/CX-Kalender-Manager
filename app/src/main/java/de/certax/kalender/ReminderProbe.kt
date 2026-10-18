package de.certax.kalender

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Samsung Reminder (com.samsung.android.app.reminder) hat keine offizielle Schnittstelle.
 * Diese Klasse sucht die Datenquellen der App auf dem Gerät, versucht sie zu lesen und
 * erstellt einen Diagnosebericht. Wenn eine Quelle lesbar ist, werden die Erinnerungen
 * daraus als Einträge übernommen.
 */
class ReminderProbe(private val context: Context) {

    data class Result(val reminders: List<Reminder>, val status: String, val report: String)

    data class Reminder(
        val id: String,
        val title: String,
        val time: LocalDateTime?,
        val allDay: Boolean,
        val completed: Boolean,
    )

    private val packages = listOf("com.samsung.android.app.reminder")
    private val paths = listOf(
        "", "reminder", "reminders", "item", "items", "card", "cards", "todo", "todos",
        "task", "tasks", "event", "events", "alarm", "alarms", "data", "list", "all",
        "search_suggest_query", "reminder_list", "card_data",
    )
    private val titleCols = listOf("title", "text", "subject", "name", "contents", "content", "card_title", "suggest_text_1", "reminder_title")
    private val timeCols = listOf("due_date", "dueDate", "alarm_time", "alarmTime", "reminder_time", "remind_time", "event_time", "start_time", "time", "date", "dtstart", "utc_due_date")
    private val doneCols = listOf("is_completed", "completed", "complete", "done", "is_done", "status")

    fun run(): Result {
        val sb = StringBuilder()
        sb.append("Gerät: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
        val found = mutableListOf<Reminder>()
        var readable = 0
        var providersSeen = 0

        for (pkg in packages) {
            val info = try {
                if (Build.VERSION.SDK_INT >= 33)
                    context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()))
                else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(pkg, PackageManager.GET_PROVIDERS)
            } catch (e: Exception) {
                sb.append("App $pkg: nicht gefunden (${e.javaClass.simpleName})\n"); null
            } ?: continue
            sb.append("App $pkg Version ${info.versionName}\n")
            val providers = info.providers.orEmpty()
            if (providers.isEmpty()) sb.append("  keine Datenquellen sichtbar\n")
            for (p in providers) {
                providersSeen++
                sb.append("• ${p.authority}\n  exportiert=${p.exported}, Leserecht=${p.readPermission ?: "-"}\n")
                val auths = (p.authority ?: "").split(';').filter { it.isNotBlank() }
                for (auth in auths) for (path in paths) {
                    val uri = Uri.parse("content://$auth" + if (path.isEmpty()) "" else "/$path")
                    try {
                        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            readable++
                            sb.append("  ✓ $uri: ${c.count} Zeilen, Spalten: ${c.columnNames.take(25).joinToString(",")}\n")
                            found += parse(c, auth + path)
                        }
                    } catch (e: SecurityException) {
                        if (path.isEmpty()) sb.append("  ✗ kein Zugriff: ${e.message?.take(120)}\n")
                        break // ohne Berechtigung bringen weitere Pfade nichts
                    } catch (_: Exception) { /* Pfad existiert nicht */ }
                }
            }
        }

        val unique = found.distinctBy { it.title to it.time }
        val status = when {
            unique.isNotEmpty() -> "${unique.size} Erinnerungen gefunden"
            readable > 0 -> "Datenquelle lesbar, aber keine Erinnerungen erkannt – bitte Diagnose schicken"
            providersSeen > 0 -> "Samsung schützt die Reminder-Daten (kein Zugriff für fremde Apps)"
            else -> "Reminder-App nicht gefunden"
        }
        return Result(unique, status, sb.toString())
    }

    private fun parse(c: Cursor, src: String): List<Reminder> {
        val names = c.columnNames.map { it.lowercase() }
        fun idx(cands: List<String>) = cands.map { it.lowercase() }.firstNotNullOfOrNull { n -> names.indexOf(n).takeIf { it >= 0 } }
        val ti = idx(titleCols) ?: return emptyList()
        val tm = idx(timeCols)
        val dn = idx(doneCols)
        val idI = names.indexOf("_id")
        val out = mutableListOf<Reminder>()
        var n = 0
        while (c.moveToNext() && n++ < 2000) {
            val title = try { c.getString(ti) } catch (_: Exception) { null }?.takeIf { it.isNotBlank() } ?: continue
            var time: LocalDateTime? = null
            if (tm != null && !c.isNull(tm)) {
                val v = try { c.getLong(tm) } catch (_: Exception) { 0L }
                val ms = when { v > 100_000_000_000L -> v; v > 1_000_000_000L -> v * 1000; else -> 0L }
                if (ms > 0) time = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
            }
            val done = dn != null && !c.isNull(dn) && (try { c.getInt(dn) } catch (_: Exception) { 0 }) == 1
            val id = if (idI >= 0) c.getString(idI) else "$n"
            out += Reminder("$src-$id", title, time, time != null && time.hour == 0 && time.minute == 0, done)
        }
        return out
    }
}
