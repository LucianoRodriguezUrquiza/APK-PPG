package com.tallerbioing.ppgmonitor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.tallerbioing.ppgmonitor.data.AppDatabase
import com.tallerbioing.ppgmonitor.data.MeasurementEntity
import com.tallerbioing.ppgmonitor.data.PatientNoteEntity

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import kotlin.math.abs
import kotlin.math.roundToInt


// ============================================================================
// CONFIGURACIÓN DE ESTADÍSTICAS
// ============================================================================

private const val MIN_SIGNAL_QUALITY_FOR_HR_STATS =
    3


// ============================================================================
// CONFIGURACIÓN GRÁFICA PPG
// ============================================================================
//
// Firmware BLE:
// 20 muestras por segundo.
//
// BleManager conserva:
// 240 muestras ≈ 12 segundos.
//
// ============================================================================

private const val PPG_VISIBLE_SAMPLES =
    240


// ============================================================================
// SUBPANTALLAS DEL APARTADO REGISTRO
// ============================================================================

enum class RegisterSection {

    MENU,

    DIARIO,

    PROMEDIOS,

    NOTAS
}


// ============================================================================
// PANTALLA PRINCIPAL DE REGISTRO
// ============================================================================

@Composable
fun RegisterScreen(
    bleManager: BleManager
) {

    var section by
    remember {

        mutableStateOf(
            RegisterSection.MENU
        )
    }


    when (
        section
    ) {

        RegisterSection.MENU -> {

            RegisterMenuScreen(

                bleManager =
                    bleManager,


                onDailyClick = {

                    section =
                        RegisterSection.DIARIO
                },


                onAverageClick = {

                    section =
                        RegisterSection.PROMEDIOS
                },


                onNotesClick = {

                    section =
                        RegisterSection.NOTAS
                }
            )
        }


        RegisterSection.DIARIO -> {

            DailyRegisterScreen(

                onBack = {

                    section =
                        RegisterSection.MENU
                }
            )
        }


        RegisterSection.PROMEDIOS -> {

            AverageRegisterScreen(

                onBack = {

                    section =
                        RegisterSection.MENU
                }
            )
        }


        RegisterSection.NOTAS -> {

            PatientNotesScreen(

                onBack = {

                    section =
                        RegisterSection.MENU
                }
            )
        }
    }
}


// ============================================================================
// MENÚ PRINCIPAL
// ============================================================================

