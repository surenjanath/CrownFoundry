buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    dependencies {
        classpath("com.android.tools.build", "gradle", "8.9.1")
        classpath(kotlin("gradle-plugin", libs.versions.kotlin.get()))
        // Since Kotlin 2.0 the Compose compiler ships with Kotlin itself and is applied as a
        // plugin, replacing the old composeOptions.kotlinCompilerExtensionVersion pinning.
        classpath("org.jetbrains.kotlin", "compose-compiler-gradle-plugin", libs.versions.kotlin.get())
        // :api, :engine and :app all serialise JSON with kotlinx.serialization.
        classpath(kotlin("serialization", libs.versions.kotlin.get()))
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            if (project.findProperty("enableComposeCompilerReports") == "true") {
                val destination = project.layout.buildDirectory.dir("compose_metrics").get().asFile.absolutePath
                arrayOf("reports", "metrics").forEach {
                    freeCompilerArgs.addAll(
                        "-P", "plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=$destination"
                    )
                }
            }
        }
    }
}
