plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release credentials are intentionally injected only by CI. Local release
// builds remain unsigned unless every required environment variable is supplied,
// which prevents a developer workstation from accidentally depending on secrets.
val releaseStorePath = providers.environmentVariable("MANGOSSH_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("MANGOSSH_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MANGOSSH_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MANGOSSH_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "website.sung.mangossh"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "website.sung.mangossh"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        create("release") {
            // Do not attach a partially populated signing configuration: Gradle
            // would otherwise read a path or password that is not safe to expose.
            if (hasReleaseSigning) {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            // GitHub Actions supplies the complete signing material only for the
            // signed release workflow. This preserves unsigned local builds.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    // Mosh is executed from nativeLibraryDir, so native assets must be copied
    // out of the APK rather than memory-mapped in place.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.connectbot.sshlib)
    implementation(libs.connectbot.termlib)
    implementation(libs.conscrypt.android)
    implementation(libs.androidx.biometric)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