@Composable
fun RegisterMenuScreen(

    bleManager: BleManager,

    onDailyClick: () -> Unit,

    onAverageClick: () -> Unit,

    onNotesClick: () -> Unit
) {

    val context =
        LocalContext.current


    val scope =
        rememberCoroutineScope()


    var exportMessage by
    remember {

        mutableStateOf(
            ""
        )
    }


    var exportInProgress by
    remember {

        mutableStateOf(
            false
        )
    }


    // ========================================================================
    // NOMBRE DEL ARCHIVO
    // ========================================================================

    val patientName =
        remember {

            PatientPreferences
                .load(
                    context
                )
                ?.nombre
                ?.trim()
                ?.ifBlank {

                    "paciente"
                }
                ?: "paciente"
        }


    val safePatientName =
        remember(
            patientName
        ) {

            patientName.replace(

                Regex(
                    "[^A-Za-z0-9_-]"
                ),

                "_"
            )
        }


    val currentDate =
        remember {

            LocalDate
                .now()
                .format(

                    DateTimeFormatter.ofPattern(
                        "yyyy-MM-dd"
                    )
                )
        }


    val defaultFileName =
        remember(
            safePatientName,
            currentDate
        ) {

            "PPG_Monitor_${safePatientName}_${currentDate}.csv"
        }


    // ========================================================================
    // SELECTOR CSV
    // ========================================================================

    val csvLauncher =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .CreateDocument(
                        "text/csv"
                    )

        ) { uri ->


            if (
                uri == null
            ) {

                exportMessage =
                    "Exportación cancelada."


                return@rememberLauncherForActivityResult
            }


            exportInProgress =
                true


            scope.launch {

                try {

                    val csvContent =
                        CsvExporter
                            .buildCsv(
                                context.applicationContext
                            )


                    CsvExporter
                        .writeCsv(

                            context =
                                context.applicationContext,

                            uri =
                                uri,

                            csvContent =
                                csvContent
                        )


                    exportMessage =
                        "Informe CSV guardado correctamente."


                } catch (
                    exception: Exception
                ) {

                    exportMessage =
                        "No se pudo guardar el informe: ${
                            exception.message
                                ?: "error desconocido"
                        }"

                } finally {

                    exportInProgress =
                        false
                }
            }
        }


    // ========================================================================
    // INTERFAZ
    // ========================================================================

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 18.dp
                )
    ) {

        Text(
            text =
                "Registro",

            fontSize =
                29.sp,

            fontWeight =
                FontWeight.Bold,

            color =
                TextPrimary
        )


        Spacer(
            modifier =
                Modifier.height(
                    4.dp
                )
        )


        Text(
            text =
                "Historial y organización de las mediciones",

            fontSize =
                14.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(
                    22.dp
                )
        )


        // ====================================================================
        // NUEVO — PPG EN TIEMPO REAL
        // ====================================================================

        PpgRealtimeCard(
            bleManager =
                bleManager
        )


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        // ====================================================================
        // REGISTRO DIARIO
        // ====================================================================

        RegisterOptionCard(

            title =
                "Registro diario",

            subtitle =
                "Consultar las mediciones registradas durante el día.",

            icon =
                "01",

            background =
                BlueSoft,

            onClick =
                onDailyClick
        )


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        // ====================================================================
        // PROMEDIOS
        // ====================================================================

        RegisterOptionCard(

            title =
                "Registros promedios",

            subtitle =
                "Consultar valores promedio y tendencias semanales.",

            icon =
                "AVG",

            background =
                PinkSoft,

            onClick =
                onAverageClick
        )


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        // ====================================================================
        // NOTAS
        // ====================================================================

        RegisterOptionCard(

            title =
                "Notas de la paciente",

            subtitle =
                "Agregar observaciones persistentes asociadas al seguimiento.",

            icon =
                "✎",

            background =
                PurpleSoft,

            onClick =
                onNotesClick
        )


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        // ====================================================================
        // DESCARGAR INFORME CSV
        // ====================================================================

        RegisterOptionCard(

            title =
                if (
                    exportInProgress
                ) {

                    "Generando informe..."

                } else {

                    "Descargar informe"
                },


            subtitle =
                "Exportar paciente, mediciones y notas en formato CSV.",


            icon =
                "↓",


            background =
                YellowSoft,


            onClick = {

                if (
                    !exportInProgress
                ) {

                    exportMessage =
                        ""


                    csvLauncher.launch(
                        defaultFileName
                    )
                }
            }
        )


        // ====================================================================
        // MENSAJE CSV
        // ====================================================================

        if (
            exportMessage.isNotBlank()
        ) {

            Spacer(
                modifier =
                    Modifier.height(
                        15.dp
                    )
            )


            Surface(
                modifier =
                    Modifier.fillMaxWidth(),

                shape =
                    RoundedCornerShape(
                        16.dp
                    ),

                color =
                    White
            ) {

                Text(
                    text =
                        exportMessage,

                    modifier =
                        Modifier.padding(
                            15.dp
                        ),

                    textAlign =
                        TextAlign.Center,

                    fontSize =
                        12.sp,

                    fontWeight =
                        FontWeight.Medium,

                    color =
                        if (
                            exportMessage.contains(
                                "correctamente",
                                ignoreCase = true
                            )
                        ) {

                            Green

                        } else {

                            TextSecondary
                        }
                )
            }
        }


        Spacer(
            modifier =
                Modifier.height(
                    20.dp
                )
        )
    }
}


// ============================================================================
// PPG EN TIEMPO REAL
// ============================================================================

