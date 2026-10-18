package de.certax.kalender

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import kotlin.math.abs

/** Wischen in alle Richtungen (Monatsansicht: hoch/runter wie Samsung, links/rechts zusätzlich). */
private fun Modifier.swipeAny(key: Any, onSwipe: (Int) -> Unit): Modifier = this.pointerInput(key) {
    var dx = 0f
    var dy = 0f
    detectDragGestures(
        onDragStart = { dx = 0f; dy = 0f },
        onDragEnd = {
            if (abs(dy) > abs(dx)) {
                if (dy < -120) onSwipe(1) else if (dy > 120) onSwipe(-1)
            } else {
                if (dx < -120) onSwipe(1) else if (dx > 120) onSwipe(-1)
            }
        },
        onDrag = { change, amount -> change.consume(); dx += amount.x; dy += amount.y }
    )
}

private class Bar(val e: Entry, val from: Int, val to: Int, var lane: Int = 0)

private val WEEK_COL = 22.dp
private val NUMBER_AREA = 24.dp
private val BAR_HEIGHT = 18.dp

@Composable
fun MonthScreen(vm: MainViewModel) {
    val month = vm.month
    val first = month.atDay(1)
    val gridStart = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val last = month.atEndOfMonth()
    val weeks = ((last.toEpochDay() - gridStart.toEpochDay()) / 7 + 1).toInt()
    val today = LocalDate.now()
    val holidayIds = vm.holidayCalendarIds
    val entries = vm.entries
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        // Wochentage
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
            Spacer(Modifier.width(WEEK_COL))
            DayOfWeek.values().forEach { dow ->
                Text(
                    dow.getDisplayName(TextStyle.SHORT, DE).uppercase().removeSuffix(".") + ".",
                    Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = weekdayColor(dow, cs.onSurfaceVariant), textAlign = TextAlign.Center
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(end = 4.dp, bottom = 76.dp)
                .swipeAny(month) { vm.step(it) }
        ) {
            for (w in 0 until weeks) {
                val ws = gridStart.plusDays(w * 7L)
                WeekRow(
                    vm = vm, weekStart = ws, month = month.monthValue, today = today,
                    entries = entries, holidayIds = holidayIds,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun WeekRow(
    vm: MainViewModel,
    weekStart: LocalDate,
    month: Int,
    today: LocalDate,
    entries: List<Entry>,
    holidayIds: Set<Long>,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val cardBase = if (dark) Color(0xFF16161A) else Color(0xFFF1F1F4)
    val weekEnd = weekStart.plusDays(6)
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }

    // Mehrtägige Einträge als durchgehende Balken, einzelne Tage als Kästchen
    val (bars, singles) = remember(entries, weekStart) {
        val inWeek = entries.filter { !it.endDate.isBefore(weekStart) && !it.startDate.isAfter(weekEnd) }
        val multi = inWeek.filter { it.endDate.isAfter(it.startDate) }
            .sortedWith(compareBy<Entry>({ it.startDate }, { -(it.endDate.toEpochDay() - it.startDate.toEpochDay()) }, { it.title }))
        val laneEnds = mutableListOf<Int>()
        val b = multi.map { e ->
            val f = maxOf(0, (e.startDate.toEpochDay() - weekStart.toEpochDay()).toInt())
            val t = minOf(6, (e.endDate.toEpochDay() - weekStart.toEpochDay()).toInt())
            val bar = Bar(e, f, t)
            var lane = laneEnds.indexOfFirst { it < f }
            if (lane < 0) { lane = laneEnds.size; laneEnds += t } else laneEnds[lane] = t
            bar.lane = lane
            bar
        }
        val s = days.map { d -> inWeek.filter { !it.endDate.isAfter(it.startDate) && it.startDate == d }.sortedForDay() }
        b to s
    }
    val lanes = (bars.maxOfOrNull { it.lane } ?: -1) + 1

    Row(modifier) {
        // Kalenderwoche
        Text(
            "${weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}",
            Modifier.width(WEEK_COL).padding(top = 6.dp), fontSize = 12.sp,
            color = cs.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // Ebene 1: Tageskacheln mit Datum
            Row(Modifier.fillMaxSize()) {
                days.forEachIndexed { i, d ->
                    val inMonth = d.monthValue == month
                    val isToday = d == today
                    val selected = d == vm.selectedDay
                    val holiday = (bars.any { (it.e.calendarId?.let { c -> c in holidayIds } == true) && i in it.from..it.to }) ||
                        singles[i].any { it.calendarId?.let { c -> c in holidayIds } == true }
                    val tint = (bars.filter { i in it.from..it.to }.minByOrNull { it.lane }?.e ?: singles[i].firstOrNull())?.color
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(1.5.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(cardBase)
                            .background(if (tint != null) Color(tint).copy(alpha = if (dark) 0.22f else 0.18f) else Color.Transparent)
                            .then(if (selected) Modifier.border(1.5.dp, cs.onSurface.copy(alpha = 0.7f), RoundedCornerShape(6.dp)) else Modifier)
                            .clickable {
                                // 1. Tipp wählt den Tag, 2. Tipp öffnet die Tagesansicht (wie Samsung)
                                if (selected) vm.drillDown(CalView.DAY, d) else vm.select(d)
                            }
                    ) {
                        Box(
                            Modifier.align(Alignment.TopCenter).padding(top = 3.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (isToday) SUNDAY_RED else Color.Transparent)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val base = if (holiday || d.dayOfWeek == DayOfWeek.SUNDAY) SUNDAY_RED else cs.onSurface
                            Text(
                                "${d.dayOfMonth}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = when {
                                    isToday -> Color.White
                                    !inMonth -> base.copy(alpha = 0.4f)
                                    else -> base
                                }
                            )
                        }
                    }
                }
            }
            // Ebene 2: Einträge (nicht anklickbar, Tipp geht an die Kachel)
            Column(Modifier.fillMaxSize().padding(top = NUMBER_AREA, bottom = 2.dp).clipToBounds()) {
                for (lane in 0 until lanes) {
                    Row(Modifier.fillMaxWidth().height(BAR_HEIGHT + 2.dp).padding(vertical = 1.dp)) {
                        var col = 0
                        while (col < 7) {
                            val bar = bars.firstOrNull { it.lane == lane && it.from == col }
                            if (bar != null) {
                                val span = bar.to - bar.from + 1
                                val dim = !days[bar.from].let { it.monthValue == month } && !days[bar.to].let { it.monthValue == month }
                                Box(
                                    Modifier.weight(span.toFloat()).fillMaxHeight().padding(horizontal = 3.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(bar.e.color).copy(alpha = if (dim) 0.45f else 1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        bar.e.title, fontSize = 11.sp, lineHeight = 12.sp, color = Color.White,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                col += span
                            } else {
                                Spacer(Modifier.weight(1f))
                                col++
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    days.forEachIndexed { i, d ->
                        val inMonth = d.monthValue == month
                        Column(Modifier.weight(1f).padding(horizontal = 3.dp)) {
                            singles[i].forEach { e -> MonthChip(e, inMonth) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthChip(e: Entry, inMonth: Boolean) {
    val alpha = if (inMonth) 1f else 0.45f
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(e.color).copy(alpha = alpha))
            .padding(horizontal = 2.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (e.source == SourceType.TASK) {
            Icon(
                Icons.Filled.Notifications, null, tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(11.dp).padding(top = 2.dp)
            )
        }
        Text(
            e.title, fontSize = 11.sp, lineHeight = 13.sp,
            color = Color.White.copy(alpha = if (e.completed) 0.6f else 1f),
            maxLines = 2, overflow = TextOverflow.Clip,
            textDecoration = if (e.completed) TextDecoration.LineThrough else null
        )
    }
}
