plugins { `java-library` }

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
    implementation("org.connectbot:simplesocks:1.0.1")
    implementation("org.connectbot:jbcrypt:1.0.2")
    implementation("com.google.crypto.tink:tink:1.21.0") { isTransitive = false }
    implementation("asia.hombre:kyber:2.0.1")
    testImplementation(libs.junit)
}