@Composable
fun PpgRealtimeCard(
    bleManager: BleManager
) {

    // ========================================================================
    // La lista es observable porque proviene del SnapshotStateList
    // administrado por BleManager.
    // ========================================================================

    val samples =
        bleManager
            .ppgSamples
            .toList()


    val bpm =
        bleManager.bpm


    val connected =
        bleManager.isConnected


    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                23.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    CyanSoft
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation =
                    2.dp
            )
    ) {

        Column(
            modifier =
                Modifier.padding(
                    18.dp
                )
        ) {

            // =================================================================
            // CABECERA
            // =================================================================

            Row(
                modifier =
                    Modifier.fillMaxWidth(),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Column(
                    modifier =
                        Modifier.weight(
                            1f
                        )
                ) {

                    Text(
                        text =
                            "PPG en tiempo real",

                        fontSize =
                            19.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            TextPrimary
                    )


                    Spacer(
                        modifier =
                            Modifier.height(
                                2.dp
                            )
                    )


                    Text(
                        text =
                            "Señal pulsátil filtrada",

                        fontSize =
                            12.sp,

                        color =
                            TextSecondary
                    )
                }


                // =============================================================
                // BPM
                // =============================================================

                Column(
                    horizontalAlignment =
                        Alignment.End
                ) {

                    Text(
                        text =
                            if (
                                bpm > 0
                            ) {

                                bpm.toString()

                            } else {

                                "--"
                            },

                        fontSize =
                            32.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            PurpleDark
                    )


                    Text(
                        text =
                            "BPM",

                        fontSize =
                            11.sp,

                        fontWeight =
                            FontWeight.Bold,

                        color =
                            TextSecondary
                    )
                }
            }


            Spacer(
                modifier =
                    Modifier.height(
                        14.dp
                    )
            )


            // =================================================================
            // GRÁFICA
            // =================================================================

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(
                            185.dp
                        )
                        .background(

                            color =
                                White,

                            shape =
                                RoundedCornerShape(
                                    16.dp
                                )
                        )
                        .padding(
                            9.dp
                        ),

                contentAlignment =
                    Alignment.Center
            ) {


                if (
                    samples.size <
                    2
                ) {

                    Text(
                        text =
                            if (
                                connected
                            ) {

                                "Esperando señal PPG..."

                            } else {

                                "Dispositivo desconectado"
                            },

                        fontSize =
                            13.sp,

                        color =
                            TextSecondary,

                        textAlign =
                            TextAlign.Center
                    )

                } else {

                    Canvas(
                        modifier =
                            Modifier.fillMaxSize()
                    ) {

                        // ====================================================
                        // LÍNEAS GUÍA
                        // ====================================================

                        val centerY =
                            size.height /
                                    2f


                        val quarter1 =
                            size.height *
                                    0.25f


                        val quarter3 =
                            size.height *
                                    0.75f


                        val guideColor =
                            Color(
                                0xFFE8E3EC
                            )


                        drawLine(

                            color =
                                guideColor,

                            start =
                                Offset(
                                    0f,
                                    quarter1
                                ),

                            end =
                                Offset(
                                    size.width,
                                    quarter1
                                ),

                            strokeWidth =
                                1.dp.toPx()
                        )


                        drawLine(

                            color =
                                Color(
                                    0xFFD8D1DE
                                ),

                            start =
                                Offset(
                                    0f,
                                    centerY
                                ),

                            end =
                                Offset(
                                    size.width,
                                    centerY
                                ),

                            strokeWidth =
                                1.dp.toPx()
                        )


                        drawLine(

                            color =
                                guideColor,

                            start =
                                Offset(
                                    0f,
                                    quarter3
                                ),

                            end =
                                Offset(
                                    size.width,
                                    quarter3
                                ),

                            strokeWidth =
                                1.dp.toPx()
                        )


                        // ====================================================
                        // ESCALADO ROBUSTO
                        //
                        // Se usa el percentil aproximado 95 de la amplitud
                        // absoluta.
                        //
                        // Esto es solamente visual.
                        //
                        // Los valores BLE no se modifican.
                        // ====================================================

                        val absoluteValues =
                            samples
                                .map {
                                    abs(it)
                                }
                                .sorted()


                        val index95 =
                            (
                                    (
                                            absoluteValues.size -
                                                    1
                                            ) *
                                            0.95f
                                    )
                                .toInt()
                                .coerceIn(

                                    0,

                                    absoluteValues.size -
                                            1
                                )


                        val scale =
                            absoluteValues[
                                index95
                            ]
                                .coerceAtLeast(
                                    1f
                                )


                        val graphAmplitude =
                            size.height *
                                    0.41f


                        // ====================================================
                        // X FIJO DE 240 MUESTRAS
                        //
                        // Cuando todavía no existen 240 puntos, la onda entra
                        // desde la derecha.
                        //
                        // Cuando se llena la ventana, se desplaza.
                        // ====================================================

                        val maxSamples =
                            PPG_VISIBLE_SAMPLES


                        val deltaX =
                            size.width /
                                    (
                                            maxSamples -
                                                    1
                                            ).toFloat()


                        val startX =
                            size.width -
                                    deltaX *
                                    (
                                            samples.size -
                                                    1
                                            )


                        // ====================================================
                        // DIBUJAR PPG
                        // ====================================================

                        for (
                        index in
                        1 until
                                samples.size
                        ) {

                            val previous =
                                (
                                        samples[
                                            index -
                                                    1
                                        ] /
                                                scale
                                        )
                                    .coerceIn(
                                        -1.25f,
                                        1.25f
                                    ) /
                                        1.25f


                            val current =
                                (
                                        samples[
                                            index
                                        ] /
                                                scale
                                        )
                                    .coerceIn(
                                        -1.25f,
                                        1.25f
                                    ) /
                                        1.25f


                            val x1 =
                                startX +
                                        (
                                                index -
                                                        1
                                                ) *
                                        deltaX


                            val x2 =
                                startX +
                                        index *
                                        deltaX


                            val y1 =
                                centerY -
                                        previous *
                                        graphAmplitude


                            val y2 =
                                centerY -
                                        current *
                                        graphAmplitude


                            drawLine(

                                color =
                                    PurpleDark,

                                start =
                                    Offset(
                                        x1,
                                        y1
                                    ),

                                end =
                                    Offset(
                                        x2,
                                        y2
                                    ),

                                strokeWidth =
                                    2.dp.toPx()
                            )
                        }
                    }
                }
            }


            Spacer(
                modifier =
                    Modifier.height(
                        9.dp
                    )
            )


            // =================================================================
            // ESTADO
            // =================================================================

            Text(
                text =
                    when {

                        !connected ->

                            "Sin conexión BLE"


                        samples.size <
                                2 ->

                            "Sin stream PPG válido."


                        else ->

                            "Ventana aproximada: 12 segundos"
                    },

                fontSize =
                    10.sp,

                color =
                    TextSecondary
            )
        }
    }
}


