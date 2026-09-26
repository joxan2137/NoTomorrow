import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    // No `org.jetbrains.kotlin.android`: AGP 9 has built-in Kotlin (research §8.1).
    // No `com.google.gms.google-services`: there is no google-services.json yet.
}

android {
    namespace = "app.notomorrow"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.notomorrow.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // The release workflow stamps these from the release tag and run number
        // (-PappVersionName / -PappVersionCode); local builds keep the defaults.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("en", "pl")
    }

    // Release signing comes from the environment (CI secrets, see .github/workflows/release.yml).
    // Without a keystore the release build type falls back to the debug key so the APK still
    // installs; such builds cannot be upgraded in place by a properly signed one.
    val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { file(it) }
        ?.takeIf { it.isFile }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt",
                "/META-INF/NOTICE.md",
                "/META-INF/*.kotlin_module",
                "/META-INF/INDEX.LIST",
                "/META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Glance's RemoteViews layouts for the widget screenshots (WidgetScreenshots).
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            project.findProperty("widgetShots")?.let { test.systemProperty("widgetShots", file(it.toString()).absolutePath) }
        }
    }

    // The shared AI spec (`backend/data/ai`, contract §1): the app reads `estimate-spec.json` from
    // its assets, the JVM tests read the spec and `fixtures/` from the classpath. Never copied.
    sourceSets {
        getByName("main") {
            assets.srcDir("../../backend/data/ai")
        }
        getByName("test") {
            resources.srcDir("../../backend/data/ai")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room exported schemas — commit android/app/schemas/.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // ---- Compose (BOM-managed) ----
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // ---- AndroidX ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.vm)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.prefs)
    implementation(libs.androidx.work.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.profileinstaller)

    // ---- Room ----
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ---- Health Connect ----
    implementation(libs.androidx.health.connect)

    // ---- Camera + barcode ----
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit)
    implementation(libs.mlkit.barcode)

    // ---- Sign-in ----
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.gms)
    implementation(libs.google.id)

    // ---- Push (no google-services plugin; app must run without Firebase config) ----
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // ---- Ktor ----
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.neg)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    // ---- Kotlin ecosystem ----
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play)
    implementation(libs.tink.android)

    // ---- test ----
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
