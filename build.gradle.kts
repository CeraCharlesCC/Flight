import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

val releaseVersion = Properties().apply {
    file("gradle.properties").reader().use { load(it) }
}.getProperty("version")
val releaseTagPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?")
providers.environmentVariable("RELEASE_TAG").orNull?.let { tag ->
    require(releaseTagPattern.matches(tag) && tag == releaseVersion && project.version.toString() == releaseVersion) {
        "Release tag '$tag' must exactly match version=$releaseVersion in gradle.properties (no v prefix)."
    }
}

val isJitPack = providers.environmentVariable("JITPACK").orNull == "true"
if (isJitPack) {
    val requestedVersion = providers.environmentVariable("VERSION").orNull
    require(!requestedVersion.isNullOrBlank()) { "JitPack must supply VERSION." }
    if (releaseTagPattern.matches(requestedVersion.removePrefix("v"))) {
        require(requestedVersion == releaseVersion) {
            "JitPack release '$requestedVersion' must exactly match version=$releaseVersion (no v prefix)."
        }
    }
    // Commit and branch snapshots retain their exact JitPack coordinates.
    version = requestedVersion
}

repositories {
    maven("https://m2.dv8tion.net/releases") {
        name = "m2-dv8tion"
    }
    mavenCentral()
    maven("https://jitpack.io") {
        name = "jitpack"
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.kotlin.reflect)
    implementation(libs.reflections)
    compileOnly(libs.jda)
    api(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.jda)
    testImplementation("net.sf.trove4j:trove4j:3.0.3")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

val gitRevision = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim() }
val artifactVersion = project.version.toString()

val writeVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/flight")
    inputs.property("version", artifactVersion)
    inputs.property("revision", gitRevision)
    outputs.dir(outputDir)

    doLast {
        val outputFile = outputDir.get().file("flight.txt").asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText("$artifactVersion\n${gitRevision.get()}\n")
    }
}

sourceSets.main {
    // Older builds wrote this file into src/main/resources; ignore that stale copy.
    resources.exclude { it.file == file("src/main/resources/flight.txt") }
    resources.srcDir(writeVersion)
}

// Keep shadowJar available, but publish the ordinary library and its dependencies.
components.named<AdhocComponentWithVariants>("java") {
    withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }
}

if (isJitPack) {
    // JitPack rewrites every variant URL to the main JAR, corrupting sources metadata.
    // Publish the POM and classified JARs there; GitHub Packages keeps Gradle metadata.
    tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }
}

publishing {
    publications {
        create<MavenPublication>("gpr") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "flight-jda6"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ceracharlescc/Flight")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: System.getenv("GITHUB_USER")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