// ============================================================================
// TARJETA DEL MENÚ
// ============================================================================

@Composable
fun RegisterOptionCard(

    title: String,

    subtitle: String,

    icon: String,

    background: Color,

    onClick: () -> Unit
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClick =
                        onClick
                ),

        shape =
            RoundedCornerShape(
                23.dp
            ),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    background
            ),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation =
                    2.dp
            )
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        19.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Box(
                modifier =
                    Modifier
                        .size(
                            52.dp
                        )
                        .background(

                            color =
                                White.copy(
                                    alpha =
                                        0.72f
                                ),

                            shape =
                                RoundedCornerShape(
                                    16.dp
                                )
                        ),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text =
                        icon,

                    fontSize =
                        16.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        PurpleDark
                )
            }


            Spacer(
                modifier =
                    Modifier.size(
                        15.dp
                    )
            )


            Column(
                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {

                Text(
                    text =
                        title,

                    fontSize =
                        19.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        TextPrimary
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            3.dp
                        )
                )


                Text(
                    text =
                        subtitle,

                    fontSize =
                        12.sp,

                    color =
                        TextSecondary
                )
            }


            Text(
                text =
                    "›",

                fontSize =
                    30.sp,

                color =
                    TextSecondary
            )
        }
    }
}


// ============================================================================
// REGISTRO DIARIO
// ============================================================================

@Composable
fun DailyRegisterScreen(
    onBack: () -> Unit
) {

    val context =
        LocalContext.current


    val measurementDao =
        remember {

            AppDatabase
                .getDatabase(
                    context.applicationContext
                )
                .measurementDao()
        }


    val today =
        remember {

            LocalDate.now()
        }


    val formattedDate =
        remember(
            today
        ) {

            today.format(

                DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy"
                )
            )
        }


    val startTimestamp =
        remember(
            today
        ) {

            today
                .atStartOfDay(
                    ZoneId.systemDefault()
                )
                .toInstant()
                .toEpochMilli()
        }


    val endTimestamp =
        remember(
            today
        ) {

            today
                .plusDays(
                    1
                )
                .atStartOfDay(
                    ZoneId.systemDefault()
                )
                .toInstant()
                .toEpochMilli() -
                    1L
        }


    var measurements by
    remember {

        mutableStateOf(
            emptyList<MeasurementEntity>()
        )
    }


    var loading by
    remember {

        mutableStateOf(
            true
        )
    }


    var loadError by
    remember {

        mutableStateOf<String?>(
            null
        )
    }


    LaunchedEffect(
        startTimestamp,
        endTimestamp
    ) {

        while (
            true
        ) {

            try {

                measurements =
                    measurementDao
                        .getMeasurementsBetween(

                            startTimestamp =
                                startTimestamp,

                            endTimestamp =
                                endTimestamp
                        )


                loading =
                    false


                loadError =
                    null

            } catch (
                exception: Exception
            ) {

                loading =
                    false


                loadError =
                    exception.message
                        ?: "Error al leer la base de datos"
            }


            delay(
                2000L
            )
        }
    }


    // ========================================================================
    // FC
    // ========================================================================

    val reliableHeartMeasurements =
        measurements.filter {

            it.bpm >
                    0 &&
                    it.signalQuality >=
                    MIN_SIGNAL_QUALITY_FOR_HR_STATS
        }


    val validBpms =
        reliableHeartMeasurements.map {

            it.bpm
        }


    val averageBpm =
        validBpms
            .takeIf {

                it.isNotEmpty()
            }
            ?.average()
            ?.roundToInt()


    val minimumBpm =
        validBpms
            .minOrNull()


    val maximumBpm =
        validBpms
            .maxOrNull()


    // ========================================================================
    // ACTIVIDAD
    // ========================================================================

    val validActivityMeasurements =
        measurements.filter {

            it.activityCode in
                    0..2
        }


    val totalActivitySamples =
        validActivityMeasurements
            .size


    val restCount =
        validActivityMeasurements
            .count {

                it.activityCode ==
                        0
            }


    val lightCount =
        validActivityMeasurements
            .count {

                it.activityCode ==
                        1
            }


    val moderateCount =
        validActivityMeasurements
            .count {

                it.activityCode ==
                        2
            }


    val restPercentage =
        calculatePercentage(
            restCount,
            totalActivitySamples
        )


    val lightPercentage =
        calculatePercentage(
            lightCount,
            totalActivitySamples
        )


    val moderatePercentage =
        calculatePercentage(
            moderateCount,
            totalActivitySamples
        )


    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 18.dp
                )
    ) {

        RegisterBackHeader(
            title =
                "Registro diario",

            onBack =
                onBack
        )


        Spacer(
            modifier =
                Modifier.height(
                    18.dp
                )
        )


        Text(
            text =
                formattedDate,

            fontSize =
                14.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(
                    8.dp
                )
        )


        Surface(
            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    15.dp
                ),

            color =
                White
        ) {

            Text(
                text =
                    when {

                        loading ->

                            "Cargando registros..."


                        loadError !=
                                null ->

                            "Error: $loadError"


                        else ->

                            "Registros guardados hoy: ${measurements.size}"
                    },

                modifier =
                    Modifier.padding(
                        13.dp
                    ),

                fontSize =
                    13.sp,

                fontWeight =
                    FontWeight.Medium,

                color =
                    TextSecondary
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    15.dp
                )
        )


        DailyDataCard(

            title =
                "Frecuencia cardíaca",

            mainValue =
                averageBpm
                    ?.let {

                        "$it BPM"
                    }
                    ?: "-- BPM",

            detail1 =
                minimumBpm
                    ?.let {

                        "Mínima: $it BPM"
                    }
                    ?: "Mínima: -- BPM",

            detail2 =
                maximumBpm
                    ?.let {

                        "Máxima: $it BPM"
                    }
                    ?: "Máxima: -- BPM",

            background =
                PinkSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    7.dp
                )
        )


        Text(
            text =
                "FC calculada con ${reliableHeartMeasurements.size} muestras de calidad buena o excelente.",

            fontSize =
                11.sp,

            color =
                TextSecondary,

            modifier =
                Modifier.padding(
                    horizontal =
                        8.dp
                )
        )


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        DailyDataCard(

            title =
                "Actividad",

            mainValue =
                if (
                    totalActivitySamples >
                    0
                ) {

                    "Datos del día"

                } else {

                    "Sin registros"
                },

            detail1 =
                if (
                    totalActivitySamples >
                    0
                ) {

                    "Reposo: $restPercentage %   |   Leve: $lightPercentage %"

                } else {

                    "Reposo: -- %   |   Leve: -- %"
                },

            detail2 =
                if (
                    totalActivitySamples >
                    0
                ) {

                    "Moderado: $moderatePercentage %"

                } else {

                    "Moderado: -- %"
                },

            background =
                BlueSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        DailyDataCard(

            title =
                "Mediciones almacenadas",

            mainValue =
                measurements
                    .size
                    .toString(),

            detail1 =
                "Muestras guardadas hoy",

            detail2 =
                "Intervalo aproximado: 5 segundos",

            background =
                GreenSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        DailyDataCard(

            title =
                "SpO₂ exploratoria",

            mainValue =
                "-- %",

            detail1 =
                "Aún no almacenada en el registro",

            detail2 =
                "Dato exploratorio no diagnóstico",

            background =
                PurpleSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    15.dp
                )
        )


        Text(
            text =
                "Las mediciones originales permanecen almacenadas en Room. El filtro de calidad sólo se aplica al cálculo estadístico de frecuencia cardíaca.",

            fontSize =
                11.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(
                    20.dp
                )
        )
    }
}


