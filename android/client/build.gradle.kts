import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release key from local.properties (git-ignored); without one, release builds use the debug key.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// One version for every app: the VERSION file at the repository root (tools/release.sh writes it). versionCode grows
// with every release: major*1000000 + minor*10000 + patch*100 + 99, a pre-release (1.2.0-rc.3) takes its number
// (03) in place of 99 so it sorts before the release.
val tetherVersion = rootProject.file("../VERSION").readText().trim()
val tetherVersionCode = Regex("""(\d+)\.(\d+)\.(\d+)(?:-[A-Za-z]+\.?(\d+)?)?""").matchEntire(tetherVersion)
    ?.destructured?.let { (ma, mi, pa, pre) ->
        ma.toInt() * 1_000_000 + mi.toInt() * 10_000 + pa.toInt() * 100 +
            (if (tetherVersion.contains('-')) (pre.toIntOrNull() ?: 0).coerceAtMost(98) else 99)
    } ?: error("VERSION must look like 1.2.3 or 1.2.3-rc.1, not '$tetherVersion'")

// The Android client: a second Android phone that does what the iPhone app does (calls, texts, contacts, recents,
// switches) with the Android phone that holds the SIMs. Talks protocol/PROTOCOL.md as a BLE central.
android {
    namespace = "app.tether.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tether.client"
        minSdk = 29          // L2CAP connection-oriented channels (BluetoothDevice.createInsecureL2capChannel)
        targetSdk = 36
        versionCode = tetherVersionCode
        versionName = tetherVersion
    }

    signingConfigs {
        localProps.getProperty("tether.signing.storeFile")?.let { store ->
            create("own") {
                storeFile = file(store)
                storePassword = localProps.getProperty("tether.signing.storePassword")
                keyAlias = localProps.getProperty("tether.signing.keyAlias")
                keyPassword = localProps.getProperty("tether.signing.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("own") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false }
    sourceSets["test"].resources.srcDir("../../protocol")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core")) { exclude(group = "org.json") }
    implementation("com.google.zxing:core:3.5.4")   // reading the pairing QR code
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
