plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The GitHub build passes -PversionCode=<run number> so every new APK installs over the last one.
val vCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val vName = (project.findProperty("versionName") as String?) ?: "0.1.$vCode"

android {
    namespace = "app.couchphone"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.couchphone"
        minSdk = 29          // Android 10: per-display input and launching on the DeX display
        targetSdk = 34
        versionCode = vCode
        versionName = vName
    }
    buildFeatures { buildConfig = true }

    // One release key for every build, so updates install over the old version.
    // The key file is in signing/ (password protected); the password comes from the
    // SIGNING_PASSWORD secret in the GitHub build. Without it, the debug key is used.
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
            isMinifyEnabled = false   // the root helper class is found by name, keep everything
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Sideloaded app: lint's Play Store checks would only stop the build.
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging {
        resources { excludes += setOf("META-INF/*.kotlin_module") }
        // The mouse helper (libcouchmouse.so) is run as a program, so it must be a real file on the phone.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    // HTTP + WebSocket server (BSD-3-Clause).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    // Lets the Bluetooth panel use Android's hidden unpair and connect calls (Apache-2.0).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}

// The phone remote (remote/dist) and the TV home page (tv-home/web) live elsewhere in the repo.
// Copy them into the app before every build.
val repoRoot = file("../..")
val remoteDist = File(repoRoot, "remote/dist")
val tvHomeWeb = File(repoRoot, "tv-home/web")

val copyWeb by tasks.registering(Copy::class) {
    description = "Copies the phone remote, the TV home page and the app's own web files into assets."
    into(layout.projectDirectory.dir("src/main/assets/www"))
    from(remoteDist) { into("remote") }
    from(tvHomeWeb) { into("tv"); exclude("mock.js") }
    from(file("../web")) { into("ontv") }
    doFirst {
        if (!File(remoteDist, "index.html").isFile) throw GradleException("No phone remote build at $remoteDist. Run npm run build in remote/ first.")
        if (!File(tvHomeWeb, "index.html").isFile) throw GradleException("No TV home page at $tvHomeWeb.")
        delete(layout.projectDirectory.dir("src/main/assets/www"))
    }
}
tasks.named("preBuild") { dependsOn(copyWeb) }
