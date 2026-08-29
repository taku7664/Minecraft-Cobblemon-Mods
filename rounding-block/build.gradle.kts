plugins {
    id("fabric-loom")
}

version = property("rounding_block_version")!!
group = "jbro.minecraft"

base { archivesName.set("rounding-block") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.11.4")
}

val modVersion = version.toString()

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") { expand("version" to modVersion) }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.test { enabled = false }

val unitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs Rounding-Block JUnit tests without the Gradle test worker."
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
