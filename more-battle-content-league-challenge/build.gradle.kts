plugins {
    kotlin("jvm")
    id("dev.architectury.loom")
}

version = property("more_battle_content_league_challenge_version")!!

base { archivesName.set("cobblemon-more-battle-content-league-challenge") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    // Same-workspace Loom project: compile current named output, not a cached remap of its old release JAR.
    implementation(project(path = ":more-battle-content", configuration = "namedElements")) { isTransitive = false }
    runtimeOnly(project(path = ":more-battle-content", configuration = "namedElements")) { isTransitive = false }
    implementation(project(path = ":cobblemon-ui-kit", configuration = "namedElements")) { isTransitive = false }
    runtimeOnly(project(path = ":cobblemon-ui-kit", configuration = "namedElements")) { isTransitive = false }
    // Provisional self-contained client delivery; Fabric deduplicates the nested mod across consumers.
    include(project(":cobblemon-ui-kit")) { isTransitive = false }
    modCompileOnly("com.cobblemon:mod:${property("cobblemon_maven_version")}") { isTransitive = false }
    modImplementation("com.cobblemon:fabric:${property("cobblemon_maven_version")}")
    // Runtime-only companions required by MBC; not bundled into the League release.
    modRuntimeOnly("maven.modrinth:cobblemon-mega-showdown:${property("mega_showdown_version_id")}")
    modRuntimeOnly("maven.modrinth:architectury-api:${property("architectury_api_version_id")}")
    modRuntimeOnly("maven.modrinth:accessories:${property("accessories_version_id")}")
    modImplementation("maven.modrinth:pokebadges:A93HZDyB")
    modImplementation("maven.modrinth:cobbled-level-control:uZaphEIC")
    modImplementation("maven.modrinth:matthiesen-core:azvkmoed")
    modImplementation("maven.modrinth:forge-config-api-port:N5qzq0XV")
    // Loom strips nested libraries from remapped Modrinth artifacts in development.
    runtimeOnly("com.electronwill.night-config:core:3.8.0")
    runtimeOnly("com.electronwill.night-config:toml:3.8.0")
    modCompileOnly("maven.modrinth:modmenu:6lgOkclV")
    // The retained development spike needs owo's injected vanilla widget interfaces during compilation.
    modImplementation("io.wispforest:owo-lib:${property("owo_lib_version")}")

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
    description = "Runs League Challenge JUnit tests without Gradle's broken Windows test worker path."
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
