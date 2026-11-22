plugins {
    id("net.fabricmc.fabric-loom") version "1.19.4"
    kotlin("jvm") version "2.4.21"
    `maven-publish`
}

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val fabricKotlinVersion = property("fabric_kotlin_version") as String

group = property("maven_group") as String
version = property("mod_version") as String
base { archivesName = property("archives_base_name") as String }

repositories {
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
    mavenCentral()
}

loom {
    runs {
        named("client") {
            // Development-only: `-Pvt.quickWorld=<saveName>` boots straight into a world so a
            // benchmark run is repeatable instead of depending on what the title screen does.
            providers.gradleProperty("vt.quickWorld").orNull?.let { world ->
                programArgs("--quickPlaySingleplayer", world)
            }
            // Development-only: `-Pvt.jfr=<file>` records a profiling flight so we can find hot
            // methods instead of guessing at them.
            providers.gradleProperty("vt.jfr").orNull?.let { file ->
                vmArgs(
                    "-XX:StartFlightRecording=filename=$file,dumponexit=true,settings=profile",
                    "-XX:+UnlockDiagnosticVMOptions",
                    "-XX:+DebugNonSafepoints"
                )
            }
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")

    // Kotlin language adapter + stdlib at runtime. Bundled into our jar so users do not have to
    // install it separately.
    implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")
    include("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    val resourceLoader = "net.fabricmc.fabric-api:fabric-resource-loader-v1:3.1.0+5af7744b5d"
    runtimeOnly(resourceLoader)
    include(resourceLoader)
    include("net.fabricmc.fabric-api:fabric-api-base:2.0.6+fcdff87f5d")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Explicit and stable: Minecraft 26.x targets Java 25.
        freeCompilerArgs.add("-Xjdk-release=25")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val modVersion = project.version
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

tasks.jar {
    val projectName = project.name
    inputs.property("projectName", projectName)
    from("LICENSE") {
        rename { "${it}_$projectName" }
    }
}
