// The Tether protocol library ("tether-core"): framing, handshake, sealed records, message types, G.711 and Opus.
// Pure Kotlin/JVM (JVM 17), so it serves the Android phone's app (:app), the Android client (:client) and any
// desktop, server or Linux program. Android-only extras live in :core-android. Published as
// com.github.amirwmr.tether:tether-core (docs/SDK-kotlin.md).
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
    id("org.jetbrains.dokka")
}

group = "com.github.amirwmr.tether"
// The release version comes from the repository's VERSION file; without one this is a snapshot.
version = rootProject.file("../VERSION").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    ?: "1.2.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

sourceSets["test"].resources.srcDir("../../protocol") // fixtures/ and PROTOCOL.md for the contract tests

dependencies {
    api("io.github.jaredmdobson:concentus:1.0.2") // pure-Java libopus port: synchronous, full encoder control
    // Message bodies are org.json objects. Android ships org.json in the platform, so the Android apps exclude
    // this dependency (group org.json) to avoid duplicate platform classes; a JVM program gets it from here.
    api("org.json:json:20260814")
    testImplementation("junit:junit:4.13.2")
}

// The Android modules' test task name, so tools/test-all.sh and CI keep calling :core:testDebugUnitTest.
tasks.register("testDebugUnitTest") { dependsOn(tasks.named("test")) }

dokka {
    moduleName.set("tether-core")
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
        sourceLink {
            localDirectory.set(file("src/main/java"))
            remoteUrl("https://github.com/amirwmr/tether/tree/main/android/core/src/main/java")
            remoteLineSuffix.set("#L")
        }
    }
}

val javadocJar by tasks.registering(Jar::class) {
    description = "API documentation (Dokka HTML) as the javadoc jar Maven repositories expect."
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "tether-core"
            from(components["java"])
            artifact(javadocJar)
            pom {
                name.set("Tether core")
                description.set("Tether protocol for Kotlin and Java: pairing codes, handshake, sealed records, control messages, audio frames, G.711 and Opus.")
                url.set("https://github.com/amirwmr/tether")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                scm { url.set("https://github.com/amirwmr/tether"); connection.set("scm:git:https://github.com/amirwmr/tether.git") }
                developers { developer { name.set("The Tether authors"); url.set("https://github.com/amirwmr/tether") } }
            }
        }
    }
}
