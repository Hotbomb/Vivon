plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Couch Remote: the phone remote as an Android app. Built with Couch Phone (the same version
// number), kept inside Couch Phone's APK and served by it, so a TV hands out a matching remote.
val vCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val vName = (project.findProperty("versionName") as String?) ?: "0.1.$vCode"

android {
    namespace = "app.couchremote"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.couchremote"
        minSdk = 26
        targetSdk = 34
        versionCode = vCode
        versionName = vName
    }
    buildFeatures { buildConfig = true }

    // The same release key as Couch Phone (see app/build.gradle.kts), so updates install over it.
    val keyFile = System.getenv("COUCHPHONE_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    val keyPass = System.getenv("COUCHPHONE_KEY_PASSWORD")
    signingConfigs {
        if (keyFile != null && !keyPass.isNullOrEmpty()) create("release") {
            storeFile = keyFile
            storeType = "pkcs12"
            storePassword = keyPass
            keyAlias = "couch-phone"
            keyPassword = keyPass
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    // Scanning the TV's QR code with the camera (Apache-2.0).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
