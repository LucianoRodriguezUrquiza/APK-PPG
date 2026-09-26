plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)

    // Procesador de código utilizado por Room
    alias(libs.plugins.ksp)
}


android {

    namespace = "com.tallerbioing.ppgmonitor"

    compileSdk {
        version = release(37)
    }


    defaultConfig {

        applicationId = "com.tallerbioing.ppgmonitor"

        minSdk = 26

        targetSdk = 37

        versionCode = 1

        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {

        release {

            optimization {
                enable = false
            }
        }
    }


    compileOptions {

        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11
    }


    buildFeatures {

        compose = true
    }
}


dependencies {

    // ========================================================================
    // JETPACK COMPOSE
    // ========================================================================

    implementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    implementation(
        libs.androidx.activity.compose
    )

    implementation(
        libs.androidx.compose.material3
    )

    implementation(
        libs.androidx.compose.ui
    )

    implementation(
        libs.androidx.compose.ui.graphics
    )

    implementation(
        libs.androidx.compose.ui.tooling.preview
    )


    // ========================================================================
    // ANDROIDX
    // ========================================================================

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )


    // ========================================================================
    // ROOM
    // ========================================================================

    // Base de datos Room
    implementation(
        libs.androidx.room.runtime
    )

    // Extensiones Kotlin / coroutines para Room
    implementation(
        libs.androidx.room.ktx
    )

    // Generador de código de Room mediante KSP
    ksp(
        libs.androidx.room.compiler
    )


    // ========================================================================
    // TESTS
    // ========================================================================

    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )


    // ========================================================================
    // DEBUG
    // ========================================================================

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}