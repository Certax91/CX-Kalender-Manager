package de.certax.kalender

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val DE = Locale.GERMANY
private val DAY_HEADER = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", DE)
private val SHORT_DATE = DateTimeFormatter.ofPattern("EE, d. MMM", DE)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { App(vm) }
        }
    }
}

private fun Context.granted(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

private enum class Tab { MONTH, LIST, TASKS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.MONTH) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Entry?>(null) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.hasCalendarPermission = ctx.granted(Manifest.permission.READ_CALENDAR)
        vm.hasContactsPermission = ctx.granted(Manifest.permission.READ_CONTACTS)
        vm.reloadAll()
    }

    // Bei jedem Zurückkehren in die App neu laden
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                vm.hasCalendarPermission = ctx.granted(Manifest.permission.READ_CALENDAR)
                vm.hasContactsPermission = ctx.granted(Manifest.permission.READ_CONTACTS)
                if ((!vm.hasCalendarPermission || !vm.hasContactsPermission) && !askedOnce) {
                    askedOnce = true
                    launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.READ_CONTACTS))
                } else vm.reloadAll()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsScreen(vm, onBack = { settingsOpen = false }, onRequestPermissions = {
            launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.READ_CONTACTS))
        })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            Tab.MONTH -> vm.month.month.getDisplayName(TextStyle.FULL_STANDALONE, DE)
                                .replaceFirstChar { it.uppercase() } + " " + vm.month.year
                            Tab.LIST -> "Demnächst"
                            Tab.TASKS -> "Samsung Aufgaben"
                        }, fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    if (tab == Tab.MONTH) {
                        IconButton(onClick = { vm.changeMonth(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vorheriger Monat") }
                        TextButton(onClick = { vm.goToday() }) { Text("Heute") }
                        IconButton(onClick = { vm.changeMonth(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nächster Monat") }
                    } else {
                        IconButton(onClick = { vm.reloadAll() }) { Icon(Icons.Filled.Refresh, "Neu laden") }
                    }
                    IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Filled.Settings, "Einstellungen") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == Tab.MONTH, { tab = Tab.MONTH }, { Icon(Icons.Filled.DateRange, null) }, label = { Text("Monat") })
                NavigationBarItem(tab == Tab.LIST, { tab = Tab.LIST }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Liste") })
                NavigationBarItem(tab == Tab.TASKS, { tab = Tab.TASKS }, { Icon(Icons.Filled.CheckCircle, null) }, label = { Text("Aufgaben") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let {
                Text("Fehler: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }
            if (!vm.hasCalendarPermission && !vm.hasContactsPermission) {
                PermissionHint { openAppSettings(ctx) }
            } else when (tab) {
                Tab.MONTH -> MonthScreen(vm) { detail = it }
                Tab.LIST -> AgendaScreen(vm) { detail = it }
                Tab.TASKS -> TasksScreen(vm)
            }
        }
    }

    detail?.let { e -> DetailDialog(e, onDismiss = { detail = null }) }
}

@Composable
private fun PermissionHint(onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Die App braucht Zugriff auf Kalender und Kontakte, um deine Termine, Geburtstage und Aufgaben anzuzeigen.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpen) { Text("Berechtigungen in den Einstellungen erlauben") }
    }
}

private fun openAppSettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
}

// ---------------------------------------------------------------- Monat

@Composable
private fun MonthScreen(vm: MainViewModel, onEntry: (Entry) -> Unit) {
    val month = vm.month
    val first = month.atDay(1)
    val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val today = LocalDate.now()
    val entries = vm.monthEntries
    val byDay = remember(entries, gridStart) {
        (0 until 42).associate { i ->
            val d = gridStart.plusDays(i.toLong())
            d to entries.filter { it.occursOn(d) }.sortedForDay()
        }
    }
    val weeks = if (gridStart.plusDays(35).month == month.month) 6 else if (gridStart.plusDays(28).month == month.month) 5 else 4

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            DayOfWeek.values().forEach { dow ->
                Text(
                    dow.getDisplayName(TextStyle.SHORT, DE).removeSuffix("."),
                    Modifier.weight(1f), fontSize = 12.sp,
                    color = if (dow == DayOfWeek.SUNDAY || dow == DayOfWeek.SATURDAY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        var dragSum by remember { mutableFloatStateOf(0f) }
        Column(
            Modifier.padding(horizontal = 4.dp).pointerInput(month) {
                detectHorizontalDragGestures(
                    onDragStart = { dragSum = 0f },
                    onDragEnd = {
                        if (dragSum > 120) vm.changeMonth(-1) else if (dragSum < -120) vm.changeMonth(1)
                    },
                    onHorizontalDrag = { _, d -> dragSum += d }
                )
            }
        ) {
            for (w in 0 until weeks) {
                Row(Modifier.fillMaxWidth().height(74.dp)) {
                    for (dIdx in 0 until 7) {
                        val day = gridStart.plusDays((w * 7 + dIdx).toLong())
                        DayCell(
                            day = day,
                            inMonth = day.month == month.month,
                            isToday = day == today,
                            isSelected = day == vm.selectedDay,
                            entries = byDay[day].orEmpty(),
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { vm.select(day) }
                        )
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
        val sel = vm.selectedDay
        val list = byDay[sel] ?: entries.filter { it.occursOn(sel) }.sortedForDay()
        Text(
            sel.format(DAY_HEADER), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
        )
        if (list.isEmpty()) {
            Text("Keine Einträge", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.key }) { EntryRow(it, sel) { onEntry(it) } }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate, inMonth: Boolean, isToday: Boolean, isSelected: Boolean,
    entries: List<Entry>, modifier: Modifier, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) cs.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (isToday) cs.primary else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${day.dayOfMonth}", fontSize = 12.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isToday -> cs.onPrimary
                    !inMonth -> cs.onSurface.copy(alpha = 0.35f)
                    else -> cs.onSurface
                }
            )
        }
        entries.take(3).forEach { e ->
            Text(
                e.title, maxLines = 1, overflow = TextOverflow.Clip, fontSize = 9.sp, lineHeight = 11.sp,
                color = Color.White,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(e.color).copy(alpha = if (inMonth) 1f else 0.45f))
                    .padding(horizontal = 2.dp)
            )
        }
        if (entries.size > 3) Text("+${entries.size - 3}", fontSize = 9.sp, lineHeight = 10.sp, color = cs.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Liste

@Composable
private fun AgendaScreen(vm: MainViewModel, onEntry: (Entry) -> Unit) {
    val today = LocalDate.now()
    val groups = remember(vm.agendaEntries) {
        (0..vm.agendaDays).mapNotNull { i ->
            val d = today.plusDays(i)
            val l = vm.agendaEntries.filter { it.occursOn(d) }.sortedForDay()
            if (l.isEmpty()) null else d to l
        }
    }
    if (groups.isEmpty()) {
        Text("Keine Einträge in den nächsten ${vm.agendaDays} Tagen", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        groups.forEach { (d, l) ->
            item(key = "h$d") {
                val label = when (d) {
                    today -> "Heute · " + d.format(SHORT_DATE)
                    today.plusDays(1) -> "Morgen · " + d.format(SHORT_DATE)
                    else -> d.format(SHORT_DATE)
                }
                Text(
                    label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .padding(start = 16.dp, top = 14.dp, bottom = 2.dp)
                )
            }
            items(l, key = { "$d-${it.key}" }) { EntryRow(it, d) { onEntry(it) } }
        }
    }
}

@Composable
private fun EntryRow(e: Entry, day: LocalDate, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(Color(e.color)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(52.dp)) {
            if (e.allDay) {
                Text(if (e.source == SourceType.TASK) "Fällig" else "ganzt.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val st = if (e.startDate == day) e.startTime!!.format(Repository.TIME) else "00:00"
                val et = if (e.endDate == day) e.endTime!!.format(Repository.TIME) else "24:00"
                Text(st, fontSize = 13.sp)
                Text(et, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                e.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (e.completed) TextDecoration.LineThrough else null
            )
            val sub = listOfNotNull(e.location?.takeIf { it.isNotBlank() }, e.sourceName.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- Aufgaben

@Composable
private fun TasksScreen(vm: MainViewModel) {
    val today = LocalDate.now()
    val list = vm.tasks.filter { vm.showDoneTasks || !it.completed }
        .sortedWith(compareBy<Task>({ it.completed }, { it.due == null }, { it.due }, { it.title.lowercase() }))
    if (list.isEmpty()) {
        Column(Modifier.padding(16.dp)) {
            Text("Keine offenen Aufgaben gefunden.")
            Spacer(Modifier.height(8.dp))
            Text("Status: ${vm.taskStatus}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.id }) { t ->
            val overdue = !t.completed && t.due != null && t.due.isBefore(today)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .border(2.dp, Color(COLOR_TASK), CircleShape)
                        .background(if (t.completed) Color(COLOR_TASK) else Color.Transparent)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, textDecoration = if (t.completed) TextDecoration.LineThrough else null)
                    val dueText = t.due?.let {
                        when (it) {
                            today -> "heute"
                            today.plusDays(1) -> "morgen"
                            else -> it.format(SHORT_DATE)
                        }
                    } ?: "ohne Datum"
                    Text(
                        "$dueText · ${t.listName}", fontSize = 12.sp,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(Modifier.padding(start = 50.dp))
        }
    }
}

// ---------------------------------------------------------------- Details

@Composable
private fun DetailDialog(e: Entry, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val dateText = if (e.allDay) {
        if (e.startDate == e.endDate) e.startDate.format(DAY_HEADER)
        else e.startDate.format(SHORT_DATE) + " – " + e.endDate.format(SHORT_DATE)
    } else {
        val s = e.startTime!!; val en = e.endTime!!
        if (s.toLocalDate() == en.toLocalDate())
            s.format(DAY_HEADER) + "\n" + s.format(Repository.TIME) + " – " + en.format(Repository.TIME) + " Uhr"
        else s.format(SHORT_DATE) + " " + s.format(Repository.TIME) + " – " + en.format(SHORT_DATE) + " " + en.format(Repository.TIME)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.title) },
        text = {
            Column {
                Text(dateText)
                e.location?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(8.dp)); Text("📍 $it") }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(e.color)))
                    Spacer(Modifier.width(6.dp))
                    Text(e.sourceName, fontSize = 13.sp)
                }
                e.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp)); Text(it.take(600), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            val canOpen = e.eventId != null || e.contactId != null
            if (canOpen) TextButton(onClick = {
                try {
                    val intent = if (e.eventId != null) {
                        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.beginMillis)
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endMillis)
                    } else {
                        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, e.contactId!!))
                    }
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(ctx, "Konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            }) { Text(if (e.eventId != null) "Im Kalender öffnen" else "Kontakt öffnen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } }
    )
}

// ---------------------------------------------------------------- Einstellungen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onRequestPermissions: () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quellen & Einstellungen") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (!vm.hasCalendarPermission || !vm.hasContactsPermission) {
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            if (!vm.hasCalendarPermission) Text("• Kalender-Zugriff fehlt (für Google-/Samsung-Kalender und Samsung Aufgaben)")
                            if (!vm.hasContactsPermission) Text("• Kontakte-Zugriff fehlt (für Geburtstage)")
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Button(onClick = onRequestPermissions) { Text("Erlauben") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { openAppSettings(ctx) }) { Text("App-Einstellungen") }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Geburtstage (Kontakte)") }
            item {
                SwitchRow("Kontakt-Geburtstage anzeigen", "${vm.birthdayCount} Geburtstage gefunden", vm.showBirthdays) { vm.setShowBirthdays(it) }
            }
            item {
                SwitchRow("Nur Google-Kontakte", "Kontakte aus Samsung-Konto / Telefon ausblenden", vm.onlyGoogleBirthdays) { vm.setOnlyGoogle(it) }
            }

            item { SectionTitle("Samsung Aufgaben") }
            item {
                SwitchRow("Aufgaben im Kalender anzeigen", "Status: ${vm.taskStatus}", vm.showTasks) { vm.setShowTasks(it) }
            }
            item {
                SwitchRow("Erledigte Aufgaben anzeigen", null, vm.showDoneTasks) { vm.setShowDone(it) }
            }

            item { SectionTitle("Kalender") }
            if (vm.calendars.isEmpty()) {
                item { Text("Keine Kalender gefunden.", Modifier.padding(horizontal = 16.dp)) }
            }
            val grouped = vm.calendars.groupBy { Repository.accountLabel(it.accountType, it.account) }
            grouped.forEach { (account, cals) ->
                item(key = "acc-$account") {
                    Text(
                        account, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
                    )
                }
                items(cals, key = { "cal-${it.id}" }) { c ->
                    val visible = c.id !in vm.hidden
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setCalendarVisible(c.id, !visible) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = visible, onCheckedChange = { vm.setCalendarVisible(c.id, it) },
                            colors = CheckboxDefaults.colors(checkedColor = Color(c.color))
                        )
                        Column(Modifier.weight(1f)) {
                            Text(c.name)
                            if (c.isContactsBirthday) Text("Geburtstagskalender von Google – meist doppelt zu den Kontakt-Geburtstagen", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
