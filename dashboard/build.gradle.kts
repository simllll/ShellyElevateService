plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.simllll.panelelevate.dashboard"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Opt-in live test (see LiveHassTest): -Dha.live=true -Dha.url=… -Dha.token=…
            it.systemProperty("ha.live", System.getProperty("ha.live") ?: "false")
            it.systemProperty("ha.url", System.getProperty("ha.url") ?: "")
            it.systemProperty("ha.token", System.getProperty("ha.token") ?: "")
            it.systemProperty("ha.dashboard", System.getProperty("ha.dashboard") ?: "")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ripple)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidsvg)
    // libwebrtc for camera live views (receive-only; native code for arm64-v8a/armeabi-v7a only, see app ndk.abiFilters)
    implementation(libs.stream.webrtc)

    implementation(platform(libs.okhttpbom))
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.okhttpbom))
    testImplementation(libs.mockwebserver)
}
