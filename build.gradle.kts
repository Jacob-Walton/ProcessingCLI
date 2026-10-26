import org.apache.tools.ant.types.resources.Archives
import java.lang.module.ModuleFinder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    id("java")
    id("com.diffplug.spotless") version "8.5.1"
    application
}

spotless {
    java {
        palantirJavaFormat()
        removeUnusedImports()
    }
}

group = "org.konpeki"
// Release builds pass -PcliVersion from the git tag
version = (findProperty("cliVersion") as String?) ?: "1.0.0"
val processingVersion = "4.5.7"
val isMac = System.getProperty("os.name").startsWith("Mac")
val platform = (if (isMac) "macos" else "windows") + "-" + (if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64")

System.getenv("LOCALAPPDATA")?.let { layout.buildDirectory = file("$it/ProcessingCLI-build") }

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

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
    implementation("info.picocli:picocli:4.7.7")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1")
}

application {
    mainClass = "org.konpeki.Main"
}

tasks.compileJava {
    dependsOn(tasks.spotlessApply)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }

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
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .get().metadata.installationPath.asFile.absolutePath
    val fileOps = fs
    val jarTime = 946684800000L // 2000-01-01T00:00:00Z
    val exec = execOps
    val mac = isMac
    val zips = archives
    val appVersion = version.toString()
    // Skip probing OpenGL ES (only used on ARM Linux) and load JOGL's natives without unpacking them each launch
    val jogl = "-Djogl.disable.opengles -Djogamp.gluegen.UseTempJarCache=false"

    inputs.file(jar)
    inputs.dir(trainingSketch)
    inputs.property("jdk", jdk)
    outputs.dir(out)

    doLast {
        fileOps.delete { delete(out, trainingCache) }
        // Invalidates compiled sketch caches from older builds
        val buildId = System.currentTimeMillis()

        // Every module in the toolchain JDK (not Gradle's own JVM)
        val listing = ByteArrayOutputStream()
        exec.exec {
            commandLine("$jdk/bin/java", "--list-modules")
            standardOutput = listing
        }
        val allModules = listing.toString().lines()
            .map { it.substringBefore('@').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("jdk.incubator") && it != "jdk.jlink" && it != "jdk.jpackage" }
            .joinToString(",")

        exec.exec {
            commandLine(listOfNotNull(
                "$jdk/bin/jpackage",
                "--type", "app-image",
                "--name", "ProcessingCLI",
                "--app-version", appVersion,
                "--input", jar.parent,
                "--main-jar", jar.name,
                "--main-class", "org.konpeki.Main",
                "--add-modules", allModules,
                "--jlink-options", "--strip-debug --no-man-pages --no-header-files --generate-cds-archive",
                "--java-options", "-XX:AOTCache=\$APPDIR/processingcli.aot -Xlog:all=error -Xlog:aot=off --enable-native-access=ALL-UNNAMED $jogl -Djava.library.path=\$APPDIR/natives -Dprocessingcli.jarTime=$jarTime -Dprocessingcli.build=$buildId",
                if (mac) null else "--win-console",
                "--dest", out.absolutePath
            ))
        }

        val appDir = File(out, if (mac) "ProcessingCLI.app/Contents/app" else "ProcessingCLI/app")
        val java = File(out, if (mac) "ProcessingCLI.app/Contents/runtime/Contents/Home/bin/java" else "ProcessingCLI/runtime/bin/java.exe")
        // Fixed mtime so the AOT cache survives unzipping (restored in Main)
        File(appDir, jar.name).setLastModified(jarTime)
        // jpackage copies everything in build/libs, including jars from older builds
        appDir.listFiles { f -> f.name.endsWith(".jar") && f.name != jar.name }?.forEach { it.delete() }

        val natives = File(appDir, "natives")
        fileOps.copy {
            from(zips.zipTree(jar)) { include("natives/${if (mac) "macosx-universal" else "windows-amd64"}/*") }
            eachFile { path = name }
            includeEmptyDirs = false
            into(natives)
        }

        exec.exec {
            commandLine(listOf(
                java.absolutePath,
                "-XX:AOTCacheOutput=" + File(appDir, "processingcli.aot").absolutePath,
                "-Xlog:all=error",
                "--enable-native-access=ALL-UNNAMED",
                "-Djava.library.path=" + natives.absolutePath) + jogl.split(" ") + listOf(
                "-cp", File(appDir, jar.name).absolutePath,
                "org.konpeki.Main",
                "--bench",
                trainingSketch.absolutePath
            ))
            environment("PCLI_BENCH", "1")
            environment("LOCALAPPDATA", trainingCache.absolutePath)
        }
    }
}

tasks.register("singleExe") {
    group = "distribution"
    description = "Packs the app-image into one self-extracting exe (needs warp-packer on PATH)."
    dependsOn("packageApp")
    val input = layout.buildDirectory.dir("app/ProcessingCLI").get().asFile
    val output = layout.buildDirectory.file("distributions/ProcessingCLI.exe").get().asFile
    val exec = execOps

    inputs.dir(input)
    outputs.file(output)

    doLast {
        output.parentFile.mkdirs()
        exec.exec {
            commandLine(
                "warp-packer",
                "--arch", "windows-x64",
                "--input_dir", input.absolutePath,
                "--exec", "ProcessingCLI.exe",
                "--output", output.absolutePath
            )
        }
        println("Built ${output.absolutePath}")
    }
}

tasks.register<Zip>("releaseZip") {
    group = "distribution"
    description = "Zips the standalone app for sharing."
    dependsOn("packageApp")

    from(layout.buildDirectory.dir("app"))
    archiveBaseName = "ProcessingCLI"
    archiveVersion = ""
    archiveClassifier = platform
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isZip64 = true
    // Keep exec bits for the macOS launcher
    useFileSystemPermissions()
    doLast {
        val zip = archiveFile.get().asFile
        val sha = MessageDigest.getInstance("SHA-256").digest(zip.readBytes())
            .joinToString("") { "%02x".format(it) }
        File("${zip.path}.sha256").writeText("$sha  ${zip.name}\n")
        println("Built ${zip.absolutePath}")
        println("Built ${zip.absolutePath}.sha256")
    }
}
