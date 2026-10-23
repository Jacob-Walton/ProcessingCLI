import org.apache.tools.ant.types.resources.Archives
import java.lang.module.ModuleFinder

plugins {
    id("java")
    application
}

group = "org.konpeki"
version = "1.0-SNAPSHOT"
val processingVersion = "4.5.7"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.processing:core:$processingVersion")
    listOf("pdf", "svg", "dxf", "net", "serial", "io").forEach {
        implementation("org.processing.core:$it:$processingVersion")
    }
    implementation("org.processing:preprocessor:$processingVersion") {
        exclude(group = "org.processing", module = "utils")
    }
    implementation(files("libs/processing-utils-4.5.7.jar"))
}

application {
    mainClass = "org.konpeki.Main"
}

tasks.test {
    useJUnitPlatform()
}

interface InjectedOps {
    @get:Inject val archives: ArchiveOperations
    @get:Inject val fs: FileSystemOperations
    @get:Inject val exec: ExecOperations
}
val ops = objects.newInstance<InjectedOps>()
val archives = ops.archives
val fs = ops.fs
val execOps = ops.exec

tasks.register<Jar>("fatJar") {
    group = "build"
    archiveClassifier = "all"
    manifest { attributes["Main-Class"] = "org.konpeki.Main" }
    val zips = archives

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { cp ->
        cp.filter { it.name.endsWith(".jar") }.map { zips.zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.register("packageApp") {
    group = "build"
    dependsOn("fatJar")
    val jar = tasks.named<Jar>("fatJar").get().archiveFile.get().asFile
    val out = layout.buildDirectory.dir("app").get().asFile
    val trainingSketch = layout.projectDirectory.dir("training/Training").asFile
    val trainingCache = layout.buildDirectory.dir("aot-training").get().asFile
    val javaHome = System.getProperty("java.home")
    val fileOps = fs
    val exec = execOps

    val allModules = ModuleFinder.ofSystem().findAll()
        .map { it.descriptor().name() }
        .filterNot { it.startsWith("jdk.incubator") }
        .sorted()
        .joinToString(",")

    inputs.file(jar)
    inputs.dir(trainingSketch)
    inputs.property("modules", allModules)
    outputs.dir(out)

    doLast {
        fileOps.delete { delete(out, trainingCache) }

        exec.exec {
            commandLine(
                "$javaHome/bin/jpackage",
                "--type", "app-image",
                "--name", "ProcessingCLI",
                "--input", jar.parent,
                "--main-jar", jar.name,
                "--main-class", "org.konpeki.Main",
                "--add-modules", allModules,
                "--jlink-options", "--strip-debug --no-man-pages --no-header-files --generate-cds-archive",
                "--java-options", "-XX:AOTCache=\$APPDIR/processingcli.aot -Xlog:all=error",
                "--win-console",
                "--dest", out.absolutePath
            )
        }

        val appDir = File(out, "ProcessingCLI/app")
        exec.exec {
            commandLine(
                File(out, "ProcessingCLI/runtime/bin/java.exe").absolutePath,
                "-XX:AOTCacheOutput=" + File(appDir, "processingcli.aot").absolutePath,
                "-Xlog:all=error",
                "-cp", File(appDir, jar.name).absolutePath,
                "org.konpeki.Main",
                trainingSketch.absolutePath
            )
            environment("PCLI_BENCH", "1")
            environment("LOCALAPPDATA", trainingCache.absolutePath)
        }
    }
}

tasks.register<Zip>("releaseZip") {
    group = "distribution"
    description = "Zips the standalone app for sharing."
    dependsOn("packageApp")

    from(layout.buildDirectory.dir("app"))
    archiveBaseName = "ProcessingCLI"
    archiveVersion = project.version.toString()
    archiveClassifier = "windows-x64"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isZip64 = true
}