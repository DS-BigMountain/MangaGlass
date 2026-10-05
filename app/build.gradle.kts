plugins { id("com.android.application") }

android {
    namespace = "com.mangaglass.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mangaglass.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
    lint { abortOnError = true }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
