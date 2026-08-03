import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

// Keep the patched Kotlin implementation in source control, but reuse the
// byte-identical JNI libraries from the pinned upstream AAR. The AAR is kept
// off every compile/runtime classpath so its classes cannot collide with this
// module's patched classes.
val upstreamNativeBundle by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

abstract class ExtractTermlibJni : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputAar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun extract() {
        fileSystemOperations.sync {
            from(archiveOperations.zipTree(inputAar)) {
                include("jni/**")
                eachFile { path = path.removePrefix("jni/") }
                includeEmptyDirs = false
            }
            into(outputDirectory)
        }
    }
}

val upstreamAar = layout.file(
    upstreamNativeBundle.elements.map { files -> files.single().asFile },
)

android {
    namespace = "org.connectbot.terminal"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val capitalizedVariant = variant.name.replaceFirstChar(Char::uppercaseChar)
        val extractUpstreamJni = tasks.register<ExtractTermlibJni>("extract${capitalizedVariant}UpstreamJni") {
            inputAar.set(upstreamAar)
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            extractUpstreamJni,
            ExtractTermlibJni::outputDirectory,
        )
    }
}

dependencies {
    add(upstreamNativeBundle.name, "org.connectbot:termlib:${libs.versions.termlib.get()}@aar")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
