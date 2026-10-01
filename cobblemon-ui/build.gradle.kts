plugins {
    id("dev.architectury.loom")
    kotlin("jvm")
}

version = property("cobblemon_ui_version")!!
group = "jbro.cobblemon"

base { archivesName.set("cobblemon-ui") }

repositories {
    maven("https://maven.terraformersmc.com/releases/")
    maven("https://maven.shedaniel.me/")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:1.21.1+build.3:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("maven.modrinth:cobblemon:${property("cobblemon_version_id")}")
    // Loom remaps mod dependencies while Gradle configures the build, so UI Kit's JARs must already exist. Without
    // them only Cobblemon UI is left unbuildable, with a warning, instead of every project failing to configure.
    // Build them first: gradlew --configure-on-demand :cobblemon-ui-kit:jar :cobblemon-ui-kit:remapJar
    val uiKitVersion = property("cobblemon_ui_kit_version")
    val uiKitDev = rootProject.file("cobblemon-ui-kit/build/devlibs/cobblemon-ui-kit-$uiKitVersion-dev.jar")
    // UI Kit is built with Mojang names; the dev client runs Yarn names, so it needs the remapped mod JAR.
    val uiKitRemapped = rootProject.file("cobblemon-ui-kit/build/libs/cobblemon-ui-kit-$uiKitVersion.jar")
    if (uiKitDev.exists()) {
        modCompileOnly(project(path = ":cobblemon-ui-kit", configuration = "namedElements")) { isTransitive = false }
    } else {
        logger.warn("Cobblemon UI cannot compile until UI Kit is built: missing ${uiKitDev.name}")
    }
    if (uiKitRemapped.exists()) {
        modRuntimeOnly(files(uiKitRemapped))
    } else {
        logger.warn("Cobblemon UI's dev client needs the remapped UI Kit: missing ${uiKitRemapped.name}")
    }
    testImplementation(project(path = ":cobblemon-ui-kit", configuration = "namedElements")) { isTransitive = false }
    // Modrinth metadata does not expose Cobblemon's development runtime libraries.
    // Versions match the official Cobblemon 1.8.1 Fabric POM; never bundle these in our JAR.
    runtimeOnly("org.graalvm.js:js:22.3.0")
    runtimeOnly("org.mongodb:mongodb-driver-sync:4.10.2")

    modCompileOnly("com.terraformersmc:modmenu:11.0.3")
    modCompileOnly("me.shedaniel.cloth:cloth-config-fabric:15.0.140")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.11.4")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

tasks.jar {
    from("THIRD_PARTY_LICENSE_CobblemonUi")
}

loom {
    accessWidenerPath.set(file("src/main/resources/cobblemon_ui.accesswidener"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

kotlin {
    jvmToolchain(21)
}

tasks.test { enabled = false }

val unitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs Cobblemon UI unit tests without the Gradle test worker."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.junit.platform.console.ConsoleLauncher")
    args("execute")
    sourceSets.test.get().output.classesDirs.files.forEach {
        args("--scan-class-path=${it.absolutePath}")
    }
    args("--fail-if-no-tests", "--details=summary")
}

tasks.check { dependsOn(unitTest) }
