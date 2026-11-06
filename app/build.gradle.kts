import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Загружаем секреты локально из local.properties (файл не отслеживается гитом)
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        load(file.inputStream())
    }
}

android {
    namespace = "com.antigulag.vpn"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.antigulag.vpn"
        minSdk = 24
        targetSdk = 37
        versionCode = 5
        versionName = "2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val keyStoreFile = file("release-key.jks")
            if (keyStoreFile.exists()) {
                storeFile = keyStoreFile
                storePassword = localProps.getProperty("KEYSTORE_PASS", "")
                keyAlias = localProps.getProperty("KEY_ALIAS", "")
                keyPassword = localProps.getProperty("KEY_PASS", "")
            }
        }
    }

    buildTypes {
        debug {
            // Чтобы дебаг через кнопку Run ставился поверх релизных APK
            val releaseSigning = signingConfigs.getByName("release")
            if (file("release-key.jks").exists()) {
                signingConfig = releaseSigning
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Базовые компоненты AndroidX и UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // QR-сканер и ядро V2Ray
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(files("libs/libv2ray.aar"))

    // Тесты
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}