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

tasks.register<Jar>("fatJar") {
    group = "build"
    archiveClassifier = "all"
    manifest { attributes["Main-Class"] = "org.konpeki.Main" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.register<Exec>("packageApp") {
    group = "build"
    dependsOn("fatJar")
    val jar = tasks.named<Jar>("fatJar").get().archiveFile.get().asFile
    val out = layout.buildDirectory.dir("app").get().asFile

    val allModules = ModuleFinder.ofSystem().findAll()
        .map { it.descriptor().name() }
        .filterNot { it.startsWith("jdk.incubator") }
        .sorted()
        .joinToString(",")

    doFirst { delete(out) }
    commandLine(
        "${System.getProperty("java.home")}/bin/jpackage",
        "--type", "app-image",
        "--name", "ProcessingCLI",
        "--input", jar.parent,
        "--main-jar", jar.name,
        "--main-class", "org.konpeki.Main",
        "--add-modules", allModules,
        "--win-console",
        "--dest", out.absolutePath
    )
}