// ============================================================================
// REGISTROS PROMEDIOS
// ============================================================================

@Composable
fun AverageRegisterScreen(
    onBack: () -> Unit
) {

    val context =
        LocalContext.current


    val measurementDao =
        remember {

            AppDatabase
                .getDatabase(
                    context.applicationContext
                )
                .measurementDao()
        }


    val today =
        remember {

            LocalDate.now()
        }


    val startOfWeekDate =
        remember(
            today
        ) {

            today.minusDays(

                (
                        today.dayOfWeek.value -
                                1
                        ).toLong()
            )
        }


    val startTimestamp =
        remember(
            startOfWeekDate
        ) {

            startOfWeekDate
                .atStartOfDay(
                    ZoneId.systemDefault()
                )
                .toInstant()
                .toEpochMilli()
        }


    val endTimestamp =
        remember(
            today
        ) {

            today
                .plusDays(
                    1
                )
                .atStartOfDay(
                    ZoneId.systemDefault()
                )
                .toInstant()
                .toEpochMilli() -
                    1L
        }


    val dateFormatter =
        remember {

            DateTimeFormatter.ofPattern(
                "dd/MM"
            )
        }


    val formattedRange =
        remember(
            startOfWeekDate,
            today
        ) {

            "${startOfWeekDate.format(dateFormatter)} - ${
                today.format(dateFormatter)
            }"
        }


    var measurements by
    remember {

        mutableStateOf(
            emptyList<MeasurementEntity>()
        )
    }


    var loading by
    remember {

        mutableStateOf(
            true
        )
    }


    var loadError by
    remember {

        mutableStateOf<String?>(
            null
        )
    }


    LaunchedEffect(
        startTimestamp,
        endTimestamp
    ) {

        while (
            true
        ) {

            try {

                measurements =
                    measurementDao
                        .getMeasurementsBetween(

                            startTimestamp =
                                startTimestamp,

                            endTimestamp =
                                endTimestamp
                        )


                loading =
                    false


                loadError =
                    null

            } catch (
                exception: Exception
            ) {

                loading =
                    false


                loadError =
                    exception.message
                        ?: "Error al leer la base de datos"
            }


            delay(
                2000L
            )
        }
    }


    // ========================================================================
    // FC
    // ========================================================================

    val reliableHeartMeasurements =
        measurements.filter {

            it.bpm >
                    0 &&
                    it.signalQuality >=
                    MIN_SIGNAL_QUALITY_FOR_HR_STATS
        }


    val weeklyBpms =
        reliableHeartMeasurements.map {

            it.bpm
        }


    val weeklyAverageBpm =
        weeklyBpms
            .takeIf {

                it.isNotEmpty()
            }
            ?.average()
            ?.roundToInt()


    val weeklyMinimumBpm =
        weeklyBpms
            .minOrNull()


    val weeklyMaximumBpm =
        weeklyBpms
            .maxOrNull()


    // ========================================================================
    // ACTIVIDAD
    // ========================================================================

    val validActivityMeasurements =
        measurements.filter {

            it.activityCode in
                    0..2
        }


    val totalActivitySamples =
        validActivityMeasurements
            .size


    val restCount =
        validActivityMeasurements
            .count {

                it.activityCode ==
                        0
            }


    val lightCount =
        validActivityMeasurements
            .count {

                it.activityCode ==
                        1
            }


    val moderateCount =
        validActivityMeasurements
            .count {

                it.activityCode ==
                        2
            }


    val restPercentage =
        calculatePercentage(
            restCount,
            totalActivitySamples
        )


    val lightPercentage =
        calculatePercentage(
            lightCount,
            totalActivitySamples
        )


    val moderatePercentage =
        calculatePercentage(
            moderateCount,
            totalActivitySamples
        )


    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 18.dp
                )
    ) {

        RegisterBackHeader(

            title =
                "Registros promedios",

            onBack =
                onBack
        )


        Spacer(
            modifier =
                Modifier.height(
                    18.dp
                )
        )


        Text(
            text =
                "Semana actual",

            fontSize =
                18.sp,

            fontWeight =
                FontWeight.Bold,

            color =
                TextPrimary
        )


        Text(
            text =
                formattedRange,

            fontSize =
                13.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        Surface(
            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    15.dp
                ),

            color =
                White
        ) {

            Text(
                text =
                    when {

                        loading ->

                            "Cargando registros..."


                        loadError !=
                                null ->

                            "Error: $loadError"


                        else ->

                            "Mediciones almacenadas esta semana: ${measurements.size}"
                    },

                modifier =
                    Modifier.padding(
                        13.dp
                    ),

                fontSize =
                    13.sp,

                color =
                    TextSecondary
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    15.dp
                )
        )


        AverageDataCard(

            title =
                "Frecuencia cardíaca",

            value =
                weeklyAverageBpm
                    ?.let {

                        "$it BPM"
                    }
                    ?: "-- BPM",

            description =
                "Promedio semanal",

            background =
                PinkSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    10.dp
                )
        )


        AverageDataCard(

            title =
                "FC mínima",

            value =
                weeklyMinimumBpm
                    ?.let {

                        "$it BPM"
                    }
                    ?: "-- BPM",

            description =
                "Mínimo semanal con señal confiable",

            background =
                PurpleSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    10.dp
                )
        )


        AverageDataCard(

            title =
                "FC máxima",

            value =
                weeklyMaximumBpm
                    ?.let {

                        "$it BPM"
                    }
                    ?: "-- BPM",

            description =
                "Máximo semanal con señal confiable",

            background =
                YellowSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    10.dp
                )
        )


        AverageDataCard(

            title =
                "Reposo",

            value =
                if (
                    totalActivitySamples >
                    0
                ) {

                    "$restPercentage %"

                } else {

                    "-- %"
                },

            description =
                "Proporción de muestras semanales",

            background =
                BlueSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    10.dp
                )
        )


        AverageDataCard(

            title =
                "Movimiento leve",

            value =
                if (
                    totalActivitySamples >
                    0
                ) {

                    "$lightPercentage %"

                } else {

                    "-- %"
                },

            description =
                "Proporción de muestras semanales",

            background =
                GreenSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    10.dp
                )
        )


        AverageDataCard(

            title =
                "Movimiento moderado",

            value =
                if (
                    totalActivitySamples >
                    0
                ) {

                    "$moderatePercentage %"

                } else {

                    "-- %"
                },

            description =
                "Proporción de muestras semanales",

            background =
                YellowSoft
        )


        Spacer(
            modifier =
                Modifier.height(
                    14.dp
                )
        )


        Text(
            text =
                "Muestras utilizadas para FC: ${reliableHeartMeasurements.size} de ${measurements.size}. Se consideran para FC únicamente señales con calidad buena o excelente.",

            fontSize =
                11.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(
                    20.dp
                )
        )
    }
}


