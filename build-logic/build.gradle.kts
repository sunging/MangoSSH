plugins { `java-gradle-plugin` }

repositories { mavenCentral() }

dependencies {
    implementation(localGroovy())
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

gradlePlugin {
    plugins {
        create("nativeTools") {
            id = "mangossh.native-tools"
            implementationClass = "website.sung.build.NativeToolsPlugin"
        }
    }
}
