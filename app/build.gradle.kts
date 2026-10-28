plugins { id("com.android.application") }

android {
    namespace = "com.mangaglass.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mangaglass.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "V1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
