package com.tallerbioing.ppgmonitor

import android.os.Bundle

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.tallerbioing.ppgmonitor.data.AppDatabase
import com.tallerbioing.ppgmonitor.data.MeasurementRecorder
import com.tallerbioing.ppgmonitor.ui.theme.PPGMonitorTheme

import kotlinx.coroutines.launch


// ============================================================================
// COLORES
// ============================================================================

val AppBackgroundTop =
    Color(0xFFF9F5FF)

val AppBackgroundBottom =
    Color(0xFFF3F6FC)

val Purple =
    Color(0xFF8C52C7)

val PurpleDark =
    Color(0xFF633393)

val PurpleSoft =
    Color(0xFFEEDCFF)

val Pink =
    Color(0xFFE85B8F)

val PinkSoft =
    Color(0xFFFFE4EE)

val Blue =
    Color(0xFF4E90D9)

val BlueSoft =
    Color(0xFFDCEEFF)

val CyanSoft =
    Color(0xFFDDF7FA)

val Yellow =
    Color(0xFFF2BF49)

val YellowSoft =
    Color(0xFFFFF2CA)

val Green =
    Color(0xFF3DAD79)

val GreenSoft =
    Color(0xFFDDF5E9)

val TextPrimary =
    Color(0xFF24212A)

val TextSecondary =
    Color(0xFF6D6775)

val White =
    Color.White


// ============================================================================
// SECCIONES
// ============================================================================

enum class AppSection {

    RESUMEN,

    REGISTRO,

    CONECTIVIDAD
}


// ============================================================================
// MODO
// ============================================================================

enum class DeviceMode {

    USO,

    CARGA
}


// ============================================================================
// ACTIVITY
// ============================================================================

class MainActivity :
    ComponentActivity() {

    private lateinit var bleManager:
            BleManager


    private lateinit var measurementRecorder:
            MeasurementRecorder


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        bleManager =
            BleManager(
                this
            )


        measurementRecorder =
            MeasurementRecorder(
                applicationContext
            )


        setContent {

            PPGMonitorTheme {

                PPGMonitorApp(

                    bleManager =
                        bleManager,

                    onEnableRecording = {

                        enableTelemetryRecording()
                    },

                    onDisableRecording = {

                        disableTelemetryRecording()
                    }
                )
            }
        }
    }


    // ========================================================================
    // ACTIVAR REGISTRO
    // ========================================================================

    private fun enableTelemetryRecording() {

        bleManager.onTelemetryReceived = {
                telemetry,
                boot,
                epoch ->

            measurementRecorder
                .recordTelemetry(
                    telemetry = telemetry,
                    boot = boot,
                    epoch = epoch
                )
        }
    }


    // ========================================================================
    // DESACTIVAR REGISTRO
    // ========================================================================

    private fun disableTelemetryRecording() {

        bleManager.onTelemetryReceived =
            null
    }


    override fun onDestroy() {

        disableTelemetryRecording()


        bleManager.close()


        measurementRecorder.close()


        super.onDestroy()
    }
}


// ============================================================================
// APP
// ============================================================================

