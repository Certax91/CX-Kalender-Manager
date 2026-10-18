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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields

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

private val PERMISSIONS = arrayOf(
    Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CONTACTS
)

private fun title(vm: MainViewModel): String {
    val d = vm.selectedDay
    return when (vm.view) {
        CalView.YEAR -> "${d.year}"
        CalView.MONTH -> d.month.getDisplayName(TextStyle.FULL_STANDALONE, DE) + " " + d.year
        CalView.WEEK -> {
            val ws = weekStart(d)
            ws.format(DateTimeFormatter.ofPattern("MMM yyyy", DE)) + " · KW " + ws.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        }
        CalView.DAY -> d.format(DateTimeFormatter.ofPattern("EE, d. MMMM", DE))
        CalView.LIST -> "Termine"
        CalView.TASKS -> "Aufgaben"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Entry?>(null) }
    var editing by remember { mutableStateOf<EventDraft?>(null) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    fun updatePermissions() {
        vm.hasCalendarPermission = ctx.granted(Manifest.permission.READ_CALENDAR)
        vm.hasWritePermission = ctx.granted(Manifest.permission.WRITE_CALENDAR)
        vm.hasContactsPermission = ctx.granted(Manifest.permission.READ_CONTACTS)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updatePermissions()
        vm.reloadAll()
    }

    // Bei jedem Zurückkehren in die App neu laden
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                updatePermissions()
                val missing = !vm.hasCalendarPermission || !vm.hasContactsPermission || !vm.hasWritePermission
                if (missing && !askedOnce) {
                    askedOnce = true
                    launcher.launch(PERMISSIONS)
                } else vm.reloadAll()
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun newEvent(day: LocalDate = vm.selectedDay, hour: Int? = null) {
        if (!vm.hasWritePermission) {
            Toast.makeText(ctx, "Bitte erlaube der App, Termine zu speichern", Toast.LENGTH_LONG).show()
            launcher.launch(PERMISSIONS); return
        }
        editing = vm.newDraft(day, hour)
    }

    // ------------------------------------------------ Editor (Vollbild)
    editing?.let { draft ->
        EventEditor(vm, draft, onClose = { editing = null })
        return
    }

    // ------------------------------------------------ Einstellungen
    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsScreen(vm, onBack = { settingsOpen = false }, onRequestPermissions = { launcher.launch(PERMISSIONS) })
        return
    }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen) {
        if (!vm.back()) (ctx as? ComponentActivity)?.finish()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(290.dp)) {
                Text(
                    "CX Kalender", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                    modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 16.dp)
                )
                listOf(
                    CalView.YEAR to Icons.Filled.DateRange,
                    CalView.MONTH to Icons.Filled.DateRange,
                    CalView.WEEK to Icons.Filled.DateRange,
                    CalView.DAY to Icons.Filled.DateRange,
                    CalView.LIST to Icons.AutoMirrored.Filled.List,
                    CalView.TASKS to Icons.Filled.CheckCircle,
                ).forEach { (v, icon) ->
                    NavigationDrawerItem(
                        label = { Text(v.label) },
                        icon = { Icon(icon, null) },
                        selected = vm.view == v,
                        onClick = { vm.switchView(v); scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 24.dp))
                NavigationDrawerItem(
                    label = { Text("Kalender verwalten") },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    selected = false,
                    onClick = { settingsOpen = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menü") }
                    },
                    title = {
                        Text(
                            title(vm), fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.clickable(enabled = vm.view in setOf(CalView.MONTH, CalView.WEEK, CalView.DAY)) {
                                // Tipp auf den Titel: eine Ebene höher (wie Samsung)
                                when (vm.view) {
                                    CalView.DAY -> vm.switchView(CalView.MONTH)
                                    CalView.WEEK -> vm.switchView(CalView.MONTH)
                                    CalView.MONTH -> vm.switchView(CalView.YEAR)
                                    else -> {}
                                }
                            }
                        )
                    },
                    actions = {
                        if (vm.view == CalView.TASKS || vm.view == CalView.LIST) {
                            IconButton(onClick = { vm.reloadAll() }) { Icon(Icons.Filled.Refresh, "Neu laden") }
                        } else {
                            // „Heute“-Knopf mit dem heutigen Datum, wie im Samsung Kalender
                            IconButton(onClick = { vm.goToday() }) {
                                Box(
                                    Modifier.size(26.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)),
                                    contentAlignment = Alignment.Center
                                ) { Text("${LocalDate.now().dayOfMonth}", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                        IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Filled.Settings, "Einstellungen") }
                    }
                )
            },
            floatingActionButton = {
                if (vm.view != CalView.TASKS && vm.hasCalendarPermission) {
                    FloatingActionButton(onClick = { newEvent() }) { Icon(Icons.Filled.Add, "Neuer Termin") }
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
                } else when (vm.view) {
                    CalView.YEAR -> YearScreen(vm)
                    CalView.MONTH -> MonthScreen(vm) { detail = it }
                    CalView.WEEK -> {
                        val ws = weekStart(vm.selectedDay)
                        TimelineScreen(vm, (0..6).map { ws.plusDays(it.toLong()) }, { detail = it }) { d, h -> newEvent(d, h) }
                    }
                    CalView.DAY -> TimelineScreen(vm, listOf(vm.selectedDay), { detail = it }) { d, h -> newEvent(d, h) }
                    CalView.LIST -> AgendaScreen(vm) { detail = it }
                    CalView.TASKS -> TasksScreen(vm)
                }
            }
        }
    }

    detail?.let { e ->
        DetailDialog(
            vm, e,
            onDismiss = { detail = null },
            onEdit = { id ->
                detail = null
                scope.launch {
                    val dr = vm.loadDraft(id)
                    if (dr != null) editing = dr
                    else Toast.makeText(ctx, "Termin konnte nicht geladen werden", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
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

// ---------------------------------------------------------------- Details

@Composable
private fun DetailDialog(vm: MainViewModel, e: Entry, onDismiss: () -> Unit, onEdit: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var askDelete by remember { mutableStateOf(false) }
    val writable = e.eventId != null && vm.hasWritePermission &&
        vm.calendars.firstOrNull { it.id == e.calendarId }?.writable == true

    val dateText = if (e.allDay) {
        if (e.startDate == e.endDate) e.startDate.format(DAY_HEADER)
        else e.startDate.format(SHORT_DATE) + " – " + e.endDate.format(SHORT_DATE)
    } else {
        val s = e.startTime!!; val en = e.endTime!!
        if (s.toLocalDate() == en.toLocalDate())
            s.format(DAY_HEADER) + "\n" + s.format(Repository.TIME) + " – " + en.format(Repository.TIME) + " Uhr"
        else s.format(SHORT_DATE) + " " + s.format(Repository.TIME) + " – " + en.format(SHORT_DATE) + " " + en.format(Repository.TIME)
    }

    if (askDelete) {
        fun doDelete(onlyThis: Boolean) {
            askDelete = false
            scope.launch {
                vm.deleteEvent(e, onlyThis)
                    .onSuccess { Toast.makeText(ctx, "Gelöscht", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(ctx, "Fehler: ${it.message}", Toast.LENGTH_LONG).show() }
                onDismiss()
            }
        }
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Termin löschen?") },
            text = {
                Column {
                    Text(e.title)
                    if (e.recurring) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { doDelete(true) }) { Text("Nur diesen Termin") }
                        TextButton(onClick = { doDelete(false) }) { Text("Alle Termine der Serie") }
                    }
                }
            },
            confirmButton = {
                if (!e.recurring) TextButton(onClick = { doDelete(false) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Abbrechen") } }
        )
        return
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
                if (e.recurring) Text("🔁 Serientermin", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                e.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp)); Text(it.take(600), fontSize = 13.sp)
                }
                if (e.contactId != null) {
                    TextButton(onClick = {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, e.contactId)))
                        } catch (_: Exception) { Toast.makeText(ctx, "Konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show() }
                        onDismiss()
                    }) { Text("Kontakt öffnen") }
                }
                if (e.eventId != null && !writable) {
                    TextButton(onClick = {
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
                                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.beginMillis)
                                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.endMillis)
                            )
                        } catch (_: Exception) { Toast.makeText(ctx, "Konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show() }
                        onDismiss()
                    }) { Text("Im Kalender öffnen") }
                }
            }
        },
        confirmButton = {
            if (writable) Row {
                TextButton(onClick = { askDelete = true }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onEdit(e.eventId!!) }) { Text("Bearbeiten") }
            } else TextButton(onClick = onDismiss) { Text("Schließen") }
        },
        dismissButton = { if (writable) TextButton(onClick = onDismiss) { Text("Schließen") } }
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
                title = { Text("Kalender verwalten") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (!vm.hasCalendarPermission || !vm.hasContactsPermission || !vm.hasWritePermission) {
                item {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            if (!vm.hasCalendarPermission) Text("• Kalender-Zugriff fehlt (für Google-/Samsung-Kalender und Samsung Aufgaben)")
                            if (vm.hasCalendarPermission && !vm.hasWritePermission) Text("• Schreibzugriff fehlt (zum Anlegen neuer Termine)")
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
                SwitchRow("Kontakt-Geburtstage anzeigen", "${vm.birthdayCount} Geburtstage gefunden", vm.showBirthdays) { vm.changeShowBirthdays(it) }
            }
            item {
                SwitchRow("Nur Google-Kontakte", "Kontakte aus Samsung-Konto / Telefon ausblenden", vm.onlyGoogleBirthdays) { vm.changeOnlyGoogle(it) }
            }

            item { SectionTitle("Samsung Aufgaben") }
            item {
                SwitchRow("Aufgaben im Kalender anzeigen", "Status: ${vm.taskStatus}", vm.showTasks) { vm.changeShowTasks(it) }
            }
            item {
                SwitchRow("Erledigte Aufgaben anzeigen", null, vm.showDoneTasks) { vm.changeShowDone(it) }
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
