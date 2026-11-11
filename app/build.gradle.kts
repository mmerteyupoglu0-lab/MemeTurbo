import java.io.FileOutputStream
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.memeturbo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.memeturbo.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "2.1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        noCompress += listOf("zip", "mdl", "fst", "conf", "stats", "dubm", "ie", "mat", "int")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// GitHub web yükleyicisi .github klasörünü atlasa bile Gradle derleme sırasında
// Türkçe Vosk ses modelini doğrudan indirip APK'nın içine (assets/vosk_tr.zip) gömer!
val downloadVoskModel by tasks.registering {
    doLast {
        val assetsDir = file("src/main/assets")
        if (!assetsDir.exists()) assetsDir.mkdirs()
        val zipFile = file("src/main/assets/vosk_tr.zip")
        if (!zipFile.exists() || zipFile.length() < 10_000_000L) {
            println("Türkçe Vosk Ses Modeli indiriliyor (vosk-model-small-tr-0.3.zip)...")
            val url = URI("https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip").toURL()
            url.openStream().use { input ->
                FileOutputStream(zipFile).use { output ->
                    input.copyTo(output)
                }
            }
            println("Vosk Türkçe modeli başarıyla indirildi: ${zipFile.length()} byte")
        }
    }
}

tasks.matching { it.name == "preBuild" || it.name.contains("Assets") }.configureEach {
    dependsOn(downloadVoskModel)
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Ekran Yazısı (OCR) ve Sadece Hayvan/Nesne/Sahne için Türkçe Görsel Etiketleme
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")

    // Türkçe Çevrimdışı Ses Tanıma (Vosk Kaldi)
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
