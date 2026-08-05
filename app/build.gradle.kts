import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// version.txt is the only source of truth for Android and GitHub releases.
// Keep this parser deliberately strict so an invalid release cannot reach a
// signed APK, GitHub Release, or F-Droid build with ambiguous version data.
val appVersionRegularFile = rootProject.layout.projectDirectory.file("version.txt")
val appVersionFile = appVersionRegularFile.asFile
require(appVersionFile.isFile) {
    "Missing version.txt at the repository root"
}
val appVersionName = providers.fileContents(appVersionRegularFile).asText.get().trim()
val appVersionMatch = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")
    .matchEntire(appVersionName)
    ?: error(
        "Invalid version in version.txt: '$appVersionName'. " +
            "Expected stable MAJOR.MINOR.PATCH SemVer, for example 1.4.2.",
    )
val appVersionMajor = appVersionMatch.groupValues[1].toLongOrNull()
    ?: error("Version major component is too large: ${appVersionMatch.groupValues[1]}")
val appVersionMinor = appVersionMatch.groupValues[2].toLongOrNull()
    ?: error("Version minor component is too large: ${appVersionMatch.groupValues[2]}")
val appVersionPatch = appVersionMatch.groupValues[3].toLongOrNull()
    ?: error("Version patch component is too large: ${appVersionMatch.groupValues[3]}")
require(appVersionMajor <= 2_100L) {
    "Version major component must be between 0 and 2100"
}
require(appVersionMinor <= 999L) {
    "Version minor component must be between 0 and 999"
}
require(appVersionPatch <= 999L) {
    "Version patch component must be between 0 and 999"
}
val derivedVersionCodeLong =
    appVersionMajor * 1_000_000L + appVersionMinor * 1_000L + appVersionPatch
require(derivedVersionCodeLong in 1L..2_100_000_000L) {
    "Calculated Android versionCode is out of range: $derivedVersionCodeLong"
}
val appVersionCode = derivedVersionCodeLong.toInt()

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
        versionCode = appVersionCode
        versionName = appVersionName

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

val releaseTag = providers.gradleProperty("releaseTag")
val localizedReleaseNotes = listOf(
    "en-US" to rootProject.file(
        "fastlane/metadata/android/en-US/changelogs/$appVersionCode.txt",
    ),
    "zh-CN" to rootProject.file(
        "fastlane/metadata/android/zh-CN/changelogs/$appVersionCode.txt",
    ),
)

tasks.register("verifyReleaseVersion") {
    group = "verification"
    description = "Verifies the app version, optional release tag, and localized release notes."

    val expectedVersionName = appVersionName
    val expectedVersionCode = appVersionCode
    val expectedNoteLocales = localizedReleaseNotes.map { it.first }
    val expectedNotePaths = localizedReleaseNotes.map { it.second.absolutePath }
    val expectedNoteDisplayPaths = localizedReleaseNotes.map {
        it.second.relativeTo(rootProject.projectDir).invariantSeparatorsPath
    }

    inputs.file(appVersionFile)
    inputs.files(localizedReleaseNotes.map { it.second })
    inputs.property("versionName", expectedVersionName)
    inputs.property("versionCode", expectedVersionCode)
    inputs.property("releaseTag", releaseTag.orElse(""))

    doLast {
        val suppliedTag = inputs.properties.getValue("releaseTag").toString()
        if (suppliedTag.isNotEmpty()) {
            require(suppliedTag == "v$expectedVersionName") {
                "Release tag '$suppliedTag' does not match version.txt; " +
                    "expected 'v$expectedVersionName'."
            }
        }

        expectedNotePaths.indices.forEach { index ->
            val notesPath = expectedNotePaths[index]
            val notesDisplayPath = expectedNoteDisplayPaths[index]
            val locale = expectedNoteLocales[index]
            val notesFile = File(notesPath)
            require(notesFile.isFile && notesFile.readText(Charsets.UTF_8).isNotBlank()) {
                "Missing non-empty $locale release notes for versionCode $expectedVersionCode: " +
                    notesDisplayPath
            }
        }

        logger.lifecycle(
            "MangoSSH release version verified: versionName={} versionCode={}",
            expectedVersionName,
            expectedVersionCode,
        )
    }
}

dependencies {
    implementation(files(embeddedTsnetAar))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
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
