// A separate Gradle build that uses the published library, as any JVM project would.
// Publish it locally first: cd ../../android && ./gradlew -Ptether.libraryOnly=true :core:publishToMavenLocal
// TETHER_MAVEN_CENTRAL_MIRROR (optional): a mirror of Maven Central tried first, as the repository's CI does.
val centralMirror: String? = System.getenv("TETHER_MAVEN_CENTRAL_MIRROR")?.takeIf { it.isNotBlank() }
pluginManagement {
    repositories {
        System.getenv("TETHER_MAVEN_CENTRAL_MIRROR")?.takeIf { it.isNotBlank() }?.let { maven(it) }
        gradlePluginPortal(); mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        centralMirror?.let { maven(it) }
        mavenCentral()
        maven("https://jitpack.io") // for a released version: com.github.amirwmr.tether:tether-core:<tag>
    }
}
rootProject.name = "tether-hello-jvm"
