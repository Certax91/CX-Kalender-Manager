package de.certax.kalender

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val DE: Locale = Locale.GERMANY
val DAY_HEADER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", DE)
val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EE, d. MMM", DE)

val SUNDAY_RED = Color(0xFFE53935)
private val SATURDAY_BLUE = Color(0xFF1E88E5)

@Composable
fun weekdayColor(d: DayOfWeek, default: Color): Color = when (d) {
    DayOfWeek.SUNDAY -> SUNDAY_RED
    else -> default
}

/** Wischen nach links/rechts zum Blättern. */
fun Modifier.swipe(key: Any, onSwipe: (Int) -> Unit): Modifier = this.pointerInput(key) {
    var sum = 0f
    detectHorizontalDragGestures(
        onDragStart = { sum = 0f },
        onDragEnd = { if (sum > 120) onSwipe(-1) else if (sum < -120) onSwipe(1) },
        onHorizontalDrag = { _, d -> sum += d }
    )
}

// ================================================================ Jahr

@Composable
fun YearScreen(vm: MainViewModel) {
    val year = vm.selectedDay.year
    val today = LocalDate.now()
    val daysWithEntries = remember(vm.entries) {
        val m = HashMap<LocalDate, Int>()
        vm.entries.forEach { e ->
            var d = e.startDate
            var guard = 0
            while (!d.isAfter(e.endDate) && guard++ < 400) { m.putIfAbsent(d, e.color); d = d.plusDays(1) }
        }
        m
    }
    Column(
        Modifier.fillMaxSize().swipe(year) { vm.step(it) }.verticalScroll(rememberScrollState()).padding(8.dp)
    ) {
        for (row in 0 until 4) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 3) {
                    val ym = YearMonth.of(year, row * 3 + col + 1)
                    MiniMonth(
                        ym, today, daysWithEntries,
                        Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(8.dp))
                            .clickable { vm.drillDown(CalView.MONTH, if (ym == YearMonth.now()) today else ym.atDay(1)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniMonth(ym: YearMonth, today: LocalDate, marks: Map<LocalDate, Int>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val first = ym.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    Column(modifier.padding(4.dp)) {
        Text(
            ym.month.getDisplayName(TextStyle.FULL_STANDALONE, DE),
            fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = if (ym == YearMonth.now()) cs.primary else cs.onSurface,
            modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
        )
        Row {
            DayOfWeek.values().forEach {
                Text(
                    it.getDisplayName(TextStyle.NARROW, DE), Modifier.weight(1f), fontSize = 8.sp,
                    textAlign = TextAlign.Center, color = weekdayColor(it, cs.onSurfaceVariant)
                )
            }
        }
        for (w in 0 until 6) {
            Row {
                for (i in 0 until 7) {
                    val d = start.plusDays((w * 7 + i).toLong())
                    val inMonth = d.month == ym.month
                    Box(Modifier.weight(1f).height(17.dp), contentAlignment = Alignment.Center) {
                        if (inMonth) {
                            val isToday = d == today
                            Box(
                                Modifier.size(15.dp).clip(CircleShape).background(if (isToday) cs.primary else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${d.dayOfMonth}", fontSize = 8.sp, lineHeight = 9.sp,
                                    color = if (isToday) cs.onPrimary else weekdayColor(d.dayOfWeek, cs.onSurface),
                                    fontWeight = if (marks.containsKey(d)) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                            marks[d]?.let { c ->
                                if (!isToday) Box(
                                    Modifier.align(Alignment.BottomCenter).size(3.dp).clip(CircleShape).background(Color(c))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================ Woche / Tag (Zeitraster)

private val HOUR_HEIGHT = 56.dp
private val TIME_COL = 44.dp

@Composable
fun TimelineScreen(
    vm: MainViewModel,
    days: List<LocalDate>,
    onEntry: (Entry) -> Unit,
    onNewAt: (LocalDate, Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val entries = vm.entries
    val perDay = remember(entries, days) { days.associateWith { d -> entries.filter { it.occursOn(d) } } }
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    // Beim Öffnen zur aktuellen Uhrzeit (bzw. 7 Uhr) scrollen
    LaunchedEffect(days.first()) {
        val h = if (today in days) (LocalTime.now().hour - 1).coerceAtLeast(0) else 7
        scroll.scrollTo(with(density) { (HOUR_HEIGHT * h).roundToPx() })
    }

    // Aktuelle Uhrzeit minütlich aktualisieren
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = LocalDateTime.now() } }

    Column(Modifier.fillMaxSize().swipe(days.first()) { vm.step(it) }) {
        // Kopfzeile mit Wochentagen
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(TIME_COL))
            days.forEach { d ->
                val isToday = d == today
                Column(
                    Modifier.weight(1f).clickable(enabled = days.size > 1) { vm.drillDown(CalView.DAY, d) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        d.dayOfWeek.getDisplayName(TextStyle.SHORT, DE).removeSuffix("."), fontSize = 11.sp,
                        color = weekdayColor(d.dayOfWeek, cs.onSurfaceVariant)
                    )
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(if (isToday) cs.primary else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${d.dayOfMonth}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (isToday) cs.onPrimary else weekdayColor(d.dayOfWeek, cs.onSurface)
                        )
                    }
                }
            }
        }
        // Ganztägige Einträge
        val allDayMax = days.maxOfOrNull { d -> perDay[d].orEmpty().count { it.allDay } } ?: 0
        if (allDayMax > 0) {
            val limit = if (days.size == 1) 6 else 3
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Text("ganzt.", Modifier.width(TIME_COL).padding(top = 2.dp), fontSize = 9.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                days.forEach { d ->
                    val list = perDay[d].orEmpty().filter { it.allDay }.sortedForDay()
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        list.take(limit).forEach { e ->
                            Text(
                                e.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = if (days.size == 1) 13.sp else 9.sp, color = Color.White,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 1.dp).clip(RoundedCornerShape(3.dp))
                                    .background(Color(e.color)).clickable { onEntry(e) }
                                    .padding(horizontal = 3.dp, vertical = if (days.size == 1) 3.dp else 0.dp)
                            )
                        }
                        if (list.size > limit) Text("+${list.size - limit}", fontSize = 9.sp, color = cs.onSurfaceVariant,
                            modifier = Modifier.clickable { vm.drillDown(CalView.DAY, d) })
                    }
                }
            }
        }
        HorizontalDivider()
        // Zeitraster
        Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Column(Modifier.width(TIME_COL)) {
                for (h in 0 until 24) {
                    Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth()) {
                        if (h > 0) Text(
                            "%02d:00".format(h), fontSize = 10.sp, color = cs.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-7).dp)
                        )
                    }
                }
            }
            days.forEach { d ->
                DayColumn(
                    day = d,
                    entries = perDay[d].orEmpty().filter { !it.allDay },
                    now = if (d == now.toLocalDate()) now else null,
                    modifier = Modifier.weight(1f),
                    onEntry = onEntry,
                    onEmptyTap = { hour -> onNewAt(d, hour) },
                    wide = days.size == 1,
                )
            }
        }
    }
}

private class Placed(val e: Entry, val startMin: Int, val endMin: Int, var lane: Int = 0, var lanes: Int = 1)

/** Überlappende Termine nebeneinander anordnen. */
private fun layoutDay(day: LocalDate, list: List<Entry>): List<Placed> {
    val items = list.mapNotNull { e ->
        val s = e.startTime ?: return@mapNotNull null
        val en = e.endTime ?: s
        val sMin = if (s.toLocalDate().isBefore(day)) 0 else s.hour * 60 + s.minute
        val eMin = if (en.toLocalDate().isAfter(day)) 24 * 60 else en.hour * 60 + en.minute
        Placed(e, sMin, maxOf(eMin, sMin + 20))
    }.sortedWith(compareBy({ it.startMin }, { -it.endMin }))
    var cluster = mutableListOf<Placed>()
    var clusterEnd = -1
    val laneEnds = mutableListOf<Int>()
    fun close() {
        val n = laneEnds.size.coerceAtLeast(1)
        cluster.forEach { it.lanes = n }
        cluster = mutableListOf(); laneEnds.clear()
    }
    for (p in items) {
        if (p.startMin >= clusterEnd && cluster.isNotEmpty()) close()
        var lane = laneEnds.indexOfFirst { it <= p.startMin }
        if (lane < 0) { lane = laneEnds.size; laneEnds += p.endMin } else laneEnds[lane] = p.endMin
        p.lane = lane
        cluster += p
        clusterEnd = maxOf(clusterEnd, p.endMin)
    }
    close()
    return items
}

@Composable
private fun DayColumn(
    day: LocalDate,
    entries: List<Entry>,
    now: LocalDateTime?,
    modifier: Modifier,
    onEntry: (Entry) -> Unit,
    onEmptyTap: (Int) -> Unit,
    wide: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val placed = remember(entries, day) { layoutDay(day, entries) }
    val lineColor = cs.outlineVariant.copy(alpha = 0.5f)
    BoxWithConstraints(
        modifier
            .height(HOUR_HEIGHT * 24)
            .background(if (day.dayOfWeek == DayOfWeek.SUNDAY || day.dayOfWeek == DayOfWeek.SATURDAY) cs.surfaceVariant.copy(alpha = 0.25f) else Color.Transparent)
            .pointerInput(day) {
                detectTapGestures { off ->
                    val hour = (off.y / with(density) { HOUR_HEIGHT.toPx() }).toInt().coerceIn(0, 23)
                    onEmptyTap(hour)
                }
            }
    ) {
        val colW: Dp = maxWidth
        // Stundenlinien
        for (h in 0 until 24) {
            Box(Modifier.offset(y = HOUR_HEIGHT * h).fillMaxWidth().height(1.dp).background(lineColor))
        }
        Box(Modifier.fillMaxHeight().width(1.dp).background(lineColor))
        // Termine
        placed.forEach { p ->
            val top = HOUR_HEIGHT * (p.startMin / 60f)
            val height = HOUR_HEIGHT * ((p.endMin - p.startMin) / 60f)
            val w = colW / p.lanes
            Column(
                Modifier
                    .offset(x = w * p.lane, y = top)
                    .width(w).height(height)
                    .padding(1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(p.e.color))
                    .clickable { onEntry(p.e) }
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    p.e.title, color = Color.White, fontSize = if (wide) 13.sp else 10.sp,
                    lineHeight = if (wide) 15.sp else 11.sp, fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Ellipsis
                )
                if (wide) {
                    val t = p.e.startTime!!.format(Repository.TIME) + " – " + p.e.endTime!!.format(Repository.TIME) +
                        (p.e.location?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    Text(t, color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Linie für die aktuelle Uhrzeit
        if (now != null) {
            val y = HOUR_HEIGHT * ((now.hour * 60 + now.minute) / 60f)
            Box(Modifier.offset(y = y).fillMaxWidth().height(2.dp).background(SUNDAY_RED))
            Box(Modifier.offset(x = (-4).dp, y = y - 3.dp).size(8.dp).clip(CircleShape).background(SUNDAY_RED))
        }
    }
}

// ================================================================ Liste

@Composable
fun AgendaScreen(vm: MainViewModel, onEntry: (Entry) -> Unit) {
    val today = LocalDate.now()
    val groups = remember(vm.entries) {
        (0..vm.agendaDays).mapNotNull { i ->
            val d = today.plusDays(i)
            val l = vm.entries.filter { it.occursOn(d) }.sortedForDay()
            if (l.isEmpty()) null else d to l
        }
    }
    if (groups.isEmpty()) {
        Text("Keine Termine in den nächsten ${vm.agendaDays} Tagen", Modifier.padding(16.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        groups.forEach { (d, l) ->
            item(key = "h$d") {
                val label = when (d) {
                    today -> "Heute · " + d.format(SHORT_DATE)
                    today.plusDays(1) -> "Morgen · " + d.format(SHORT_DATE)
                    else -> d.format(SHORT_DATE)
                }
                Text(
                    label, fontWeight = FontWeight.SemiBold,
                    color = weekdayColor(d.dayOfWeek, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .clickable { vm.drillDown(CalView.DAY, d) }
                        .padding(start = 16.dp, top = 14.dp, bottom = 2.dp)
                )
            }
            items(l, key = { "$d-${it.key}" }) { e -> EntryRow(e, d) { onEntry(e) } }
        }
    }
}

@Composable
fun EntryRow(e: Entry, day: LocalDate, onClick: () -> Unit) {
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

// ================================================================ Aufgaben

@Composable
fun TasksScreen(vm: MainViewModel) {
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
