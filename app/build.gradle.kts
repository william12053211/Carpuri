import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Refuse a missing or accidentally replaced unpatched SDK instead of silently reversing seats again.
val seatSdkAar = file("libs/Vehicle-2.3.2-hdapp-seatfix.aar")
check(seatSdkAar.isFile) { "Missing seat-fixed SDK. Extract the complete carpuri project ZIP, including app/libs." }
val seatSdkHash = MessageDigest.getInstance("SHA-256").digest(seatSdkAar.readBytes())
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
check(seatSdkHash == "db760e4aa15a595917823edcd3ba60bf0d4ef11cf060a8b5b679872445d1a4c7") {
    "Unexpected Vehicle SDK bytes. Restore app/libs/Vehicle-2.3.2-hdapp-seatfix.aar from the carpuri project ZIP."
}

android {
    namespace = "kr.hdapp.carpool"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "kr.hdapp.carpool"
        minSdk = 23
        targetSdk = 35
        versionCode = 10
        versionName = "0.2.6"
        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.vehicleSdk.get()}\"")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":core"))
    // SDK 2.3.2's original seat Boolean conversion swaps Empty/Occupied and loses UNKNOWN.
    // This fixed AAR changes only the seat getter and callback conversions.
    implementation(files("libs/Vehicle-2.3.2-hdapp-seatfix.aar"))
    // A local AAR has no Maven POM, so retain the original SDK's transitive dependencies explicitly.
    implementation("ai.pleos.playground:vehicle-aidl:1.1.1")
    implementation("ai.pleos.playground:vehicle-data:1.1.11")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.25")
    implementation("com.google.code.gson:gson:2.10.1")
    // The Kotlin plugin and core-ktx below already provide the remaining original POM dependencies.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