@Composable
fun PPGMonitorApp(

    bleManager: BleManager,

    onEnableRecording: () -> Unit,

    onDisableRecording: () -> Unit
) {

    val context =
        LocalContext.current


    val scope =
        rememberCoroutineScope()


    // ========================================================================
    // PERFIL
    // ========================================================================

    val initialProfile =
        remember {

            PatientPreferences
                .load(
                    context
                )
        }


    var formularioCompleto by
    rememberSaveable {

        mutableStateOf(
            initialProfile != null
        )
    }


    var nombre by
    rememberSaveable {

        mutableStateOf(
            initialProfile
                ?.nombre
                ?: ""
        )
    }


    var edad by
    rememberSaveable {

        mutableStateOf(
            initialProfile
                ?.edad
                ?: ""
        )
    }


    var patologia by
    rememberSaveable {

        mutableStateOf(
            initialProfile
                ?.patologia
                ?: ""
        )
    }


    var medicacion by
    rememberSaveable {

        mutableStateOf(
            initialProfile
                ?.medicacion
                ?: ""
        )
    }


    var peso by
    rememberSaveable {

        mutableStateOf(
            initialProfile
                ?.peso
                ?: ""
        )
    }


    var altura by
    rememberSaveable {

        mutableStateOf(
            initialProfile
                ?.altura
                ?: ""
        )
    }


    // ========================================================================
    // REGISTRO
    // ========================================================================

    LaunchedEffect(
        formularioCompleto
    ) {

        if (
            formularioCompleto
        ) {

            onEnableRecording()

        } else {

            onDisableRecording()
        }
    }


    // ========================================================================
    // NAVEGACIÓN
    // ========================================================================

    var selectedSection by
    remember {

        mutableStateOf(
            AppSection.RESUMEN
        )
    }


    // ========================================================================
    // PERMISOS BLE
    // ========================================================================

    val permissionLauncher =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .RequestMultiplePermissions()

        ) { result ->


            val granted =
                result
                    .values
                    .all {
                        it
                    }


            if (
                granted
            ) {

                bleManager
                    .scanAndConnect()

            } else {

                bleManager
                    .permissionDenied()
            }
        }


    val conectar:
                () -> Unit = {

        if (
            bleManager
                .hasRequiredPermissions()
        ) {

            bleManager
                .scanAndConnect()

        } else {

            permissionLauncher
                .launch(

                    bleManager
                        .requiredPermissions()
                )
        }
    }


    // ========================================================================
    // FONDO
    // ========================================================================

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(

                    brush =
                        Brush.verticalGradient(

                            colors =
                                listOf(

                                    AppBackgroundTop,

                                    AppBackgroundBottom
                                )
                        )
                )
    ) {


        // ====================================================================
        // FORMULARIO
        // ====================================================================

        if (
            !formularioCompleto
        ) {

            PatientFormScreen(

                nombre =
                    nombre,

                edad =
                    edad,

                patologia =
                    patologia,

                medicacion =
                    medicacion,

                peso =
                    peso,

                altura =
                    altura,


                onNombreChange = {

                    nombre =
                        it
                },


                onEdadChange = {

                    edad =
                        it
                },


                onPatologiaChange = {

                    patologia =
                        it
                },


                onMedicacionChange = {

                    medicacion =
                        it
                },


                onPesoChange = {

                    peso =
                        it
                },


                onAlturaChange = {

                    altura =
                        it
                },


                onContinue = {

                    PatientPreferences
                        .save(

                            context =
                                context,

                            profile =
                                PatientProfile(

                                    nombre =
                                        nombre.trim(),

                                    edad =
                                        edad.trim(),

                                    patologia =
                                        patologia.trim(),

                                    medicacion =
                                        medicacion.trim(),

                                    peso =
                                        peso.trim(),

                                    altura =
                                        altura.trim()
                                )
                        )


                    formularioCompleto =
                        true


                    selectedSection =
                        AppSection.RESUMEN
                }
            )


        } else {


            // ================================================================
            // DASHBOARD
            // ================================================================

            MainDashboard(

                nombre =
                    nombre.ifBlank {
                        "Paciente"
                    },


                selectedSection =
                    selectedSection,


                onSectionChange = {

                    selectedSection =
                        it
                },


                // ============================================================
                // EDITAR PACIENTE
                // ============================================================

                onEditPatient = {

                    formularioCompleto =
                        false
                },


                // ============================================================
                // REEMPLAZAR PACIENTE
                // ============================================================

                onReplacePatient = {

                    onDisableRecording()


                    scope.launch {

                        val database =
                            AppDatabase
                                .getDatabase(
                                    context.applicationContext
                                )


                        database
                            .measurementDao()
                            .deleteAllMeasurements()


                        database
                            .patientNoteDao()
                            .deleteAllNotes()


                        PatientPreferences
                            .clear(
                                context
                            )


                        nombre = ""

                        edad = ""

                        patologia = ""

                        medicacion = ""

                        peso = ""

                        altura = ""


                        selectedSection =
                            AppSection.RESUMEN


                        formularioCompleto =
                            false
                    }
                },


                bleManager =
                    bleManager,


                conectar =
                    conectar
            )
        }
    }
}


// ============================================================================
// DASHBOARD
// ============================================================================

@Composable
fun MainDashboard(

    nombre: String,

    selectedSection:
    AppSection,

    onSectionChange:
        (AppSection) -> Unit,

    onEditPatient:
        () -> Unit,

    onReplacePatient:
        () -> Unit,

    bleManager:
    BleManager,

    conectar:
        () -> Unit
) {

    Column(
        modifier =
            Modifier.fillMaxSize()
    ) {


        AppHeader(

            nombre =
                nombre,

            bleConnected =
                bleManager.isConnected,

            onEditPatient =
                onEditPatient
        )


        MainTabs(

            selected =
                selectedSection,

            onSelected =
                onSectionChange
        )


        when (
            selectedSection
        ) {


            AppSection.RESUMEN -> {

                SummaryScreen(
                    bleManager = bleManager
                )
            }


            // =================================================================
            // REGISTRO
            //
            // Ahora le pasamos BleManager para que pueda mostrar:
            // - PPG en tiempo real
            // - BPM actual
            // =================================================================

            AppSection.REGISTRO -> {

                RegisterScreen(

                    bleManager =
                        bleManager
                )
            }


            AppSection.CONECTIVIDAD -> {

                ConnectivityScreen(

                    bleManager =
                        bleManager,

                    conectar =
                        conectar,

                    patientName =
                        nombre,

                    onReplacePatient =
                        onReplacePatient
                )
            }
        }
    }
}


