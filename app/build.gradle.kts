plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bpoppa.pocketai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bpoppa.pocketai"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    flavorDimensions += "engine"
    productFlavors {
        create("termux") {
            dimension = "engine"
            applicationIdSuffix = ".termux"
            versionNameSuffix = "-termux"
        }
        create("embedded") {
            dimension = "engine"
            applicationIdSuffix = ".embedded"
            versionNameSuffix = "-embedded"
        }
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
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