// ============================================================================
// NOTAS DE LA PACIENTE
// ============================================================================

@Composable
fun PatientNotesScreen(
    onBack: () -> Unit
) {

    val context =
        LocalContext.current


    val patientNoteDao =
        remember {

            AppDatabase
                .getDatabase(
                    context.applicationContext
                )
                .patientNoteDao()
        }


    val scope =
        rememberCoroutineScope()


    var currentNote by
    remember {

        mutableStateOf(
            ""
        )
    }


    var savedNotes by
    remember {

        mutableStateOf(
            emptyList<PatientNoteEntity>()
        )
    }


    var loadingNotes by
    remember {

        mutableStateOf(
            true
        )
    }


    var notesError by
    remember {

        mutableStateOf<String?>(
            null
        )
    }


    LaunchedEffect(
        Unit
    ) {

        try {

            savedNotes =
                patientNoteDao
                    .getAllNotes()


            notesError =
                null

        } catch (
            exception: Exception
        ) {

            notesError =
                exception.message
                    ?: "Error al cargar las notas"
        }


        loadingNotes =
            false
    }


    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 18.dp
                )
    ) {

        RegisterBackHeader(

            title =
                "Notas de la paciente",

            onBack =
                onBack
        )


        Spacer(
            modifier =
                Modifier.height(
                    20.dp
                )
        )


        OutlinedTextField(

            value =
                currentNote,


            onValueChange = {

                currentNote =
                    it
            },


            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        135.dp
                    ),


            label = {

                Text(
                    text =
                        "Escribir una nota"
                )
            },


            shape =
                RoundedCornerShape(
                    18.dp
                ),


            colors =
                OutlinedTextFieldDefaults
                    .colors(

                        focusedTextColor =
                            Color.Black,

                        unfocusedTextColor =
                            Color.Black,

                        disabledTextColor =
                            Color.Black,

                        focusedLabelColor =
                            Purple,

                        unfocusedLabelColor =
                            Color(
                                0xFF55515A
                            ),

                        cursorColor =
                            Purple,

                        focusedBorderColor =
                            Purple,

                        unfocusedBorderColor =
                            Color(
                                0xFF77727B
                            )
                    )
        )


        Spacer(
            modifier =
                Modifier.height(
                    10.dp
                )
        )


        Button(
            modifier =
                Modifier.fillMaxWidth(),

            enabled =
                currentNote.isNotBlank(),

            colors =
                ButtonDefaults
                    .buttonColors(

                        containerColor =
                            Purple,

                        contentColor =
                            White,

                        disabledContainerColor =
                            Purple.copy(
                                alpha =
                                    0.30f
                            ),

                        disabledContentColor =
                            White.copy(
                                alpha =
                                    0.55f
                            )
                    ),

            onClick = {

                val cleanText =
                    currentNote
                        .trim()


                if (
                    cleanText.isNotBlank()
                ) {

                    scope.launch {

                        try {

                            patientNoteDao
                                .insertNote(

                                    PatientNoteEntity(

                                        timestamp =
                                            System.currentTimeMillis(),

                                        text =
                                            cleanText
                                    )
                                )


                            savedNotes =
                                patientNoteDao
                                    .getAllNotes()


                            currentNote =
                                ""


                            notesError =
                                null

                        } catch (
                            exception: Exception
                        ) {

                            notesError =
                                exception.message
                                    ?: "Error al guardar la nota"
                        }
                    }
                }
            }
        ) {

            Text(
                text =
                    "GUARDAR NOTA",

                color =
                    White,

                fontWeight =
                    FontWeight.Bold
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    18.dp
                )
        )


        Surface(
            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    15.dp
                ),

            color =
                White
        ) {

            Text(
                text =
                    when {

                        loadingNotes ->

                            "Cargando notas..."


                        notesError !=
                                null ->

                            "Error: $notesError"


                        else ->

                            "Notas guardadas: ${savedNotes.size}"
                    },

                modifier =
                    Modifier.padding(
                        13.dp
                    ),

                fontSize =
                    13.sp,

                color =
                    TextSecondary
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    20.dp
                )
        )


        Text(
            text =
                "Notas guardadas",

            fontSize =
                18.sp,

            fontWeight =
                FontWeight.Bold,

            color =
                TextPrimary
        )


        Spacer(
            modifier =
                Modifier.height(
                    10.dp
                )
        )


        if (
            !loadingNotes &&
            savedNotes.isEmpty()
        ) {

            Text(
                text =
                    "Todavía no hay notas.",

                fontSize =
                    13.sp,

                color =
                    TextSecondary
            )

        } else {

            savedNotes.forEach { note ->

                NoteCard(

                    note =
                        note,


                    onDelete = {

                        scope.launch {

                            try {

                                patientNoteDao
                                    .deleteNote(
                                        note
                                    )


                                savedNotes =
                                    patientNoteDao
                                        .getAllNotes()


                                notesError =
                                    null

                            } catch (
                                exception: Exception
                            ) {

                                notesError =
                                    exception.message
                                        ?: "Error al eliminar la nota"
                            }
                        }
                    }
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            9.dp
                        )
                )
            }
        }


        Spacer(
            modifier =
                Modifier.height(
                    12.dp
                )
        )


        Text(
            text =
                "Las notas se almacenan permanentemente en la base de datos local de la aplicación.",

            fontSize =
                11.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(
                    20.dp
                )
        )
    }
}


