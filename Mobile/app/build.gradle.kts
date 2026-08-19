import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Offline matches are persisted as JSON in the app's private directory.
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Release signing is read from keystore.properties, which is never committed. Without that file
 * - on a fresh clone, or in CI - the release build falls back to the debug key, so everything
 * still builds; it just is not publishable.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "com.surenjanath.crownfoundry"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 21
        // Google Play requires new apps and updates to target API 35 today, and API 36 from
        // 31 Aug 2026. Targeting 36 satisfies both.
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
    }

    namespace = "com.surenjanath.crownfoundry"

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "CrownFoundry Debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appName"] = "CrownFoundry"
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets.all {
        kotlin.srcDir("src/$name/kotlin")
    }

    buildFeatures {
        compose = true
        // AGP 8 no longer generates BuildConfig unless asked; the about screen reads it.
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        freeCompilerArgs += "-Xcontext-receivers"
        jvmTarget = "17"
    }

    testOptions {
        // Robolectric needs the merged resources to inflate the theme.
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(projects.composePersist)
    implementation(projects.composeRouting)
    implementation(projects.api)
    implementation(projects.engine)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.compose.activity)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ripple)
    implementation(libs.compose.shimmer)

    coreLibraryDesugaring(libs.desugaring)

    testImplementation(testLibs.junit)
    testImplementation(testLibs.robolectric)
    testImplementation(testLibs.androidx.test.core)
    testImplementation(testLibs.kotlin.coroutines.test)

    androidTestImplementation(testLibs.junit)
    androidTestImplementation(testLibs.androidx.test.runner)
    androidTestImplementation(testLibs.androidx.test.junit)
    androidTestImplementation(testLibs.compose.ui.test.junit4)
    debugImplementation(testLibs.compose.ui.test.manifest)
}
