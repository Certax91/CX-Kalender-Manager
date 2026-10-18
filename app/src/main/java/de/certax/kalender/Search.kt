package de.certax.kalender

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: MainViewModel, onBack: () -> Unit, onEntry: (Entry) -> Unit) {
    var query by rememberSaveableQuery()
    var results by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        delay(300) // erst suchen, wenn kurz nicht getippt wird
        searching = true
        results = vm.search(query)
        searching = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                title = {
                    TextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Suchen") }, singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent
                        ),
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Löschen") }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (query.trim().length >= 2 && results.isEmpty() && !searching) {
                Text("Keine Treffer", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                var lastDate: java.time.LocalDate? = null
                results.forEach { e ->
                    if (e.startDate != lastDate) {
                        val d = e.startDate
                        item(key = "h$d") {
                            Text(
                                d.format(DAY_HEADER), fontWeight = FontWeight.SemiBold,
                                color = weekdayColor(d.dayOfWeek, MaterialTheme.colorScheme.primary),
                                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp)
                            )
                        }
                        lastDate = d
                    }
                    item(key = "r${e.key}") { EntryRow(e, e.startDate) { onEntry(e) } }
                }
            }
        }
    }
}

@Composable
private fun rememberSaveableQuery(): MutableState<String> =
    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