// ============================================================================
// TARJETA DE NOTA
// ============================================================================

@Composable
fun NoteCard(

    note: PatientNoteEntity,

    onDelete: () -> Unit
) {

    val formattedTimestamp =
        remember(
            note.timestamp
        ) {

            Instant
                .ofEpochMilli(
                    note.timestamp
                )
                .atZone(
                    ZoneId.systemDefault()
                )
                .format(

                    DateTimeFormatter.ofPattern(
                        "dd/MM/yyyy HH:mm"
                    )
                )
        }


    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                18.dp
            ),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    PurpleSoft
            )
    ) {

        Column(
            modifier =
                Modifier.padding(
                    16.dp
                )
        ) {

            Text(
                text =
                    note.text,

                fontSize =
                    14.sp,

                color =
                    TextPrimary
            )


            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )


            Text(
                text =
                    formattedTimestamp,

                fontSize =
                    11.sp,

                color =
                    TextSecondary
            )


            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )


            Text(
                text =
                    "Eliminar",

                modifier =
                    Modifier.clickable(
                        onClick =
                            onDelete
                    ),

                fontSize =
                    12.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    Pink
            )
        }
    }
}


// ============================================================================
// CALCULAR PORCENTAJE
// ============================================================================

fun calculatePercentage(
    value: Int,
    total: Int
): Int {

    if (
        total <=
        0
    ) {

        return 0
    }


    return (
            value.toDouble() /
                    total.toDouble() *
                    100.0
            )
        .roundToInt()
}


