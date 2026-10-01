pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// Use the JDK selected by the caller or IDE; never provision a different JVM.
check(JavaVersion.current() == JavaVersion.VERSION_17) {
    "MangoSSH requires JDK 17. Set JAVA_HOME or the IDE Gradle JDK to an installed JDK 17."
}
val javacName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "javac.exe"
} else {
    "javac"
}
check(file("${System.getProperty("java.home")}/bin/$javacName").isFile) {
    "MangoSSH requires a full JDK 17 installation, including javac."
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MangoSSH"
include(":app")
include(":third_party:termlib")
include(":third_party:cbssh")
include(":third_party:cbssh:protocol")
