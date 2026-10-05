package com.tallerbioing.ppgmonitor

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import java.time.LocalDate


// ============================================================================
// PANTALLA 2 — RESUMEN
// ============================================================================

@Composable
fun SummaryScreen(
    bleManager: BleManager
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(
                horizontal = 16.dp,
                vertical = 14.dp
            )
    ) {

        WeekDashboard()

        Spacer(modifier = Modifier.height(14.dp))

        HeartRateCard(
            bpm = bleManager.bpm
        )

        Spacer(modifier = Modifier.height(10.dp))

        B18StatusCard(
            state = bleManager.bpmStateText,
            ageMs = bleManager.bpmAgeMs,
            quality = bleManager.signalQualityText,
            connected = bleManager.isConnected
        )

        Spacer(modifier = Modifier.height(12.dp))

        ActivityCard(
            activityCode = bleManager.activityCode
        )

        Spacer(modifier = Modifier.height(12.dp))

        BatteryCard(
            battery = bleManager.batteryPercentage,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        ExploratoryVitalsCard(
            spo2 = if (bleManager.spo2Valid) bleManager.spo2 else null,
            prv = bleManager.prv,
            connected = bleManager.isConnected
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}


@Composable
private fun B18StatusCard(
    state: String,
    ageMs: Long?,
    quality: String,
    connected: Boolean
) {

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = White
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {

        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Text(
                text = "ESTADO DE LA MEDICIÓN",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(7.dp))

            Text(
                text = if (connected) state else "Sin conexión BLE",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Calidad: $quality",
                fontSize = 13.sp,
                color = TextSecondary
            )

            Text(
                text = "Edad BPM: " +
                    (ageMs?.let { "${it} ms" } ?: "--"),
                fontSize = 13.sp,
                color = TextSecondary
            )
        }
    }
}


@Composable
private fun ExploratoryVitalsCard(
    spo2: Float?,
    prv: B18Prv?,
    connected: Boolean
) {

    val validPrv = prv?.takeIf { it.valid }

    val prvStatus =
        when {
            !connected ->
                "PRV: Sin conexión"

            validPrv != null ->
                "PRV: Disponible"

            prv == null ->
                "PRV: Esperando datos..."

            else ->
                "PRV: Sensando..."
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = PurpleSoft
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {

        Column(
            modifier = Modifier.padding(18.dp)
        ) {

            Text(
                text = "VARIABLES EXPLORATORIAS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "SpO₂: " +
                    (spo2?.let { String.format("%.1f %%", it) } ?: "--"),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "PRV (PPG, no ECG)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Text(
                text = prvStatus,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (validPrv != null) Green else TextSecondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (validPrv == null) {
                    if (prv == null) {
                        "La ESP32 todavía no entregó una ventana PRV evaluable."
                    } else {
                        "Ventana diagnóstica: " +
                            "${prv.spanMs / 1000} s · " +
                            "NN ${prv.nn}/${prv.total} · " +
                            "limpia ${prv.cleanPercent} %"
                    }
                } else {
                    "PP medio ${String.format("%.1f", validPrv.ppMeanMs!!)} ms · " +
                        "RMSSD ${String.format("%.1f", validPrv.rmssdMs!!)} ms\n" +
                        "SDNN ${String.format("%.1f", validPrv.sdnnMs!!)} ms · " +
                        "pNN50 ${String.format("%.1f", validPrv.pnn50Percent!!)} %"
                },
                fontSize = 13.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Presión arterial: pendiente de integración BLE",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )

            Text(
                text = "B18 no transmite todavía valores sistólico/diastólico en mmHg.",
                fontSize = 12.sp,
                color = TextSecondary
            )

            if (validPrv?.flag == true) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Patrón irregular exploratorio detectado",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Pink
                )
            }
        }
    }
}


// ============================================================================
// DASHBOARD DE LA SEMANA
// ============================================================================

@Composable
fun WeekDashboard() {

    val today =
        remember {
            LocalDate.now()
        }


    val monday =
        remember(today) {

            today.minusDays(
                (
                        today.dayOfWeek.value - 1
                        ).toLong()
            )
        }


    val days =
        remember(monday) {

            (0..6).map { index ->

                monday.plusDays(
                    index.toLong()
                )
            }
        }


    val labels =
        listOf(
            "Lun",
            "Mar",
            "Mié",
            "Jue",
            "Vie",
            "Sáb",
            "Dom"
        )


    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(22.dp),

        colors =
            CardDefaults.cardColors(
                containerColor = White
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation = 2.dp
            )
    ) {

        Column(
            modifier =
                Modifier.padding(15.dp)
        ) {

            Text(
                text = "Esta semana",

                fontSize = 16.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    TextPrimary
            )


            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )


            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {

                days.forEachIndexed {
                        index,
                        day ->

                    DayChip(
                        dayName =
                            labels[index],

                        dayNumber =
                            day.dayOfMonth,

                        selected =
                            day == today
                    )
                }
            }
        }
    }
}


// ============================================================================
// DÍA DE LA SEMANA
// ============================================================================

@Composable
fun DayChip(
    dayName: String,
    dayNumber: Int,
    selected: Boolean
) {

    Column(
        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Text(
            text = dayName,

            fontSize = 10.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(4.dp)
        )


        Box(
            modifier = Modifier
                .size(35.dp)
                .clip(CircleShape)
                .background(

                    if (selected) {

                        Purple

                    } else {

                        PurpleSoft
                    }
                ),

            contentAlignment =
                Alignment.Center
        ) {

            Text(
                text =
                    dayNumber.toString(),

                fontSize = 12.sp,

                fontWeight =
                    FontWeight.Bold,

                color =

                    if (selected) {

                        White

                    } else {

                        PurpleDark
                    }
            )
        }
    }
}


