// Shell-side helper. Runs under app_process as uid 2000 (shell); no Android Context of its own.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tether.helper"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    lint {
        abortOnError = false
        error += "NewApi"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
