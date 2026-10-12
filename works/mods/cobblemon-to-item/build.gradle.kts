plugins {
    kotlin("jvm")
    id("dev.architectury.loom")
}

version = property("cobblemon_to_item_version")!!
group = "jbro.cobblemon"
base { archivesName.set("cobblemon-to-item") }
repositories { maven("https://maven.terraformersmc.com/releases/") }
dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modImplementation("com.cobblemon:fabric:${property("cobblemon_maven_version")}")
    modCompileOnly("com.terraformersmc:modmenu:11.0.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.11.4")
}
tasks.processResources {
    inputs.property("version", version.toString())
    filesMatching("fabric.mod.json") { expand("version" to version.toString()) }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}
tasks.jar { from("LICENSE") { rename { "LICENSE_cobblemon-to-item" } } }
tasks.named<Jar>("sourcesJar") { from("LICENSE") { rename { "LICENSE_cobblemon-to-item" } } }
loom { accessWidenerPath.set(file("src/main/resources/cobblemon_to_item.accesswidener")) }
val unitTest by tasks.registering(JavaExec::class) {
    group = "verification"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.junit.platform.console.ConsoleLauncher")
    args("execute", "--fail-if-no-tests", "--details=summary")
    sourceSets.test.get().output.classesDirs.files.forEach { args("--scan-class-path=${it.absolutePath}") }
}
tasks.test { enabled = false }
tasks.check { dependsOn(unitTest) }
