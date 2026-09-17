plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation(project(":third_party:cbssh:protocol"))
    implementation("io.github.nsk90:kstatemachine-jvm:0.38.1")
    implementation("io.ktor:ktor-network:3.5.2")
    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("com.google.crypto.tink:tink:1.23.0") { isTransitive = false }
    implementation("asia.hombre:kyber:2.0.1")
    implementation("org.connectbot:jbcrypt:1.0.2")
    testImplementation(kotlin("test"))
    testImplementation("nl.jqno.equalsverifier:equalsverifier:4.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.18")
}

tasks.test { useJUnitPlatform() }
