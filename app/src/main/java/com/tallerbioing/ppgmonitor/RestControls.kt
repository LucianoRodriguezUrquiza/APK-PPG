package com.tallerbioing.ppgmonitor

import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val ScreenPinkSelected = Color(0xFFB93670)
private val ScreenPinkUnselected = Color(0xFFF7BED5)
private val ScreenPinkLabel = Color(0xFF542B40)

// Los botones reflejan el estado confirmado por el ACK BLE, no sólo el toque.
@Composable
private fun ScreenModeButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = if (selected) ScreenPinkSelected else ScreenPinkUnselected,
        label = "Color de selección del control de pantalla"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) Color.White else ScreenPinkLabel,
        label = "Texto del control de pantalla"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (selected) 4.dp else 0.dp,
            pressedElevation = 1.dp
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            contentColor = labelColor,
            disabledContainerColor = buttonColor.copy(
                alpha = if (selected) 0.72f else 0.60f
            ),
            disabledContentColor = labelColor.copy(alpha = 0.85f)
        )
    ) {
        Text(
            text = if (selected) "✓  $text" else text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun ScreenControlCard(ble: BleManager) {
    val enabled = ble.isConnected && !ble.screenCommandPending

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = PinkSoft,
            contentColor = TextPrimary
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Ahorro de batería",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = TextPrimary
            )
            ScreenModeButton(
                text = "Apagar pantalla",
                selected = ble.screenOn == false,
                enabled = enabled,
                onClick = { ble.requestScreen(false) }
            )
            ScreenModeButton(
                text = "Encender pantalla",
                selected = ble.screenOn == true,
                enabled = enabled,
                onClick = { ble.requestScreen(true) }
            )
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

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = PinkSoft,
            contentColor = Color.Black
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Registro del sueño: " + when {
                    periods == null -> "Cargando…"
                    active != null -> "Sueño"
                    else -> "Vigilia"
                },
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            Button(
                enabled = periods != null && !saving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ScreenPinkSelected,
                    contentColor = Color.White,
                    disabledContainerColor = ScreenPinkSelected.copy(alpha = 0.55f),
                    disabledContentColor = Color.White
                ),
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
            error?.let { Text(it, color = Color.Black) }
            periods?.firstOrNull { it.endMs != null }?.let { last ->
                val seconds = (last.durationMs() ?: 0) / 1000
                Text("Último período:", fontWeight = FontWeight.SemiBold)
                Text(date(last.startMs))
                Text(
                    "↓",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = ScreenPinkSelected,
                    fontSize = 22.sp
                )
                Text(date(last.endMs!!))
                Text("Duración: ${seconds / 3600} h ${(seconds % 3600) / 60} min ${seconds % 60} s")
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}