// ============================================================================
// TARJETA DE DATOS DIARIOS
// ============================================================================

@Composable
fun DailyDataCard(

    title: String,

    mainValue: String,

    detail1: String,

    detail2: String,

    background: Color
) {

    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                22.dp
            ),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    background
            )
    ) {

        Column(
            modifier =
                Modifier.padding(
                    19.dp
                )
        ) {

            Text(
                text =
                    title,

                fontSize =
                    13.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    TextSecondary
            )


            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )


            Text(
                text =
                    mainValue,

                fontSize =
                    29.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    TextPrimary
            )


            Spacer(
                modifier =
                    Modifier.height(
                        8.dp
                    )
            )


            Text(
                text =
                    detail1,

                fontSize =
                    13.sp,

                color =
                    TextSecondary
            )


            Text(
                text =
                    detail2,

                fontSize =
                    13.sp,

                color =
                    TextSecondary
            )
        }
    }
}


// ============================================================================
// TARJETA DE PROMEDIO
// ============================================================================

@Composable
fun AverageDataCard(

    title: String,

    value: String,

    description: String,

    background: Color
) {

    Card(
        modifier =
            Modifier.fillMaxWidth(),

        shape =
            RoundedCornerShape(
                20.dp
            ),

        colors =
            CardDefaults.cardColors(

                containerColor =
                    background
            )
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        18.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(
                modifier =
                    Modifier.weight(
                        1f
                    )
            ) {

                Text(
                    text =
                        title,

                    fontSize =
                        16.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        TextPrimary
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            2.dp
                        )
                )


                Text(
                    text =
                        description,

                    fontSize =
                        12.sp,

                    color =
                        TextSecondary
                )
            }


            Text(
                text =
                    value,

                fontSize =
                    23.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    TextPrimary
            )
        }
    }
}


// ============================================================================
// HEADER CON VOLVER
// ============================================================================

@Composable
fun RegisterBackHeader(

    title: String,

    onBack: () -> Unit
) {

    Row(
        modifier =
            Modifier.fillMaxWidth(),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Surface(
            modifier =
                Modifier.clickable(
                    onClick =
                        onBack
                ),

            shape =
                RoundedCornerShape(
                    14.dp
                ),

            color =
                PurpleSoft
        ) {

            Text(
                text =
                    "‹",

                modifier =
                    Modifier.padding(
                        horizontal = 17.dp,
                        vertical = 7.dp
                    ),

                fontSize =
                    29.sp,

                fontWeight =
                    FontWeight.Bold,

                color =
                    PurpleDark
            )
        }


        Spacer(
            modifier =
                Modifier.size(
                    13.dp
                )
        )


        Text(
            text =
                title,

            fontSize =
                26.sp,

            fontWeight =
                FontWeight.Bold,

            color =
                TextPrimary
        )
    }
}