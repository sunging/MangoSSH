plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val embeddedTsnetAar = layout.buildDirectory.file("generated/tsnet/mangossh-tsnet.aar")
val buildEmbeddedTsnetAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the pinned four-ABI embedded tsnet gomobile bridge."
    val bridgeSources = rootProject.fileTree("native/tsnetbridge") {
        exclude(".build/**")
    }
    inputs.files(bridgeSources)
    inputs.files(
        rootProject.file("tools/build-tsnet-android-wsl.sh"),
        rootProject.file("tools/fetch-android-ndk-wsl.sh"),
        rootProject.file("tools/fetch-go-wsl.sh"),
        rootProject.file("tools/fetch-jdk17-wsl.sh"),
        rootProject.file("tools/generate-tsnet-notices.py"),
        rootProject.file("tools/normalize-tsnet-aar.py"),
        rootProject.file("tools/patches/tailscale-v1.98.8-tsnet-no-logtail.patch"),
    )
    outputs.file(embeddedTsnetAar)
    workingDir(rootProject.projectDir)
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        val windowsRoot = rootProject.projectDir.absolutePath.replace("'", "'\"'\"'")
        commandLine(
            "wsl.exe",
            "bash",
            "-lc",
            "cd \"\$(wslpath -u '$windowsRoot')\" && bash tools/build-tsnet-android-wsl.sh",
        )
    } else {
        commandLine("bash", "tools/build-tsnet-android-wsl.sh")
    }
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
    compileSdk = 37

    defaultConfig {
        applicationId = "website.sung.mangossh"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"

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

tasks.named("preBuild").configure {
    dependsOn(buildEmbeddedTsnetAar)
}

dependencies {
    implementation(files(embeddedTsnetAar))
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
    implementation(project(":third_party:termlib"))
    implementation(libs.conscrypt.android)
    implementation(libs.androidx.biometric)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
