import website.sung.build.NativeSettings

plugins {
    id("mangossh.native-tools")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

val nativeConfig = NativeSettings.read(rootProject.projectDir)
val nativeAbis = NativeSettings.abis(project)

android {
    ndkVersion = nativeConfig["ndk"].toString()
    NativeSettings.agpNdkPath(project)?.let { ndkPath = it }
    namespace = "org.connectbot.terminal"
    compileSdk = 37

    defaultConfig {
        minSdk = (nativeConfig["androidApi"] as Number).toInt()
        ndk {
            abiFilters += nativeAbis
            debugSymbolLevel = "FULL"
        }
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_static"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = nativeConfig["cmake"].toString()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {

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
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
