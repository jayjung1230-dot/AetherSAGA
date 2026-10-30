plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("base")
}

base {
    archivesName.set("AetherSAGA")
}

android {
    namespace = "com.aether.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aether.reader"
        minSdk = 26          // Luna X is API 30; 26 keeps older LCD tablets
        targetSdk = 30       // Scoped Storage semantics + MANAGE_EXTERNAL_STORAGE on the primary device
        versionCode = 2
        versionName = "1.1"
        resourceConfigurations += listOf("en", "ko")
    }

    signingConfigs {
        // Debug keystore signing keeps the release APK installable without a private key; replace for distribution.
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // targetSdk 30 is deliberate (sideloaded on Android 11 e-reader); Play Store target check does not apply.
    lint { disable += "ExpiredTargetSdkVersion"; abortOnError = false }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += listOf("META-INF/*.version", "DebugProbesKt.bin", "kotlin/**", "**/*.kotlin_*") }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    // Deliberately NOT used: AppCompat, Hilt/Dagger, Glide/Coil, Retrofit/OkHttp, Compose.
    // Framework Views + HttpURLConnection/ServerSocket keep the APK small and E-Ink friendly.
}
