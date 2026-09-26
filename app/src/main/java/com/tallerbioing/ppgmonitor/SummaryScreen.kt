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
    bleManager: BleManager,
    deviceMode: DeviceMode,
    onModeChange: (DeviceMode) -> Unit
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

        // --------------------------------------------------------------------
        // SEMANA ACTUAL
        // --------------------------------------------------------------------

        WeekDashboard()


        Spacer(
            modifier = Modifier.height(14.dp)
        )


        // --------------------------------------------------------------------
        // FRECUENCIA CARDÍACA
        // --------------------------------------------------------------------

        HeartRateCard(
            bpm = bleManager.bpm
        )


        Spacer(
            modifier = Modifier.height(12.dp)
        )


        // --------------------------------------------------------------------
        // ACTIVIDAD
        // --------------------------------------------------------------------

        ActivityCard(
            activityCode =
                bleManager.activityCode
        )


        Spacer(
            modifier = Modifier.height(12.dp)
        )


        // --------------------------------------------------------------------
        // BATERÍA + MODO
        // --------------------------------------------------------------------

        Row(
            modifier = Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

            BatteryCard(
                battery =
                    bleManager.batteryPercentage,

                modifier =
                    Modifier.weight(1f)
            )


            ModeCard(
                currentMode =
                    deviceMode,

                onModeChange =
                    onModeChange,

                modifier =
                    Modifier.weight(1f)
            )
        }


        Spacer(
            modifier = Modifier.height(20.dp)
        )
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


// ============================================================================
// TARJETA MODO DEL DISPOSITIVO
// ============================================================================

@Composable
fun ModeCard(
    currentMode: DeviceMode,
    onModeChange: (DeviceMode) -> Unit,
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
                    PurpleSoft
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation = 2.dp
            )
    ) {

        Column(
            modifier =
                Modifier.padding(14.dp)
        ) {

            Text(
                text = "MODO",

                fontSize = 12.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    TextSecondary
            )


            Spacer(
                modifier =
                    Modifier.height(10.dp)
            )


            ModeButton(
                text =
                    "Modo Uso",

                selected =
                    currentMode ==
                            DeviceMode.USO,

                onClick = {

                    onModeChange(
                        DeviceMode.USO
                    )
                }
            )


            Spacer(
                modifier =
                    Modifier.height(7.dp)
            )


            ModeButton(
                text =
                    "Modo Carga",

                selected =
                    currentMode ==
                            DeviceMode.CARGA,

                onClick = {

                    onModeChange(
                        DeviceMode.CARGA
                    )
                }
            )
        }
    }
}


// ============================================================================
// BOTÓN DE MODO
// ============================================================================

@Composable
fun ModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    11.dp
                )
            ),

        onClick = onClick,

        color =

            if (selected) {

                Purple

            } else {

                White.copy(
                    alpha = 0.72f
                )
            },

        shape =
            RoundedCornerShape(
                11.dp
            )
    ) {

        Text(
            text = text,

            modifier =
                Modifier.padding(
                    vertical = 9.dp,
                    horizontal = 5.dp
                ),

            textAlign =
                TextAlign.Center,

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