plugins {
    kotlin("jvm")
    id("dev.architectury.loom")
}

version = property("more_cobblemon_contents_league_challenge_version")!!
group = "jbro.cobblemon.mcc"

base { archivesName.set("more-cobblemon-contents-league-challenge") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    implementation(project(path = ":more-cobblemon-contents", configuration = "namedElements")) { isTransitive = false }
    runtimeOnly(project(path = ":more-cobblemon-contents", configuration = "namedElements")) { isTransitive = false }
    // The core bundles the UI kit; League only compiles against it and runs it in development.
    compileOnly(project(path = ":cobblemon-ui", configuration = "namedElements")) { isTransitive = false }
    runtimeOnly(project(path = ":cobblemon-ui", configuration = "namedElements")) { isTransitive = false }
    testImplementation(project(path = ":cobblemon-ui", configuration = "namedElements")) { isTransitive = false }
    // Development runs only: the battle camera that closing scenes turn.
    runtimeOnly(project(path = ":better-cobblemon-battlecam", configuration = "namedElements")) { isTransitive = false }
    modCompileOnly("com.cobblemon:mod:${property("cobblemon_maven_version")}") { isTransitive = false }
    modImplementation("com.cobblemon:fabric:${property("cobblemon_maven_version")}")
    modImplementation("maven.modrinth:pokebadges:A93HZDyB")
    modImplementation("maven.modrinth:cobbled-level-control:uZaphEIC")
    modImplementation("maven.modrinth:matthiesen-core:azvkmoed")
    modImplementation("maven.modrinth:forge-config-api-port:N5qzq0XV")
    // Loom strips nested libraries from remapped Modrinth artifacts in development.
    runtimeOnly("com.electronwill.night-config:core:3.8.0")
    runtimeOnly("com.electronwill.night-config:toml:3.8.0")
    modCompileOnly("maven.modrinth:modmenu:6lgOkclV")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.11.4")
}

val modVersion = version.toString()

tasks.processResources {
    inputs.property("version", modVersion)
    // Editor tooling can drop state folders into src; they never belong in the jar.
    exclude("**/.omc/**")
    filesMatching("fabric.mod.json") { expand("version" to modVersion) }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

// The remap tasks inherit these notices from the original and sources JARs.
tasks.withType<org.gradle.jvm.tasks.Jar>().matching {
    it.name == "jar" || it.name == "sourcesJar"
}.configureEach {
    from(layout.projectDirectory.file("LICENSE")) {
        rename { "LICENSE_more_cobblemon_contents_league_challenge" }
    }
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
    // The hard League's data test reads Mega Showdown's items and which species each Mega Stone evolves.
    val megaShowdown = project.configurations.detachedConfiguration(
        project.dependencies.create("maven.modrinth:cobblemon-mega-showdown:${project.property("mega_showdown_version_id")}"),
    ).apply { isTransitive = false }
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dleague.megaShowdownJar=${megaShowdown.singleFile.absolutePath}") }
}

tasks.check { dependsOn(unitTest) }
