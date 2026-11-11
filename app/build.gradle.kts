plugins { id("com.android.application") }

android {
    namespace = "com.mangaglass.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mangaglass.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "V1.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release { isMinifyEnabled = false; isDebuggable = false; ndk { abiFilters += "arm64-v8a" } }
    }
    lint { abortOnError = true }
    // The bundled OCR weights load through memory mapping, so the assets stay uncompressed.
    androidResources { noCompress += listOf("onnx") }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
