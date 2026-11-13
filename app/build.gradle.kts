plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.stretter.shellyelevateservice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stretter.shellyelevateservice"
        minSdk = 24
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 24
        versionCode = 2_05_00
        versionName = "2.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Only ARM devices (NSPanel Pro, Shelly Wall Displays); keeps libwebrtc's x86 builds out of the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release signing comes from the environment (GitHub Actions secrets). Without it, the
    // release APK stays unsigned, so forks and local builds keep working.
    val signingKeystore = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (signingKeystore != null) {
            create("release") {
                storeFile = file(signingKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (signingKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Compress native libraries (libwebrtc: 18 MB uncompressed); they are extracted on install.
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.preference)
    implementation(libs.nanohttpd)
    implementation(libs.org.eclipse.paho.mqttv5.client)

    implementation(platform(libs.okhttpbom))
    implementation(libs.okhttp)
    implementation(libs.appcompat)
    implementation(project(":dashboard"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}