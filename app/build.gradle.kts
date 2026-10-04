plugins { id("com.android.application") }

android {
    namespace = "com.mangaglass.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mangaglass.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false; isDebuggable = false } }
    lint { abortOnError = true }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
