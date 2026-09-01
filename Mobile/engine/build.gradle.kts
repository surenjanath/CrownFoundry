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

        // `ArtifactComparisonTest` is a tool rather than a test: it plays two published policies
        // off against each other so a newly trained one can be judged before it is published.
        // It needs the two file paths, and a test task cannot see the build's own properties.
        //
        // Resolved to absolute paths against `Mobile/` rather than passed through, because a test
        // task's working directory is its own module - so a path a human would naturally write
        // from the Gradle root would quietly resolve to nothing and skip the comparison.
        fun artifactPath(name: String): String = providers.gradleProperty(name)
            .map { rootProject.file(it).absolutePath }
            .getOrElse("")

        unitTests.all {
            it.systemProperty("crownfoundry.champion", artifactPath("crownfoundry.champion"))
            it.systemProperty("crownfoundry.candidate", artifactPath("crownfoundry.candidate"))
            it.systemProperty(
                "crownfoundry.compareGames",
                providers.gradleProperty("crownfoundry.compareGames").getOrElse("40")
            )
            // Only while a comparison is actually being run. Every test in this module prints
            // something - `SearchBudgetTest` reports the timings it measured - and turning that
            // on permanently would bury an ordinary test run in numbers nobody asked for.
            it.testLogging {
                showStandardStreams = providers.gradleProperty("crownfoundry.candidate").isPresent
            }
        }
    }
}

dependencies {
    // Exposed: :app builds boards and reads move lists straight off these types.
    api(libs.kotlin.coroutines)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(testLibs.junit)
    testImplementation(testLibs.kotlin.coroutines.test)
}
