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

// The Tailscale version actually linked into the embedded tsnet bridge, read
// straight from the vendored module's own VERSION.txt so the value shown in
// Settings can never drift from the source gomobile compiles into the AAR.
val vendoredTailscaleVersionFile =
    rootProject.layout.projectDirectory.file("native/tsnetbridge/vendor/tailscale.com/VERSION.txt")
val embeddedTailscaleVersion =
    providers.fileContents(vendoredTailscaleVersionFile).asText.get().trim()
require(Regex("""^\d+\.\d+\.\d+$""").matches(embeddedTailscaleVersion)) {
    "Invalid vendored Tailscale version: '$embeddedTailscaleVersion'"
}
// tools/build-tsnet-android.sh validates this same pin against modules.txt
// before building; cross-check it here too so a stale VERSION.txt can't
// silently disagree with the module gomobile actually vendors.
val vendoredModulesText = providers.fileContents(
    rootProject.layout.projectDirectory.file("native/tsnetbridge/vendor/modules.txt"),
).asText.get()
require(
    vendoredModulesText.lineSequence().any { it == "# tailscale.com v$embeddedTailscaleVersion" },
) {
    "vendor/modules.txt does not pin tailscale.com v$embeddedTailscaleVersion"
}

val embeddedTsnetAar = layout.buildDirectory.file("generated/tsnet/mangossh-tsnet.aar")
val androidSdkPath = androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath
val buildEmbeddedTsnetAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the pinned four-ABI embedded tsnet gomobile bridge."
    val bridgeSources = rootProject.fileTree("native/tsnetbridge") {
        exclude(".build/**")
    }
    inputs.files(bridgeSources)
    inputs.files(
        rootProject.file("tools/build-tsnet-android.sh"),
        rootProject.file("tools/fetch-android-ndk.sh"),
        rootProject.file("tools/fetch-go.sh"),
        rootProject.file("tools/fetch-jdk17.sh"),
        rootProject.file("tools/lib/linux-host.sh"),
        rootProject.file("tools/generate-tsnet-notices.py"),
        rootProject.file("tools/normalize-tsnet-aar.py"),
        rootProject.file("tools/patches/tailscale-v1.102.2-tsnet-no-logtail.patch"),
    )
    inputs.property("androidSdkDirectory", androidSdkPath)
    outputs.file(embeddedTsnetAar)
    workingDir(rootProject.projectDir)

    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        val translatedVariables =
            "MANGOSSH_PROJECT_DIR/p:ANDROID_HOME/p:ANDROID_SDK_ROOT/p"
        val inheritedWslEnv = providers.environmentVariable("WSLENV").orNull.orEmpty()
        val wslEnv = listOf(inheritedWslEnv, translatedVariables)
            .filter(String::isNotBlank)
            .joinToString(":")
        environment("MANGOSSH_PROJECT_DIR", rootProject.projectDir.absolutePath)
        environment("ANDROID_HOME", androidSdkPath)
        environment("ANDROID_SDK_ROOT", androidSdkPath)
        environment("WSLENV", wslEnv)
        commandLine(
            "wsl.exe",
            "bash",
            "-lc",
            "cd \"\$MANGOSSH_PROJECT_DIR\" && bash tools/build-tsnet-android.sh",
        )
    } else {
        environment("ANDROID_HOME", androidSdkPath)
        environment("ANDROID_SDK_ROOT", androidSdkPath)
        commandLine("bash", "tools/build-tsnet-android.sh")
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

/** Writes the vendored Tailscale release as a Kotlin constant for display in Settings. */
abstract class GenerateEmbeddedTsnetBuildInfo : DefaultTask() {
    @get:Input
    abstract val tailscaleVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDir = outputDirectory.get().asFile
            .resolve("website/sung/mangossh/session/tsnet")
        packageDir.mkdirs()
        packageDir.resolve("EmbeddedTsnetBuildInfo.kt").writeText(
            """
            |// Generated by the :app build from native/tsnetbridge/vendor/tailscale.com/VERSION.txt.
            |// Do not edit.
            |package website.sung.mangossh.session.tsnet
            |
            |/** Tailscale release the vendored tsnet bridge in this build was compiled from. */
            |internal object EmbeddedTsnetBuildInfo {
            |    const val TAILSCALE_VERSION: String = "${tailscaleVersion.get()}"
            |}
            |
            """.trimMargin(),
        )
    }
}

val generateEmbeddedTsnetBuildInfo by tasks.registering(GenerateEmbeddedTsnetBuildInfo::class) {
    group = "build"
    description = "Generates the vendored Tailscale version constant shown in Settings."
    tailscaleVersion.set(embeddedTailscaleVersion)
}

androidComponents.onVariants { variant ->
    requireNotNull(variant.sources.kotlin) { "Kotlin sources are required" }
        .addGeneratedSourceDirectory(
            generateEmbeddedTsnetBuildInfo,
            GenerateEmbeddedTsnetBuildInfo::outputDirectory,
        )
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