// ============================================================================
// TARJETA FRECUENCIA CARDÍACA
// ============================================================================

@Composable
fun HeartRateCard(
    bpm: Int
) {

    // ------------------------------------------------------------------------
    // Animación del corazón
    // ------------------------------------------------------------------------

    val infiniteTransition =
        rememberInfiniteTransition(
            label = "heartBeat"
        )


    val pulse by
    infiniteTransition.animateFloat(

        initialValue = 0.90f,

        targetValue = 1.14f,

        animationSpec =
            infiniteRepeatable(

                animation =
                    tween(
                        durationMillis = 650
                    ),

                repeatMode =
                    RepeatMode.Reverse
            ),

        label =
            "heartScale"
    )


    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(24.dp),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    PinkSoft
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation = 2.dp
            )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    text =
                        "FRECUENCIA CARDÍACA",

                    fontSize = 13.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        TextSecondary
                )


                Spacer(
                    modifier =
                        Modifier.height(7.dp)
                )


                Row(
                    verticalAlignment =
                        Alignment.Bottom
                ) {

                    Text(
                        text =

                            if (bpm > 0) {

                                bpm.toString()

                            } else {

                                "--"
                            },

                        fontSize = 48.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            TextPrimary
                    )


                    Spacer(
                        modifier =
                            Modifier.width(6.dp)
                    )


                    Text(
                        text = "BPM",

                        modifier =
                            Modifier.padding(
                                bottom = 8.dp
                            ),

                        fontSize = 15.sp,

                        color =
                            TextSecondary
                    )
                }
            }


            // ----------------------------------------------------------------
            // Corazón animado
            // ----------------------------------------------------------------

            Text(
                text = "♥",

                modifier =
                    Modifier.graphicsLayer {

                        scaleX = pulse

                        scaleY = pulse
                    },

                fontSize = 64.sp,

                color = Pink
            )
        }
    }
}


// ============================================================================
// TARJETA ACTIVIDAD
// ============================================================================

@Composable
fun ActivityCard(
    activityCode: Int
) {

    // ------------------------------------------------------------------------
    // Texto recibido desde MPU6050
    // ------------------------------------------------------------------------

    val activityText =

        when (activityCode) {

            0 ->
                "Reposo"

            1 ->
                "Movimiento leve"

            2 ->
                "Movimiento moderado"

            else ->
                "Sin datos"
        }


    // ------------------------------------------------------------------------
    // Representación visual
    // ------------------------------------------------------------------------

    val activityIcon =

        when (activityCode) {

            0 ->
                "●"

            1 ->
                "🚶"

            2 ->
                "🏃"

            else ->
                "○"
        }


    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(24.dp),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    BlueSoft
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation = 2.dp
            )
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(
                modifier =
                    Modifier.weight(1f)
            ) {

                Text(
                    text =
                        "MONITOR DE ACTIVIDAD",

                    fontSize = 13.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        TextSecondary
                )


                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )


                Text(
                    text =
                        activityText,

                    fontSize = 27.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        TextPrimary
                )
            }


            Text(
                text =
                    activityIcon,

                fontSize = 52.sp
            )
        }
    }
}


// ============================================================================
// TARJETA BATERÍA
// ============================================================================

@Composable
fun BatteryCard(
    battery: Int,
    modifier: Modifier = Modifier
) {

    Card(
        modifier =
            modifier,

        shape =
            RoundedCornerShape(24.dp),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    YellowSoft
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation = 2.dp
            )
    ) {

        Column(
            modifier =
                Modifier.padding(17.dp)
        ) {

            Text(
                text =
                    "BATERÍA",

                fontSize = 12.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    TextSecondary
            )


            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )


            BatteryIcon(
                percentage =
                    battery
            )


            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )


            Text(
                text =

                    if (battery >= 0) {

                        "$battery %"

                    } else {

                        "-- %"
                    },

                fontSize = 28.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    TextPrimary
            )
        }
    }
}


// ============================================================================
// ÍCONO DE BATERÍA
// ============================================================================

@Composable
fun BatteryIcon(
    percentage: Int
) {

    val normalized =
        percentage
            .coerceIn(
                0,
                100
            )
            .toFloat() / 100f


    val fillColor =

        when {

            percentage < 0 ->

                Color.LightGray


            percentage <= 20 ->

                Pink


            percentage <= 50 ->

                Yellow


            else ->

                Green
        }


    Row(
        verticalAlignment =
            Alignment.CenterVertically
    ) {

        // --------------------------------------------------------------------
        // Cuerpo batería
        // --------------------------------------------------------------------

        Box(
            modifier = Modifier
                .width(64.dp)
                .height(31.dp)
                .border(
                    width = 2.dp,

                    color =
                        TextPrimary,

                    shape =
                        RoundedCornerShape(
                            7.dp
                        )
                )
                .padding(4.dp)
        ) {

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(
                        normalized
                    )
                    .clip(
                        RoundedCornerShape(
                            3.dp
                        )
                    )
                    .background(
                        fillColor
                    )
            )
        }


        // --------------------------------------------------------------------
        // Terminal de batería
        // --------------------------------------------------------------------

        Box(
            modifier = Modifier
                .width(5.dp)
                .height(14.dp)
                .background(
                    color =
                        TextPrimary,

                    shape =
                        RoundedCornerShape(
                            topEnd = 3.dp,
                            bottomEnd = 3.dp
                        )
                )
        )
    }
}
