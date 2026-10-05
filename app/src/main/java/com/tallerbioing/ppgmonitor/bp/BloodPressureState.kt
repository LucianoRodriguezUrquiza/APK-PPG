package com.tallerbioing.ppgmonitor.bp

enum class BloodPressureStatus(
    val displayName: String
) {
    SENSANDO("Sensando"),
    TRANSFIRIENDO("Transfiriendo"),
    CALCULANDO("Calculando"),
    DISPONIBLE("Disponible"),
    RECHAZADA("Rechazada")
}

data class BloodPressureUiState(
    val status: BloodPressureStatus =
        BloodPressureStatus.SENSANDO,
    val systolicMmHg: Float? = null,
    val diastolicMmHg: Float? = null,
    val windowSeq: Long? = null,
    val progressSamples: Int = 0,
    val message: String? = null
)
