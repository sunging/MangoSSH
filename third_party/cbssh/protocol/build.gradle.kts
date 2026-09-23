plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

kotlin { jvmToolchain(17) }

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

val kaitaiCompiler by configurations.creating

/** Generates protocol codecs from the pinned, reviewable wire definitions. */
abstract class KaitaiTask : JavaExec() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val definitions: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    override fun exec() {
        destination.get().asFile.mkdirs()
        args = listOf("--read-write", "--target", "java", "--outdir", destination.get().asFile.absolutePath,
            "--java-package", "org.connectbot.sshlib.protocol") + definitions.files.sortedBy { it.name }.map { it.absolutePath }
        super.exec()
    }
}

val kaitai = tasks.register<KaitaiTask>("kaitai") {
    definitions.from(fileTree("src/main/resources/kaitai") { include("*.ksy") })
    destination.set(layout.buildDirectory.dir("generated/kaitai"))
    classpath = kaitaiCompiler
    mainClass.set("io.kaitai.struct.JavaMain")
}

sourceSets.main { java.srcDir(kaitai.flatMap { it.destination }) }

dependencies {
    testImplementation(kotlin("test"))
    api("io.kaitai:kaitai-struct-runtime:0.11")
    kaitaiCompiler("io.kaitai:kaitai-struct-compiler_2.13:0.11")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
}

tasks.test { useJUnitPlatform() }
