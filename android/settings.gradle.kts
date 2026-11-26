// Optional mirror for Google's Maven repository, for networks that cannot reach dl.google.com: set
// tether.googleMavenMirror=<url> in local.properties (git-ignored) or TETHER_GOOGLE_MAVEN_MIRROR in the environment.
val googleMirror: String? = run {
    val f = file("local.properties")
    val p = java.util.Properties().apply { if (f.exists()) f.inputStream().use { load(it) } }
    (p.getProperty("tether.googleMavenMirror") ?: System.getenv("TETHER_GOOGLE_MAVEN_MIRROR"))?.takeIf { it.isNotBlank() }
}

pluginManagement {
    repositories {
        val f = file("local.properties")
        val p = java.util.Properties().apply { if (f.exists()) f.inputStream().use { load(it) } }
        (p.getProperty("tether.googleMavenMirror") ?: System.getenv("TETHER_GOOGLE_MAVEN_MIRROR"))?.takeIf { it.isNotBlank() }?.let { maven(it) }
        // Optional mirror of Maven Central, tried first (CI uses Google's: Maven Central rate-limits shared runners).
        System.getenv("TETHER_MAVEN_CENTRAL_MIRROR")?.takeIf { it.isNotBlank() }?.let { maven(it) }
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        googleMirror?.let { maven(it) }
        System.getenv("TETHER_MAVEN_CENTRAL_MIRROR")?.takeIf { it.isNotBlank() }?.let { maven(it) }
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") }
        }
    }
}
rootProject.name = "Tether"
// -Ptether.libraryOnly=true (or TETHER_LIBRARY_ONLY=true) configures only the JVM library :core, so it builds and
// publishes without the Android SDK (JitPack, the "Kotlin library (JVM)" CI job, desktop developers).
val libraryOnly = (providers.gradleProperty("tether.libraryOnly").orNull ?: System.getenv("TETHER_LIBRARY_ONLY")) == "true"
if (libraryOnly) include(":core") else include(":app", ":helper", ":core", ":core-android", ":client")
