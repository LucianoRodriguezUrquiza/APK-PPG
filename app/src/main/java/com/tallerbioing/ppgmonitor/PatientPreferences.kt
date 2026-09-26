package com.tallerbioing.ppgmonitor

import android.content.Context


// ============================================================================
// PERFIL DE PACIENTE
// ============================================================================

data class PatientProfile(

    val nombre: String,

    val edad: String,

    val patologia: String,

    val medicacion: String,

    val peso: String,

    val altura: String
)


// ============================================================================
// ALMACENAMIENTO PERSISTENTE DEL PERFIL
// ============================================================================
//
// Se utiliza SharedPreferences porque la aplicación maneja un único perfil
// activo por instalación.
//
// Las mediciones y notas continúan almacenándose mediante Room.
//
// ============================================================================

object PatientPreferences {

    private const val PREFS_NAME =
        "ppg_patient_profile"


    private const val KEY_REGISTERED =
        "registered"


    private const val KEY_NAME =
        "name"


    private const val KEY_AGE =
        "age"


    private const val KEY_PATHOLOGY =
        "pathology"


    private const val KEY_MEDICATION =
        "medication"


    private const val KEY_WEIGHT =
        "weight"


    private const val KEY_HEIGHT =
        "height"


    // ========================================================================
    // GUARDAR PERFIL
    // ========================================================================

    fun save(
        context: Context,
        profile: PatientProfile
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                KEY_REGISTERED,
                true
            )
            .putString(
                KEY_NAME,
                profile.nombre
            )
            .putString(
                KEY_AGE,
                profile.edad
            )
            .putString(
                KEY_PATHOLOGY,
                profile.patologia
            )
            .putString(
                KEY_MEDICATION,
                profile.medicacion
            )
            .putString(
                KEY_WEIGHT,
                profile.peso
            )
            .putString(
                KEY_HEIGHT,
                profile.altura
            )
            .apply()
    }


    // ========================================================================
    // CARGAR PERFIL
    // ========================================================================

    fun load(
        context: Context
    ): PatientProfile? {

        val preferences =
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )


        val registered =
            preferences.getBoolean(
                KEY_REGISTERED,
                false
            )


        if (!registered) {

            return null
        }


        return PatientProfile(

            nombre =
                preferences.getString(
                    KEY_NAME,
                    ""
                ) ?: "",

            edad =
                preferences.getString(
                    KEY_AGE,
                    ""
                ) ?: "",

            patologia =
                preferences.getString(
                    KEY_PATHOLOGY,
                    ""
                ) ?: "",

            medicacion =
                preferences.getString(
                    KEY_MEDICATION,
                    ""
                ) ?: "",

            peso =
                preferences.getString(
                    KEY_WEIGHT,
                    ""
                ) ?: "",

            altura =
                preferences.getString(
                    KEY_HEIGHT,
                    ""
                ) ?: ""
        )
    }


    // ========================================================================
    // ELIMINAR PERFIL
    // ========================================================================

    fun clear(
        context: Context
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .clear()
            .apply()
    }
}