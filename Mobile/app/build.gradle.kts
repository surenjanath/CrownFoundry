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

/**
 * Where a fresh install looks for the referee, baked in at build time.
 *
 * The emulator's `10.0.2.2` is the right default to develop against and the wrong thing to
 * publish: on a real phone it is an address that does not answer, so every network call fails and
 * the app spends its first run retrying nothing. A release build therefore has to say where the
 * referee actually is, via `crownfoundry.backendUrl` in `gradle.properties` or `-P` on the
 * command line, and the build stops if it does not.
 *
 * A release that is deliberately offline-only is spelled `crownfoundry.backendUrl=none`, which
 * leaves the bundled engine as the whole product and never reaches for a network.
 */
val configuredBackendUrl: String? = (project.findProperty("crownfoundry.backendUrl") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

val emulatorBackendUrl = "http://10.0.2.2:8000"

fun backendUrlFor(buildType: String): String =
    configuredBackendUrl ?: emulatorBackendUrl

/**
 * The check runs against the task graph rather than at configuration time, because the release
 * build type is configured even when only `testDebugUnitTest` was asked for - throwing there
 * would make an unset property break every debug build and every test run.
 */
gradle.taskGraph.whenReady {
    val packagingRelease = allTasks.any { task ->
        task.project == project &&
            task.name.contains("Release") &&
            listOf("assemble", "bundle", "package").any { task.name.startsWith(it) }
    }
    if (!packagingRelease) return@whenReady

    val configured = configuredBackendUrl ?: throw GradleException(
        """
        A release build needs a backend URL. Set it in Mobile/gradle.properties:

            crownfoundry.backendUrl=https://your-host.example.com

        or pass -Pcrownfoundry.backendUrl=... on the command line. Use the value `none` to
        publish an offline-only build that never contacts a server.

        Without this the published app would point at $emulatorBackendUrl, which is the
        emulator's route to a developer's laptop and unreachable from a real device.
        """.trimIndent()
    )

    if (configured != "none" && !configured.startsWith("https://")) {
        throw GradleException(
            "crownfoundry.backendUrl must be https:// for a release build (got '$configured'). " +
                "Release builds refuse cleartext to anything but loopback; see " +
                "app/src/main/res/xml/network_security_config.xml."
        )
    }
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
        versionCode = 5
        versionName = "1.4.0"
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
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"${backendUrlFor("debug")}\"")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appName"] = "CrownFoundry"
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"${backendUrlFor("release")}\"")

            // Play warns that this bundle carries native code with no debug symbols. The only .so
            // in it is androidx.graphics.path, pulled in transitively by Compose, and AndroidX
            // ships it stripped: it has a .dynsym and nothing else. Both `debugSymbolLevel`
            // settings were tried and each extracted zero files, so the warning cannot be answered
            // from here - it is left standing deliberately. What makes this app's own crashes
            // readable is the R8 mapping file, which is in every bundle already.
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
