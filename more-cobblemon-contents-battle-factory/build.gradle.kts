plugins {
    kotlin("jvm")
    id("dev.architectury.loom")
}

version = property("more_cobblemon_contents_battle_factory_version")!!
group = "jbro.cobblemon.mcc"

base { archivesName.set("more-cobblemon-contents-battle-factory") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    implementation(project(path = ":more-cobblemon-contents", configuration = "namedElements")) { isTransitive = false }
    runtimeOnly(project(path = ":more-cobblemon-contents", configuration = "namedElements")) { isTransitive = false }
    // The core bundles the UI kit; content mods compile against its hub tab types.
    compileOnly(project(path = ":cobblemon-ui-kit", configuration = "namedElements")) { isTransitive = false }
    runtimeOnly(project(path = ":cobblemon-ui-kit", configuration = "namedElements")) { isTransitive = false }
    testImplementation(project(path = ":cobblemon-ui-kit", configuration = "namedElements")) { isTransitive = false }
    modCompileOnly("com.cobblemon:mod:${property("cobblemon_maven_version")}") {
        isTransitive = false
    }
    modImplementation("com.cobblemon:fabric:${property("cobblemon_maven_version")}")

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