// ============================================================================
// HEADER
// ============================================================================

@Composable
fun AppHeader(

    nombre: String,

    bleConnected: Boolean,

    onEditPatient:
        () -> Unit
) {

    Surface(

        color =
            Purple,

        shadowElevation =
            5.dp
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(

                        horizontal =
                            20.dp,

                        vertical =
                            15.dp
                    ),

            verticalAlignment =
                Alignment.CenterVertically
        ) {


            Box(
                modifier =
                    Modifier
                        .size(
                            48.dp
                        )
                        .clip(
                            CircleShape
                        )
                        .background(

                            White.copy(
                                alpha =
                                    0.94f
                            )
                        ),

                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text =
                        nombre
                            .trim()
                            .take(1)
                            .uppercase()
                            .ifBlank {
                                "P"
                            },

                    fontSize =
                        22.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        PurpleDark
                )
            }


            Spacer(
                modifier =
                    Modifier.width(
                        13.dp
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
                        nombre,

                    fontSize =
                        21.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        White
                )


                Text(
                    text =

                        if (
                            bleConnected
                        ) {

                            "● Dispositivo conectado"

                        } else {

                            "○ Dispositivo desconectado"
                        },

                    fontSize =
                        12.sp,

                    color =

                        if (
                            bleConnected
                        ) {

                            Color(
                                0xFFD9FFE7
                            )

                        } else {

                            White.copy(
                                alpha =
                                    0.72f
                            )
                        }
                )
            }


            TextButton(
                onClick =
                    onEditPatient
            ) {

                Text(
                    text =
                        "Editar",

                    color =
                        White
                )
            }
        }
    }
}


// ============================================================================
// TABS
// ============================================================================

@Composable
fun MainTabs(

    selected:
    AppSection,

    onSelected:
        (AppSection) -> Unit
) {

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    White
                )
                .padding(

                    horizontal =
                        10.dp,

                    vertical =
                        10.dp
                ),

        horizontalArrangement =
            Arrangement.spacedBy(
                6.dp
            )
    ) {


        MainTabButton(

            text =
                "Resumen",

            selected =
                selected ==
                        AppSection.RESUMEN,

            modifier =
                Modifier.weight(
                    1f
                ),

            onClick = {

                onSelected(
                    AppSection.RESUMEN
                )
            }
        )


        MainTabButton(

            text =
                "Registro",

            selected =
                selected ==
                        AppSection.REGISTRO,

            modifier =
                Modifier.weight(
                    1f
                ),

            onClick = {

                onSelected(
                    AppSection.REGISTRO
                )
            }
        )


        MainTabButton(

            text =
                "Conectividad",

            selected =
                selected ==
                        AppSection.CONECTIVIDAD,

            modifier =
                Modifier.weight(
                    1f
                ),

            onClick = {

                onSelected(
                    AppSection.CONECTIVIDAD
                )
            }
        )
    }
}


// ============================================================================
// TAB
// ============================================================================

@Composable
fun MainTabButton(

    text: String,

    selected: Boolean,

    modifier:
    Modifier =
        Modifier,

    onClick:
        () -> Unit
) {

    Surface(

        modifier =
            modifier
                .clip(
                    RoundedCornerShape(
                        13.dp
                    )
                )
                .clickable(
                    onClick =
                        onClick
                ),

        shape =
            RoundedCornerShape(
                13.dp
            ),

        color =

            if (
                selected
            ) {

                Purple

            } else {

                PurpleSoft
            }
    ) {

        Text(
            text =
                text,

            modifier =
                Modifier.padding(

                    vertical =
                        11.dp,

                    horizontal =
                        4.dp
                ),

            textAlign =
                TextAlign.Center,

            color =

                if (
                    selected
                ) {

                    White

                } else {

                    PurpleDark
                },

            fontSize =
                13.sp,

            fontWeight =

                if (
                    selected
                ) {

                    FontWeight.Bold

                } else {

                    FontWeight.Medium
                }
        )
    }
}