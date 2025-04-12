plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.npoor.aletmate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.npoor.aletmate" // ✅ fixed from com.example.aletmate
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Core libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Unit testing
    testImplementation(libs.junit)

    // Android instrumentation testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")              // ✅ For @RunWith(AndroidJUnit4::class)
    androidTestImplementation("androidx.test:runner:1.5.2")                // ✅ For InstrumentationRegistry
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1") // ✅ Espresso for UI testing
}
