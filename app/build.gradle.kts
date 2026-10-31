import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- version: CI passes the tag (vX.Y.Z) as -PtivibroVersion=X.Y.Z or TIVIBRO_VERSION; local builds keep the defaults
val tivibroVersion: String? = ((findProperty("tivibroVersion") as String?) ?: System.getenv("TIVIBRO_VERSION"))
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }

/** X.Y.Z -> X*10000 + Y*100 + Z. */
fun versionCodeOf(v: String): Int {
    val p = v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    return p[0] * 10000 + p[1] * 100 + p[2]
}

// ---- release signing: ~/.config/tivibro/keystore.properties, else the TIVIBRO_* env vars (CI), else the debug key
data class Signing(val store: File, val storePassword: String, val alias: String, val keyPassword: String)

val releaseSigning: Signing? = run {
    val local = File(System.getProperty("user.home"), ".config/tivibro/keystore.properties")
    val b64 = System.getenv("TIVIBRO_KEYSTORE_BASE64")?.takeIf { it.isNotBlank() }
    when {
        local.isFile -> {
            val p = Properties().apply { local.inputStream().use { load(it) } }
            Signing(File(p.getProperty("storeFile")), p.getProperty("storePassword"), p.getProperty("keyAlias"), p.getProperty("keyPassword"))
        }
        b64 != null -> {
            val f = layout.buildDirectory.file("signing/release.jks").get().asFile
            f.parentFile.mkdirs()
            f.writeBytes(Base64.getMimeDecoder().decode(b64))
            Signing(f, System.getenv("TIVIBRO_KEYSTORE_PASSWORD"), System.getenv("TIVIBRO_KEY_ALIAS"), System.getenv("TIVIBRO_KEY_PASSWORD"))
        }
        else -> null
    }
}

// ---- TMDB (posters and descriptions on Home): -PtmdbToken, else TIVIBRO_TMDB_TOKEN (CI), else
//      ~/.config/tivibro/tmdb_token; none: built without one (the user can still add their own in Settings)
val tmdbToken: String = ((findProperty("tmdbToken") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("TIVIBRO_TMDB_TOKEN")?.takeIf { it.isNotBlank() }
    ?: File(System.getProperty("user.home"), ".config/tivibro/tmdb_token").takeIf { it.isFile }?.readText())
    ?.trim()?.filter { it.isLetterOrDigit() || it in "._-" }.orEmpty()

android {
    namespace = "dev.romirom.tivibro"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.romirom.tivibro"
        minSdk = 23
        targetSdk = 35
        versionCode = tivibroVersion?.let(::versionCodeOf) ?: 1
        versionName = tivibroVersion ?: "0.1"
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
    }

    signingConfigs {
        releaseSigning?.let { s ->
            create("release") {
                storeFile = s.store
                storePassword = s.storePassword
                keyAlias = s.alias
                keyPassword = s.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseSigning != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val media3 = "1.5.0" // matches the prebuilt FFmpeg decoder below
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // MP2 audio (most TeamPlay channels) has no platform decoder; the original app bundles FFmpeg for the same reason
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // the real org.json: android.jar only has stubs
}
