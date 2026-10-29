import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    id("java")
    id("com.diffplug.spotless") version "8.5.1"
    application
}

group = "org.konpeki"
val processingVersion = "4.5.7"


// Releases pass -PcliVersion, local builds are like 1.3.2-dev.5+g4f2a9c1
fun gitVersion(): String? {
    val described = providers.exec {
        commandLine("git", "describe", "--tags", "--long", "--dirty", "--match", "v*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match = Regex("""v(\d+)\.(\d+)\.(\d+)-(\d+)-(g\w+)(-dirty)?""").matchEntire(described) ?: return null
    val (major, minor, patch, commits, hash, dirty) = match.destructured
    if (commits == "0" && dirty.isEmpty()) return "$major.$minor.$patch"
    val next = "$major.$minor.${patch.toInt() + 1}"
    return "$next-dev.$commits+$hash" + if (dirty.isEmpty()) "" else ".dirty"
}

version = findProperty("cliVersion") as String? ?: gitVersion() ?: "1.0.0"


val os = System.getProperty("os.name").let {
    when {
        it.startsWith("Mac") -> "macos"
        it.startsWith("Windows") -> "windows"
        else -> "linux"
    }
}
val arm = System.getProperty("os.arch") == "aarch64"
// Matches Node's process.platform/process.arch naming used by the VS Code extension
val platform = "$os-" + if (arm) "arm64" else "x64"


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
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.debug:0.23.1")
    // Comes with Processing at runtime
    compileOnly("org.jogamp.jogl:jogl-all:2.6.0")
    compileOnly("org.jogamp.gluegen:gluegen-rt:2.6.0")
}

application {
    mainClass = "org.konpeki.Main"
}

spotless {
    java {
        palantirJavaFormat()
        removeUnusedImports()
    }
}

tasks.compileJava {
    dependsOn(tasks.spotlessApply)
}

tasks.test {
    useJUnitPlatform()
}

// JDK and Processing docs and sources, the JDK from -PjdkSources or a Temurin download
val docSources: Configuration by configurations.creating
dependencies { docSources("org.processing:core:$processingVersion:sources") { isTransitive = false } }
val generatedDocs = layout.buildDirectory.dir("generated/docs")
val docIndex = tasks.register<JavaExec>("docIndex") {
    dependsOn(tasks.compileJava)
    classpath = files(sourceSets.main.get().java.classesDirectory) + configurations.runtimeClasspath.get()
    mainClass = "org.konpeki.DocIndex"
    val jdkSources = (findProperty("jdkSources") as String?)
        ?: javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
            vendor = JvmVendorSpec.ADOPTIUM
        }.get().metadata.installationPath.file("lib/src.zip").asFile.absolutePath
    val out = generatedDocs.get().file("org/konpeki/docs.json.gz").asFile
    val sourceJars: FileCollection = docSources
    inputs.files(sourceJars)
    inputs.property("jdkSources", jdkSources)
    outputs.file(out)
    outputs.file(generatedDocs.get().file("org/konpeki/sources.zip"))
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(out.absolutePath, jdkSources) + sourceJars.files.map { it.absolutePath }
    })
}
sourceSets.main { resources.srcDir(generatedDocs) }
tasks.processResources { dependsOn(docIndex) }

tasks.named("distZip") { enabled = false }
tasks.named("distTar") { enabled = false }

// Task actions can't use the script
interface Services {
    @get:Inject val archives: ArchiveOperations
    @get:Inject val files: FileSystemOperations
    @get:Inject val exec: ExecOperations
}
val services = objects.newInstance<Services>()

tasks.register<Jar>("fatJar") {
    group = "build"
    archiveClassifier = "all"
    manifest { attributes["Main-Class"] = "org.konpeki.Main" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val archives = services.archives
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.filter { it.name.endsWith(".jar") }.map { archives.zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Standalone app with its own runtime
tasks.register("packageApp") {
    group = "build"
    dependsOn("fatJar")

    val jar = tasks.named<Jar>("fatJar").get().archiveFile.get().asFile
    val out = layout.buildDirectory.dir("app").get().asFile
    // Only this build's jar, jpackage adds all of --input
    val input = layout.buildDirectory.dir("jpackage-input").get().asFile
    val sketchLauncher = layout.buildDirectory.file("sketch-launcher.properties").get().asFile
    val trainingSketch = layout.projectDirectory.dir("training/Training").asFile
    val trainingCache = layout.buildDirectory.dir("aot-training").get().asFile
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .get().metadata.installationPath.asFile.absolutePath

    val fullVersion = version.toString()
    // jpackage only takes numbers
    val appVersion = fullVersion.substringBefore('-')
    val target = os
    val nativesDir = when (os) {
        "macos" -> "macosx-universal"
        "linux" -> if (arm) "linux-aarch64" else "linux-amd64"
        else -> "windows-amd64"
    }
    // 2000-01-01, so the AOT cache survives unzipping
    val jarTime = 946684800000L
    // Skip probing OpenGL ES (only used on ARM Linux) and load JOGL's natives without unpacking them each launch
    val jogl = listOf("-Djogl.disable.opengles", "-Djogamp.gluegen.UseTempJarCache=false")
    val archives = services.archives
    val files = services.files
    val exec = services.exec

    inputs.file(jar)
    inputs.dir(trainingSketch)
    inputs.property("jdk", jdk)
    outputs.dir(out)

    doLast {
        files.delete { delete(out, trainingCache, input) }
        files.copy { from(jar); into(input) }
        // Windowless launcher for exported sketches
        sketchLauncher.writeText("main-class=org.konpeki.Exported\nwin-console=false\n")

        // Every module in the toolchain JDK
        val listing = ByteArrayOutputStream()
        exec.exec {
            commandLine("$jdk/bin/java", "--list-modules")
            standardOutput = listing
        }
        val modules = listing.toString().lines()
            .map { it.substringBefore('@').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("jdk.incubator") && it != "jdk.jlink" && it != "jdk.jpackage" }

        val javaOptions = listOf(
            "-XX:AOTCache=\$APPDIR/processingcli.aot",
            "-Xlog:all=error",
            "-Xlog:aot=off",
            "--enable-native-access=ALL-UNNAMED",
            "-Djava.library.path=\$APPDIR/natives",
            "-Dprocessingcli.jarTime=$jarTime",
            // Invalidates compiled sketch caches from older builds
            "-Dprocessingcli.build=${System.currentTimeMillis()}",
            "-Dprocessingcli.version=$fullVersion",
        ) + jogl
        exec.exec {
            commandLine(listOfNotNull(
                "$jdk/bin/jpackage",
                "--type", "app-image",
                "--name", "ProcessingCLI",
                "--app-version", appVersion,
                "--input", input.absolutePath,
                "--main-jar", jar.name,
                "--main-class", "org.konpeki.Main",
                "--add-modules", modules.joinToString(","),
                "--jlink-options", "--strip-debug --no-man-pages --no-header-files --generate-cds-archive",
                "--java-options", javaOptions.joinToString(" "),
                if (target == "windows") "--win-console" else null,
                "--add-launcher", "Sketch=${sketchLauncher.absolutePath}",
                "--dest", out.absolutePath,
            ))
        }

        // Where jpackage put the app
        val (appDir, java) = when (target) {
            "macos" -> File(out, "ProcessingCLI.app/Contents/app") to
                File(out, "ProcessingCLI.app/Contents/runtime/Contents/Home/bin/java")
            "linux" -> File(out, "ProcessingCLI/lib/app") to File(out, "ProcessingCLI/lib/runtime/bin/java")
            else -> File(out, "ProcessingCLI/app") to File(out, "ProcessingCLI/runtime/bin/java.exe")
        }
        val appJar = File(appDir, jar.name)
        appJar.setLastModified(jarTime)

        val natives = File(appDir, "natives")
        files.copy {
            from(archives.zipTree(jar)) { include("natives/$nativesDir/*") }
            eachFile { path = name }
            includeEmptyDirs = false
            into(natives)
        }

        // Record the AOT cache
        exec.exec {
            commandLine(listOf(
                java.absolutePath,
                "-XX:AOTCacheOutput=" + File(appDir, "processingcli.aot").absolutePath,
                "-Xlog:all=error",
                "--enable-native-access=ALL-UNNAMED",
                "-Djava.library.path=" + natives.absolutePath,
            ) + jogl + listOf(
                "-cp", appJar.absolutePath,
                "org.konpeki.Main",
                "--bench",
                trainingSketch.absolutePath,
            ))
            environment("PCLI_BENCH", "1")
            environment("LOCALAPPDATA", trainingCache.absolutePath)
        }
    }
}

val release = if (os == "linux") {
    tasks.register<Tar>("release") {
        compression = Compression.GZIP
        archiveExtension = "tar.gz"
    }
} else {
    tasks.register<Zip>("release") { isZip64 = true }
}

release.configure {
    group = "distribution"
    dependsOn("packageApp")

    from(layout.buildDirectory.dir("app"))
    archiveBaseName = "ProcessingCLI"
    archiveVersion = ""
    archiveClassifier = platform
    destinationDirectory = layout.buildDirectory.dir("distributions")
    // Keep exec bits for the macOS and Linux launchers
    useFileSystemPermissions()

    doLast {
        val archive = archiveFile.get().asFile
        val sha = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        File("${archive.path}.sha256").writeText("$sha  ${archive.name}\n")
        println("Built ${archive.absolutePath}")
        println("Built ${archive.absolutePath}.sha256")
    }
}
