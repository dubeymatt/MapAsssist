plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.mapasssist"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mapasssist"
        minSdk = 24
        targetSdk = 35
        // Keep this value in step with each distributed build; the Settings
        // screen reads it directly from BuildConfig.
        versionCode = 105
        versionName = "0.9.15"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

tasks.register("writeVersion") {
    doLast {
        val file = file("version.txt")
        file.writeText("Kotlin version determined by AGP")
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swipe.refresh)
    implementation(libs.androidx.work.runtime)
    implementation(libs.google.play.services.auth)
    implementation(libs.material)
    implementation("androidx.cardview:cardview:1.0.0")
    // The project currently uses Kotlin 2.1; this stable BoM line is binary
    // compatible with it (newer Firebase Auth releases require Kotlin 2.3).
    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
