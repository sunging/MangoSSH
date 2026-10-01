plugins { `java-gradle-plugin` }

dependencies { implementation(localGroovy()) }

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
