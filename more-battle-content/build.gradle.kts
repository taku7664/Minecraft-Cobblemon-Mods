plugins {
    kotlin("jvm")
    id("fabric-loom")
}

version = property("more_battle_content_version")!!

base { archivesName.set("cobblemon-more-battle-content") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("maven.modrinth:cobblemon:${property("cobblemon_version_id")}")
    // Modrinth display versions are shared by Fabric and NeoForge releases.
    // Pin loader-specific version IDs because Modrinth Maven also omits transitive mod dependencies.
    modRuntimeOnly("maven.modrinth:cobblemon-mega-showdown:${property("mega_showdown_version_id")}")
    modRuntimeOnly("maven.modrinth:owo-lib:${property("owo_lib_version_id")}")
    modRuntimeOnly("maven.modrinth:architectury-api:${property("architectury_api_version_id")}")
    modRuntimeOnly("maven.modrinth:accessories:${property("accessories_version_id")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.11.4")
}

val modVersion = version.toString()

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") { expand("version" to modVersion) }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.test { enabled = false }

val unitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs JUnit tests without Gradle's broken Windows test worker path."
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
