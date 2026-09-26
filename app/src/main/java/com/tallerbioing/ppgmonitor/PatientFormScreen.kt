package com.tallerbioing.ppgmonitor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


// ============================================================================
// FORMULARIO DE LA PACIENTE
// ============================================================================

@Composable
fun PatientFormScreen(

    nombre: String,

    edad: String,

    patologia: String,

    medicacion: String,

    peso: String,

    altura: String,

    onNombreChange: (String) -> Unit,

    onEdadChange: (String) -> Unit,

    onPatologiaChange: (String) -> Unit,

    onMedicacionChange: (String) -> Unit,

    onPesoChange: (String) -> Unit,

    onAlturaChange: (String) -> Unit,

    onContinue: () -> Unit
) {

    val canContinue =

        nombre.isNotBlank() &&
                edad.isNotBlank() &&
                peso.isNotBlank() &&
                altura.isNotBlank()


    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(
                horizontal = 22.dp,
                vertical = 24.dp
            )
    ) {

        Text(
            text = "PPG Monitor",

            fontSize = 32.sp,

            fontWeight =
                FontWeight.Bold,

            color =
                PurpleDark
        )


        Spacer(
            modifier =
                Modifier.height(
                    3.dp
                )
        )


        Text(
            text =
                "Monitor portátil de frecuencia cardíaca y actividad",

            fontSize =
                15.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(
                    24.dp
                )
        )


        Card(
            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    28.dp
                ),

            colors =
                CardDefaults.cardColors(
                    containerColor =
                        White
                ),

            elevation =
                CardDefaults.cardElevation(
                    defaultElevation =
                        6.dp
                )
        ) {

            Column(
                modifier =
                    Modifier.padding(
                        horizontal = 22.dp,
                        vertical = 22.dp
                    )
            ) {

                Text(
                    text =
                        "Datos de la paciente",

                    fontSize =
                        24.sp,

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
                        "Complete los datos para comenzar el seguimiento.",

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


                PatientField(

                    value =
                        nombre,

                    onValueChange =
                        onNombreChange,

                    label =
                        "Nombre"
                )


                PatientField(

                    value =
                        edad,

                    onValueChange =
                        onEdadChange,

                    label =
                        "Edad",

                    keyboardType =
                        KeyboardType.Number
                )


                PatientField(

                    value =
                        patologia,

                    onValueChange =
                        onPatologiaChange,

                    label =
                        "Patología (si posee)"
                )


                PatientField(

                    value =
                        medicacion,

                    onValueChange =
                        onMedicacionChange,

                    label =
                        "Medicación (si toma)"
                )


                PatientField(

                    value =
                        peso,

                    onValueChange =
                        onPesoChange,

                    label =
                        "Peso (kg)",

                    keyboardType =
                        KeyboardType.Decimal
                )


                PatientField(

                    value =
                        altura,

                    onValueChange =
                        onAlturaChange,

                    label =
                        "Altura (cm)",

                    keyboardType =
                        KeyboardType.Decimal
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
                                58.dp
                            ),

                    enabled =
                        canContinue,

                    shape =
                        RoundedCornerShape(
                            18.dp
                        ),

                    colors =
                        ButtonDefaults
                            .buttonColors(

                                containerColor =
                                    Purple,

                                contentColor =
                                    White,

                                disabledContainerColor =
                                    Purple.copy(
                                        alpha = 0.35f
                                    ),

                                disabledContentColor =
                                    Color.White.copy(
                                        alpha = 0.65f
                                    )
                            ),

                    onClick =
                        onContinue
                ) {

                    Text(
                        text =
                            "GUARDAR Y CONTINUAR",

                        fontSize =
                            14.sp,

                        fontWeight =
                            FontWeight.Bold
                    )
                }
            }
        }


        Spacer(
            modifier =
                Modifier.height(
                    22.dp
                )
        )


        Text(
            text =
                "Los datos ingresados se utilizan únicamente dentro de esta aplicación.",

            modifier =
                Modifier.padding(
                    horizontal = 12.dp
                ),

            fontSize =
                12.sp,

            color =
                TextSecondary
        )


        Spacer(
            modifier =
                Modifier.height(
                    30.dp
                )
        )
    }
}


// ============================================================================
// CAMPO DEL FORMULARIO
// ============================================================================

@Composable
fun PatientField(

    value: String,

    onValueChange: (String) -> Unit,

    label: String,

    keyboardType: KeyboardType =
        KeyboardType.Text
) {

    OutlinedTextField(

        value =
            value,

        onValueChange =
            onValueChange,

        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    bottom = 12.dp
                ),

        label = {

            Text(
                text = label
            )
        },

        singleLine =
            true,

        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    keyboardType
            ),

        textStyle =
            LocalTextStyle.current.copy(

                color =
                    Color.Black,

                fontSize =
                    16.sp
            ),

        shape =
            RoundedCornerShape(
                16.dp
            ),

        colors =
            OutlinedTextFieldDefaults
                .colors(

                    // ========================================================
                    // TEXTO ESCRITO
                    // ========================================================

                    focusedTextColor =
                        Color.Black,

                    unfocusedTextColor =
                        Color.Black,

                    disabledTextColor =
                        Color.Black,


                    // ========================================================
                    // ETIQUETA
                    // ========================================================

                    focusedLabelColor =
                        Purple,

                    unfocusedLabelColor =
                        Color(
                            0xFF55515A
                        ),


                    // ========================================================
                    // CURSOR
                    // ========================================================

                    cursorColor =
                        Purple,


                    // ========================================================
                    // BORDES
                    // ========================================================

                    focusedBorderColor =
                        Purple,

                    unfocusedBorderColor =
                        Color(
                            0xFF77727B
                        ),


                    // ========================================================
                    // FONDO
                    // ========================================================

                    focusedContainerColor =
                        White,

                    unfocusedContainerColor =
                        White
                )
    )
}