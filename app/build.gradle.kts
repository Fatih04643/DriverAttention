plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.driverattention"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.driverattention"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // .task model dosyası APK içinde sıkıştırılmasın (MediaPipe doğrudan okuyabilsin)
    androidResources {
        noCompress += "task"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Jetpack Compose (Material 3) — sürümler BOM üzerinden yönetilir
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // CameraX: kamera erişimi + önizleme görünümü
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ViewModel'i Compose içinden almak için
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // MediaPipe Tasks Vision: Face Landmarker (yüzde 478 nokta)
    implementation(libs.mediapipe.tasks.vision)

    // Room: sürüş oturumları ve uyarı kayıtları (SQLite üzerinde)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Ekranlar arası geçiş, ayarların kalıcı saklanması, alt menü ikonları
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.material.icons.core)

    // GPS hızı (Faz 8): Google Play Services birleşik konum sağlayıcısı
    implementation(libs.play.services.location)

    // analysis/ paketindeki saf Kotlin sınıfları için birim testleri
    testImplementation(libs.junit)
}
