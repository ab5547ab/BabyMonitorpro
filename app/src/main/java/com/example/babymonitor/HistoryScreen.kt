package com.example.babymonitor

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(context: Context) {
    var sessions by remember { mutableStateOf<List<SleepSession>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadTrigger) {
        isLoading = true
        sessions = withContext(Dispatchers.IO) { HistoryRepository.getAllSessions(context) }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "היסטוריית שינה",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = { reloadTrigger++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "רענן")
                }
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { HistoryRepository.clearHistory(context) }
                        reloadTrigger++
                    }
                }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "נקה היסטוריה")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when {
            isLoading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            sessions.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.NightsStay,
                        contentDescription = null,
                        modifier = Modifier.height(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "עדיין אין היסטוריה. הפעילו ניטור כדי להתחיל לתעד.")
                }
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(sessions) { session ->
                        SessionCard(session)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: SleepSession) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = formatDate(session.startTime),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            val rangeText = if (session.endTime != null) {
                "${formatTime(session.startTime)} - ${formatTime(session.endTime!!)}  " +
                    "(${formatDuration(session.endTime!! - session.startTime)})"
            } else {
                "${formatTime(session.startTime)} - עדיין פעיל"
            }
            Text(text = rangeText, fontSize = 14.sp)

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (session.cryEvents.isEmpty())
                    "לא זוהה בכי בסשן הזה"
                else
                    "זוהה בכי ${session.cryEvents.size} פעמים",
                fontSize = 14.sp,
                color = if (session.cryEvents.isEmpty())
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )

            if (session.cryEvents.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "הסתר פרטים" else "הצג פרטי בכי")
                }

                if (expanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        session.cryEvents.forEach { event ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = formatTime(event.timestamp), fontSize = 13.sp)
                                Text(text = "משך ${event.durationSec} שנ'", fontSize = 13.sp)
                                Text(text = "שיא ${event.peakDb} dB", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("EEEE, dd/MM/yyyy", Locale("iw")).format(Date(ts))

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours} ש' ${minutes} דק'" else "${minutes} דק'"
}
