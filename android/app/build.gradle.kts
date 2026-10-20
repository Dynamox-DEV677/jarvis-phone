import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (GitHub Actions secrets) or from
// ~/.jarvis-phone/signing.properties on the developer's PC. The keystore is never in the repo.
val signing = Properties().apply {
    val f = File(System.getProperty("user.home"), ".jarvis-phone/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? = System.getenv(name) ?: signing.getProperty(name)

android {
    namespace = "dev.kairo.jarvis"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kairo.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            val store = secret("JARVIS_KEYSTORE")
            if (store != null && File(store).exists()) {
                storeFile = File(store)
                storePassword = secret("JARVIS_KEYSTORE_PASSWORD")
                keyAlias = secret("JARVIS_KEY_ALIAS") ?: "jarvis"
                keyPassword = secret("JARVIS_KEY_PASSWORD") ?: secret("JARVIS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            if (rel.storeFile != null) signingConfig = rel
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    // Offline speech recognition, used only to hear the wake word "Jarvis".
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
