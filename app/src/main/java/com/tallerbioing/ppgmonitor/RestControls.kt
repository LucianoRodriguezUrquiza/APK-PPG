package com.tallerbioing.ppgmonitor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tallerbioing.ppgmonitor.data.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ScreenControlCard(ble: BleManager) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ahorro de pantalla", fontWeight = FontWeight.Bold)
            Text(ble.connectionState)
            Text(ble.screenMessage)
            Text("Apaga sólo la luz del reloj. El monitoreo continúa.")
            val enabled = ble.isConnected && !ble.screenCommandPending
            Button(onClick = { ble.requestScreen(false) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                Text("Apagar pantalla")
            }
            Button(onClick = { ble.requestScreen(true) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
                Text("Encender pantalla")
            }
            if (ble.screenOn == null) {
                TextButton(onClick = { ble.requestScreen(null) }, enabled = enabled) {
                    Text("Consultar estado")
                }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
fun SleepRecordCard() {
    val context = LocalContext.current.applicationContext
    val dao = remember(context) { AppDatabase.getDatabase(context).sleepPeriodDao() }
    // null means Room has not delivered the persisted state yet: disable actions.
    val periods by remember(dao) { dao.observeAll() }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val active = periods?.firstOrNull { it.endMs == null }
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss") }
    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(formatter)

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Registro de sueño", fontWeight = FontWeight.Bold)
            Text("Período de descanso declarado manualmente; no detecta el sueño ni sus fases.")
            Text(when {
                periods == null -> "Cargando registro…"
                active != null -> "Sueño declarado activo desde ${date(active.startMs)}"
                else -> "Vigilia"
            })
            Button(
                enabled = periods != null && !saving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Purple),
                onClick = {
                    // Capture the tap time before coroutine/database scheduling.
                    val now = System.currentTimeMillis()
                    val ending = active != null
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            withContext(NonCancellable) {
                                if (ending) dao.end(now) else dao.start(now)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "No se pudo guardar el período"
                        } finally {
                            saving = false
                        }
                    }
                }
            ) { Text(if (active == null) "Me voy a dormir" else "Ya me desperté") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            periods?.firstOrNull { it.endMs != null }?.let { last ->
                val seconds = (last.durationMs() ?: 0) / 1000
                Text("Último período: ${date(last.startMs)} → ${date(last.endMs!!)}")
                Text("Duración: ${seconds / 3600} h ${(seconds % 3600) / 60} min ${seconds % 60} s")
            }
            Text("La anotación se conserva sin BLE. Sólo se exportan las mediciones realmente guardadas.")
        }
    }
    Spacer(Modifier.height(18.dp))
}
