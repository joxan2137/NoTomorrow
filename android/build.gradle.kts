buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Literal coordinates: type-safe catalog accessors (`libs.…`) do not resolve
        // inside `buildscript {}` (research §8.1). These raise AGP 9's built-in
        // Kotlin (2.2.10) / KSP (2.2.10-2.0.2) to the versions the port targets.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.11")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    // google-services is intentionally NOT applied: there is no google-services.json
    // yet and the app must build and run without Firebase configuration.
}
