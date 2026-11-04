plugins {
    kotlin("jvm")
    id("dev.architectury.loom")
}

version = property("cobblemon_party_sidebar_version")!!
group = "jbro.cobblemon"

base { archivesName.set("cobblemon-party-sidebar") }

repositories { maven("https://maven.terraformersmc.com/releases/") }

dependencies {
    implementation(project(path = ":cobblemon-to-item", configuration = "namedElements"))
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("com.cobblemon:fabric:${property("cobblemon_maven_version")}")
    modCompileOnly("com.terraformersmc:modmenu:11.0.3")
    // Optional: EMI keeps its side panels clear of the party column when it is installed.
    modCompileOnly("dev.emi:emi-fabric:1.1.24+1.21.1:api")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
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

loom {
    accessWidenerPath.set(file("src/main/resources/cobblemon_party_sidebar.accesswidener"))
}

// Run the same way as Cobblemon To Item's unit tests, on real Cobblemon stores.
val unitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.junit.platform.console.ConsoleLauncher")
    args("execute", "--fail-if-no-tests", "--details=tree")
    sourceSets.test.get().output.classesDirs.files.forEach { args("--scan-class-path=${it.absolutePath}") }
}

tasks.test { enabled = false }
tasks.check { dependsOn(unitTest) }
