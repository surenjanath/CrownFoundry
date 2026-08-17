plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.surenjanath.crownfoundry.engine"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
    }

    sourceSets.all {
        kotlin.srcDir("src/$name/kotlin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Exposed: :app builds boards and reads move lists straight off these types.
    api(libs.kotlin.coroutines)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(testLibs.junit)
    testImplementation(testLibs.kotlin.coroutines.test)
}
