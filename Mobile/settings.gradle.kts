enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
    }

    versionCatalogs {
        create("libs") {
            version("kotlin", "2.1.20")
            plugin("kotlin-serialization","org.jetbrains.kotlin.plugin.serialization").versionRef("kotlin")

            library("kotlin-coroutines","org.jetbrains.kotlinx", "kotlinx-coroutines-core").version("1.9.0")

            // The on-device engine reads its own artifact header and persists offline matches;
            // both are small JSON documents, and neither wants a whole HTTP stack behind it.
            library("kotlinx-serialization-json", "org.jetbrains.kotlinx", "kotlinx-serialization-json")
                .version("1.7.3")

            version("compose", "1.7.8")
            library("compose-foundation", "androidx.compose.foundation", "foundation").versionRef("compose")
            library("compose-ui", "androidx.compose.ui", "ui").versionRef("compose")
            library("compose-ui-util", "androidx.compose.ui", "ui-util").versionRef("compose")
            library("compose-ripple", "androidx.compose.material", "material-ripple").versionRef("compose")

            library("compose-shimmer", "com.valentinilk.shimmer", "compose-shimmer").version("1.3.2")

            library("compose-activity", "androidx.activity", "activity-compose").version("1.9.3")

            version("ktor", "2.3.13")
            library("ktor-client-core", "io.ktor", "ktor-client-core").versionRef("ktor")
            library("ktor-client-cio", "io.ktor", "ktor-client-okhttp").versionRef("ktor")
            library("ktor-client-content-negotiation", "io.ktor", "ktor-client-content-negotiation").versionRef("ktor")
            library("ktor-client-encoding", "io.ktor", "ktor-client-encoding").versionRef("ktor")
            library("ktor-client-serialization", "io.ktor", "ktor-client-serialization").versionRef("ktor")
            library("ktor-serialization-json", "io.ktor", "ktor-serialization-kotlinx-json").versionRef("ktor")

            library("desugaring", "com.android.tools", "desugar_jdk_libs").version("2.1.5")
        }

        create("testLibs") {
            library("junit", "junit", "junit").version("4.13.2")

            library("kotlin-coroutines-test", "org.jetbrains.kotlinx", "kotlinx-coroutines-test")
                .version("1.9.0")
            library("ktor-client-mock", "io.ktor", "ktor-client-mock").version("2.3.13")

            version("androidx-test", "1.6.1")
            library("androidx-test-core", "androidx.test", "core").versionRef("androidx-test")
            library("androidx-test-runner", "androidx.test", "runner").versionRef("androidx-test")
            library("androidx-test-junit", "androidx.test.ext", "junit").version("1.2.1")
            library("robolectric", "org.robolectric", "robolectric").version("4.14.1")
            library("compose-ui-test-junit4", "androidx.compose.ui", "ui-test-junit4")
                .version("1.7.8")
            library("compose-ui-test-manifest", "androidx.compose.ui", "ui-test-manifest")
                .version("1.7.8")
        }
    }
}

rootProject.name = "CrownFoundry"

include(":app")
include(":api")
include(":engine")
include(":compose-routing")
include(":compose-persist")
