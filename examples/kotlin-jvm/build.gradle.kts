plugins {
    kotlin("jvm") version "2.4.21"
    application
}

// Same version as the library build (the repository's VERSION file, else the snapshot).
val tetherVersion = providers.gradleProperty("tetherVersion").orNull
    ?: file("../../VERSION").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    ?: "1.2.0-SNAPSHOT"

dependencies {
    implementation("com.github.amirwmr.tether:tether-core:$tetherVersion")
}

kotlin { jvmToolchain(17) }
application { mainClass.set("HelloTetherKt") }
