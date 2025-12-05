import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
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

    testImplementation(libs.jda)
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

val buildVersionString: String by lazy {
    val gitHash = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }
        .standardOutput
        .asText
        .get()
        .trim()

    "${project.version}\n$gitHash"
}

val writeVersion by tasks.registering {
    val outputDir = sourceSets.main.get().resources.srcDirs.first()
    val outputFile = file("$outputDir/flight.txt")

    outputs.file(outputFile)
    outputs.upToDateWhen { false }

    doLast {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        outputFile.writeText(buildVersionString)
    }
}

tasks.named("processResources") {
    dependsOn(writeVersion)
}

tasks.named("sourcesJar") {
    dependsOn(writeVersion)
}

tasks.build {
    dependsOn(writeVersion)
}

tasks.named("shadowJar") {
    dependsOn(writeVersion)
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
