package de.certax.kalender

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val EDIT_DATE = DateTimeFormatter.ofPattern("EE, d. MMM yyyy", DE)

private enum class Picker { START_DATE, START_TIME, END_DATE, END_TIME, CALENDAR, REPEAT, REMINDER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditor(vm: MainViewModel, initial: EventDraft, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf(initial) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val cals = vm.writableCalendars
    val cal = cals.firstOrNull { it.id == d.calendarId }

    val changed = d != initial
    BackHandler { if (changed) confirmDiscard = true else onClose() }

    fun save() {
        if (d.calendarId == null) {
            Toast.makeText(ctx, "Bitte einen Kalender wählen", Toast.LENGTH_SHORT).show(); return
        }
        saving = true
        scope.launch {
            val r = vm.saveDraft(d)
            saving = false
            r.onSuccess {
                Toast.makeText(ctx, "Gespeichert", Toast.LENGTH_SHORT).show()
                onClose()
            }.onFailure {
                Toast.makeText(ctx, "Fehler: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.id == null) "Neuer Termin" else "Termin bearbeiten") },
                navigationIcon = {
                    IconButton(onClick = { if (changed) confirmDiscard = true else onClose() }) {
                        Icon(Icons.Filled.Close, "Abbrechen")
                    }
                },
                actions = {
                    TextButton(onClick = { save() }, enabled = !saving) {
                        Icon(Icons.Filled.Done, null); Spacer(Modifier.width(4.dp)); Text("Speichern")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())

            // Titel
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(cal?.color ?: 0xFF888888.toInt())))
                Spacer(Modifier.width(12.dp))
                TextField(
                    value = d.title, onValueChange = { d = d.copy(title = it) },
                    placeholder = { Text("Titel", fontSize = 22.sp) },
                    textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Kalender
            EditorRow("Kalender", cal?.let { it.name + " · " + Repository.accountLabel(it.accountType, it.account) }
                ?: if (cals.isEmpty()) "Kein beschreibbarer Kalender gefunden" else "Kalender wählen") { picker = Picker.CALENDAR }
            HorizontalDivider()

            // Ganztägig
            Row(
                Modifier.fillMaxWidth().clickable { d = toggleAllDay(d) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ganztägig", Modifier.weight(1f))
                Switch(checked = d.allDay, onCheckedChange = { d = toggleAllDay(d) })
            }

            // Beginn / Ende
            DateTimeRow("Beginn", d.start, d.allDay, { picker = Picker.START_DATE }, { picker = Picker.START_TIME })
            DateTimeRow("Ende", d.end, d.allDay, { picker = Picker.END_DATE }, { picker = Picker.END_TIME })
            HorizontalDivider()

            EditorRow("Wiederholen", if (d.otherRrule != null && d.repeat == Repeat.NONE) "Benutzerdefiniert (bleibt erhalten)" else d.repeat.label) { picker = Picker.REPEAT }
            EditorRow("Erinnerung", reminderLabel(d.reminder)) { picker = Picker.REMINDER }
            HorizontalDivider()

            OutlinedTextField(
                value = d.location, onValueChange = { d = d.copy(location = it) },
                label = { Text("Ort") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            )
            OutlinedTextField(
                value = d.notes, onValueChange = { d = d.copy(notes = it) },
                label = { Text("Notiz") }, minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            )
            if (initial.id != null && (initial.repeat != Repeat.NONE || initial.otherRrule != null)) {
                Text(
                    "Hinweis: Änderungen gelten für alle Termine dieser Serie.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    // ------------------------------------------------ Auswahldialoge
    when (picker) {
        Picker.START_DATE, Picker.END_DATE -> {
            val isStart = picker == Picker.START_DATE
            val cur = (if (isStart) d.start else d.end).toLocalDate()
            val state = rememberDatePickerState(initialSelectedDateMillis = cur.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { ms ->
                            val nd = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            d = if (isStart) setStart(d, nd.atTime(d.start.toLocalTime())) else setEnd(d, nd.atTime(d.end.toLocalTime()))
                        }
                        picker = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("Abbrechen") } }
            ) { DatePicker(state = state, showModeToggle = false) }
        }
        Picker.START_TIME, Picker.END_TIME -> {
            val isStart = picker == Picker.START_TIME
            val cur = if (isStart) d.start else d.end
            val state = rememberTimePickerState(initialHour = cur.hour, initialMinute = cur.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { picker = null },
                title = { Text(if (isStart) "Beginn" else "Ende") },
                text = { TimePicker(state = state) },
                confirmButton = {
                    TextButton(onClick = {
                        val t = LocalTime.of(state.hour, state.minute)
                        d = if (isStart) setStart(d, cur.toLocalDate().atTime(t)) else setEnd(d, cur.toLocalDate().atTime(t))
                        picker = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("Abbrechen") } }
            )
        }
        Picker.CALENDAR -> ChoiceDialog(
            title = "Kalender",
            options = cals.map { it.id to (it.name + "\n" + Repository.accountLabel(it.accountType, it.account)) },
            colors = cals.associate { it.id to it.color },
            selected = d.calendarId,
            onPick = { d = d.copy(calendarId = it); picker = null },
            onDismiss = { picker = null }
        )
        Picker.REPEAT -> ChoiceDialog(
            title = "Wiederholen",
            options = Repeat.values().map { it to it.label },
            selected = if (d.otherRrule != null && d.repeat == Repeat.NONE) null else d.repeat,
            onPick = { d = d.copy(repeat = it, otherRrule = null); picker = null },
            onDismiss = { picker = null }
        )
        Picker.REMINDER -> ChoiceDialog(
            title = "Erinnerung",
            options = REMINDER_OPTIONS,
            selected = d.reminder,
            onPick = { d = d.copy(reminder = it); picker = null },
            onDismiss = { picker = null }
        )
        null -> {}
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Änderungen verwerfen?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Weiter bearbeiten") } }
        )
    }
}

/** Beginn ändern und das Ende mitverschieben (gleiche Dauer), wie im Samsung Kalender. */
private fun setStart(d: EventDraft, s: LocalDateTime): EventDraft {
    val dur = Duration.between(d.start, d.end).let { if (it.isNegative) Duration.ZERO else it }
    return d.copy(start = s, end = s.plus(dur))
}

private fun setEnd(d: EventDraft, e: LocalDateTime): EventDraft {
    val end = if (d.allDay) {
        if (e.toLocalDate().isBefore(d.start.toLocalDate())) d.start else e
    } else if (!e.isAfter(d.start)) d.start.plusHours(1) else e
    return d.copy(end = end)
}

private fun toggleAllDay(d: EventDraft): EventDraft = if (d.allDay) {
    val s = d.start.toLocalDate().atTime(9, 0)
    d.copy(allDay = false, start = s, end = d.end.toLocalDate().atTime(10, 0).let { if (it.isAfter(s)) it else s.plusHours(1) })
} else {
    d.copy(allDay = true, start = d.start.toLocalDate().atStartOfDay(), end = d.end.toLocalDate().atStartOfDay())
}

@Composable
private fun EditorRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f))
    }
}

@Composable
private fun DateTimeRow(label: String, dt: LocalDateTime, allDay: Boolean, onDate: () -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(70.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onDate, modifier = Modifier.weight(1f)) {
            Text(dt.toLocalDate().format(EDIT_DATE), fontSize = 16.sp)
        }
        if (!allDay) TextButton(onClick = onTime) { Text(dt.format(Repository.TIME), fontSize = 16.sp) }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    colors: Map<T, Int> = emptyMap(),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = { onPick(value) })
                        colors[value]?.let {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(it)))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

/** Hilfsfunktion: neuer Entwurf am Tag mit Standardwerten. */
fun MainViewModel.newDraft(day: LocalDate, hour: Int? = null): EventDraft =
    EventStore.newDraft(day, hour, defaultCalendarId(), prefs.defaultReminder)
