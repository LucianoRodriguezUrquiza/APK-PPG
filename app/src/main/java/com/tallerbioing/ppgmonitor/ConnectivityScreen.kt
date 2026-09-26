package com.tallerbioing.ppgmonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


// ============================================================================
// COLORES DEL ESTADO BLE
// ============================================================================

private val ConnectionGreen =
    Color(0xFF3DAD79)

private val ConnectionGreenSoft =
    Color(0xFFDDF5E9)

private val ConnectionRed =
    Color(0xFFC83C5A)

private val ConnectionRedSoft =
    Color(0xFFFFE3E8)

private val ConnectionWaiting =
    Color(0xFFE3A83B)

private val ConnectionWaitingSoft =
    Color(0xFFFFF1CC)


// ============================================================================
// CONECTIVIDAD
// ============================================================================

@Composable
fun ConnectivityScreen(

    bleManager: BleManager,

    conectar: () -> Unit,

    patientName: String,

    onReplacePatient: () -> Unit
) {

    var showReplaceDialog by
    remember {

        mutableStateOf(
            false
        )
    }


    // ========================================================================
    // DETERMINAR ESTADO VISUAL DE LA CONEXIÓN
    // ========================================================================

    val internalState =
        bleManager.connectionState.lowercase()


    val connectionInProgress =

        internalState.contains("buscando") ||
                internalState.contains("conectando") ||
                internalState.contains("descubriendo") ||
                internalState.contains("activando")


    val stateBackground =
        when {

            bleManager.isConnected ->
                ConnectionGreenSoft

            connectionInProgress ->
                ConnectionWaitingSoft

            else ->
                ConnectionRedSoft
        }


    val stateColor =
        when {

            bleManager.isConnected ->
                ConnectionGreen

            connectionInProgress ->
                ConnectionWaiting

            else ->
                ConnectionRed
        }


    val mainStateText =
        when {

            bleManager.isConnected ->
                "Estado: Conectado"

            connectionInProgress ->
                "Conectando..."

            else ->
                "Conexión fallida"
        }


    // ========================================================================
    // INTERFAZ
    // ========================================================================

    Column(
        modifier = Modifier
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
            text = "Conectividad",

            fontSize = 29.sp,

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
                "Conexión Bluetooth y configuración del dispositivo",

            fontSize =
                14.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(
                    20.dp
                )
        )


        // ====================================================================
        // ESTADO BLE
        // ====================================================================

        Card(
            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    24.dp
                ),

            colors =
                CardDefaults.cardColors(
                    containerColor =
                        stateBackground
                )
        ) {

            Column(
                modifier =
                    Modifier.padding(
                        20.dp
                    )
            ) {

                Text(
                    text =
                        "PPG-Monitor-S3",

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
                            10.dp
                        )
                )


                Text(
                    text =
                        mainStateText,

                    fontSize =
                        24.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        stateColor
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            5.dp
                        )
                )


                // Estado interno útil para diagnóstico
                Text(
                    text =
                        bleManager.connectionState,

                    fontSize =
                        12.sp,

                    color =
                        TextSecondary
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            16.dp
                        )
                )


                Button(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(
                                52.dp
                            ),

                    enabled =
                        !bleManager.isConnected &&
                                !connectionInProgress,

                    colors =
                        ButtonDefaults.buttonColors(

                            containerColor =
                                Purple,

                            contentColor =
                                White,

                            disabledContainerColor =
                                Purple.copy(
                                    alpha = 0.30f
                                ),

                            disabledContentColor =
                                White.copy(
                                    alpha = 0.60f
                                )
                        ),

                    onClick =
                        conectar
                ) {

                    Text(
                        text =
                            if (
                                bleManager.isConnected
                            ) {

                                "CONECTADO"

                            } else if (
                                connectionInProgress
                            ) {

                                "CONECTANDO..."

                            } else {

                                "BUSCAR Y CONECTAR"
                            },

                        color =
                            White,

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }


        Spacer(
            modifier =
                Modifier.height(
                    14.dp
                )
        )


        // ====================================================================
        // ÚLTIMO DATO
        // ====================================================================

        Surface(
            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    18.dp
                ),

            color =
                White
        ) {

            Column(
                modifier =
                    Modifier.padding(
                        16.dp
                    )
            ) {

                Text(
                    text =
                        "Último dato recibido",

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        TextPrimary
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            6.dp
                        )
                )


                Text(
                    text =
                        bleManager.lastPacket,

                    fontSize =
                        13.sp,

                    color =
                        TextSecondary
                )
            }
        }


        Spacer(
            modifier =
                Modifier.height(
                    18.dp
                )
        )


        // ====================================================================
        // CONTROL OLED
        // ====================================================================

        Text(
            text =
                "Pantalla OLED",

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


        Row(
            modifier =
                Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.spacedBy(
                    8.dp
                )
        ) {

            OledButton(
                text =
                    "BPM",

                enabled =
                    bleManager.isConnected,

                modifier =
                    Modifier.weight(
                        1f
                    ),

                onClick = {

                    bleManager
                        .sendCommand(
                            "SCREEN:0"
                        )
                }
            )


            OledButton(
                text =
                    "ACT.",

                enabled =
                    bleManager.isConnected,

                modifier =
                    Modifier.weight(
                        1f
                    ),

                onClick = {

                    bleManager
                        .sendCommand(
                            "SCREEN:1"
                        )
                }
            )


            OledButton(
                text =
                    "RESUMEN",

                enabled =
                    bleManager.isConnected,

                modifier =
                    Modifier.weight(
                        1f
                    ),

                onClick = {

                    bleManager
                        .sendCommand(
                            "SCREEN:2"
                        )
                }
            )
        }


        Spacer(
            modifier =
                Modifier.height(
                    28.dp
                )
        )


        // ====================================================================
        // GESTIÓN DE PACIENTE
        // ====================================================================

        Text(
            text =
                "Gestión de paciente",

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
                        Color(
                            0xFFFFE8EC
                        )
                )
        ) {

            Column(
                modifier =
                    Modifier.padding(
                        18.dp
                    )
            ) {

                Text(
                    text =
                        "Paciente actual",

                    fontSize =
                        12.sp,

                    color =
                        TextSecondary
                )


                Text(
                    text =
                        patientName,

                    fontSize =
                        21.sp,

                    fontWeight =
                        FontWeight.Bold,

                    color =
                        TextPrimary
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            12.dp
                        )
                )


                Text(
                    text =
                        "Al registrar una nueva paciente se eliminarán permanentemente las mediciones y notas del perfil actual.",

                    fontSize =
                        12.sp,

                    color =
                        TextSecondary
                )


                Spacer(
                    modifier =
                        Modifier.height(
                            14.dp
                        )
                )


                Button(
                    modifier =
                        Modifier.fillMaxWidth(),

                    colors =
                        ButtonDefaults.buttonColors(

                            containerColor =
                                ConnectionRed,

                            contentColor =
                                White
                        ),

                    onClick = {

                        showReplaceDialog =
                            true
                    }
                ) {

                    Text(
                        text =
                            "REGISTRAR NUEVA PACIENTE",

                        color =
                            White,

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }


        Spacer(
            modifier =
                Modifier.height(
                    24.dp
                )
        )
    }


    // ========================================================================
    // DIÁLOGO DE CONFIRMACIÓN
    // ========================================================================

    if (
        showReplaceDialog
    ) {

        AlertDialog(

            onDismissRequest = {

                showReplaceDialog =
                    false
            },


            title = {

                Text(
                    text =
                        "¿Reemplazar paciente?"
                )
            },


            text = {

                Text(
                    text =
                        "Esta acción eliminará el perfil actual, todas las mediciones almacenadas y todas las notas. Después volverás al formulario para registrar una nueva paciente."
                )
            },


            confirmButton = {

                TextButton(
                    onClick = {

                        showReplaceDialog =
                            false


                        onReplacePatient()
                    }
                ) {

                    Text(
                        text =
                            "ELIMINAR Y CONTINUAR",

                        color =
                            ConnectionRed,

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            },


            dismissButton = {

                TextButton(
                    onClick = {

                        showReplaceDialog =
                            false
                    }
                ) {

                    Text(
                        text =
                            "Cancelar"
                    )
                }
            }
        )
    }
}


// ============================================================================
// BOTÓN OLED
// ============================================================================

@Composable
fun OledButton(

    text: String,

    enabled: Boolean,

    modifier: Modifier =
        Modifier,

    onClick: () -> Unit
) {

    Button(
        modifier =
            modifier,

        enabled =
            enabled,

        colors =
            ButtonDefaults.buttonColors(

                containerColor =
                    Purple,

                contentColor =
                    White,

                disabledContainerColor =
                    Purple.copy(
                        alpha = 0.30f
                    ),

                disabledContentColor =
                    White.copy(
                        alpha = 0.55f
                    )
            ),

        onClick =
            onClick
    ) {

        Text(
            text =
                text,

            color =
                White,

            fontSize =
                11.sp,

            textAlign =
                TextAlign.Center,

            fontWeight =
                FontWeight.Bold
        )
    }
}