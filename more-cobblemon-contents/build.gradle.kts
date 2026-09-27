import java.util.UUID

plugins {
    kotlin("jvm")
    id("dev.architectury.loom")
}

version = property("more_cobblemon_contents_version")!!
group = "jbro.cobblemon.mcc"

base { archivesName.set("more-cobblemon-contents") }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    modCompileOnly("com.cobblemon:mod:${property("cobblemon_maven_version")}") {
        isTransitive = false
    }
    modImplementation("com.cobblemon:fabric:${property("cobblemon_maven_version")}")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(project(path = ":cobblemon-ui-kit", configuration = "namedElements")) { isTransitive = false }
    runtimeOnly(project(path = ":cobblemon-ui-kit", configuration = "namedElements")) { isTransitive = false }
    include(project(":cobblemon-ui-kit")) { isTransitive = false }
    // Cobblemon supplies GraalJS at runtime. Better AI compiles only against its public context API
    // so MCC does not package a second JavaScript engine into its own JAR.
    compileOnly("org.graalvm.sdk:graal-sdk:22.3.0")
    // Modrinth display versions are shared by Fabric and NeoForge releases.
    // Pin loader-specific version IDs there; use Wisp's Maven for owo so Endec transitives resolve.
    modRuntimeOnly("maven.modrinth:cobblemon-mega-showdown:${property("mega_showdown_version_id")}")
    modRuntimeOnly("io.wispforest:owo-lib:${property("owo_lib_version")}")
    modRuntimeOnly("maven.modrinth:architectury-api:${property("architectury_api_version_id")}")
    modRuntimeOnly("maven.modrinth:accessories:${property("accessories_version_id")}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.google.code.gson:gson:2.11.0")
    // MinecraftTestBootstrap hooks the launcher session.
    testImplementation("org.junit.platform:junit-platform-launcher:1.11.4")
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
    // Better AI tests are minutes of simulated battles. Split the suite while iterating:
    // ./gradlew :more-cobblemon-contents:unitTest -Pscope=core   (everything except Better AI)
    // ./gradlew :more-cobblemon-contents:unitTest -Pscope=ai     (Better AI only)
    when (project.findProperty("scope")) {
        "core" -> args("--exclude-package=jbro.cobblemon.mcc.betterai")
        "ai" -> args("--include-package=jbro.cobblemon.mcc.betterai")
        null -> Unit
        else -> throw GradleException("scope must be core or ai")
    }
    // Narrow a run to a few classes: ./gradlew :more-cobblemon-contents:unitTest -Ptests=LocalDoublesProjectionTest
    if (project.hasProperty("tests")) {
        args("--include-classname=.*(${project.property("tests")}).*")
    }
    // Parameter sweeps run hundreds of simulated battles to calibrate a weight. They are opt-in so a
    // normal verification run stays fast: ./gradlew :more-cobblemon-contents:unitTest -Psweeps
    systemProperty("betterai.sweeps", if (project.hasProperty("sweeps")) "true" else "false")
    systemProperty("betterai.oracle", project.hasProperty("oracle").toString())
}

tasks.check { dependsOn(unitTest) }


// Opt-in test-harness capture; never part of build/check or the shipped server JAR.
tasks.register<JavaExec>("captureBaseline") {
    group = "verification"
    description = "Records local self-play inputs and actual decisions, or validates and replays a capture."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.LocalBaselineCapture")
    workingDir(rootProject.projectDir)
    doFirst {
        val output = providers.gradleProperty("baselineOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-baseline/${UUID.randomUUID()}").get().asFile.absolutePath
        setArgs(listOf(rootProject.projectDir.absolutePath, output,
            providers.gradleProperty("baselineBattles").getOrElse("2"),
            providers.gradleProperty("baselineSeed").getOrElse("20260905"),
            providers.gradleProperty("baselineTurns").getOrElse("30"),
            providers.gradleProperty("baselineFormat").getOrElse("SINGLE"),
            providers.gradleProperty("baselineTier").getOrElse("STANDARD"),
            providers.gradleProperty("baselineReplay").getOrElse("")))
    }
}

tasks.register<JavaExec>("evaluatePaired") {
    group = "verification"
    description = "Evaluates side-swapped team pairs in separate tuning or held-out corpora."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.LocalPairedEvaluationCapture")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(rootProject.projectDir.absolutePath,
            providers.gradleProperty("evaluationOutput").orNull
                ?: layout.buildDirectory.dir("reports/betterai-paired/${UUID.randomUUID()}").get().asFile.absolutePath,
            providers.gradleProperty("evaluationPairs").getOrElse("10"),
            providers.gradleProperty("evaluationSeed").getOrElse("20260906"),
            providers.gradleProperty("evaluationTurns").getOrElse("30"),
            providers.gradleProperty("evaluationFormat").getOrElse("SINGLE"),
            providers.gradleProperty("evaluationTier").getOrElse("STANDARD"),
            providers.gradleProperty("evaluationSplit").getOrElse("TUNING"),
            providers.gradleProperty("evaluationChallenger").getOrElse("CURRENT"),
            providers.gradleProperty("evaluationDefender").getOrElse("CURRENT"),
            project.hasProperty("allowHoldout").toString()))
    }
}

tasks.register<JavaExec>("captureOracle") {
    group = "verification"
    description = "Runs the embedded Showdown scripted oracle (requires Node.js on PATH)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedShowdownOracle")
    doFirst {
        setArgs(listOf(providers.gradleProperty("oracleOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-oracle/${UUID.randomUUID()}").get().asFile.absolutePath))
    }
    workingDir(rootProject.projectDir)
}

tasks.register<JavaExec>("auditPresetOracle") {
    group = "verification"
    description = "Accounts for every raw Factory preset against embedded registry and Obtainable rules."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedPresetAudit")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("presetAuditOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-presets/${UUID.randomUUID()}").get().asFile.absolutePath,
            providers.gradleProperty("presetTeamPairs").orNull ?: "0",
            providers.gradleProperty("presetTeamSeed").orNull ?: "20260906"))
    }
}

tasks.register<JavaExec>("captureNativeTeams") {
    group = "verification"
    description = "Runs sampled complete teams with both Local Brains in embedded native singles."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedTeamBattle")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("nativeTeamOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-native-teams/${UUID.randomUUID()}").get().asFile.absolutePath,
            providers.gradleProperty("nativeTeamPairs").orNull ?: "1",
            providers.gradleProperty("nativeTeamSeed").orNull ?: "20260906"))
    }
}

tasks.register<JavaExec>("captureNativePairs") {
    group = "verification"
    description = "Runs both native seat orientations for each complete sampled team pair."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedNativePairs")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("nativePairOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-native-pairs/${UUID.randomUUID()}").get().asFile.absolutePath,
            providers.gradleProperty("nativeTeamPairs").orNull ?: "1",
            providers.gradleProperty("nativeTeamSeed").orNull ?: "20260906",
            providers.gradleProperty("nativePairSplit").orNull ?: "ALL"))
    }
}

tasks.register<JavaExec>("captureTournamentMirror") {
    group = "verification"
    description = "Runs the published 2025 WCoP SV OU team as a same-entry native Better AI mirror."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedTournamentMirrorBattle")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(
            providers.gradleProperty("tournamentMirrorOutput").orNull
                ?: layout.buildDirectory.dir("reports/betterai-tournament-mirror/${UUID.randomUUID()}").get().asFile.absolutePath,
            providers.gradleProperty("tournamentMirrorSeedsPerLead").getOrElse("2"),
            providers.gradleProperty("tournamentMirrorRepeats").getOrElse("2"),
            providers.gradleProperty("tournamentMirrorMaxTurns").getOrElse("300"),
        ))
    }
}

tasks.register<JavaExec>("captureBssTournamentMirror") {
    group = "verification"
    description = "Runs a published level-50 BSS entry as a bring-six-pick-three Better AI mirror."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedBssTournamentMirrorBattle")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(
            providers.gradleProperty("bssMirrorOutput").orNull
                ?: layout.buildDirectory.dir("reports/betterai-bss-mirror/${UUID.randomUUID()}").get().asFile.absolutePath,
            providers.gradleProperty("bssMirrorSeedsPerLead").getOrElse("2"),
            providers.gradleProperty("bssMirrorRepeats").getOrElse("1"),
            providers.gradleProperty("bssMirrorMaxTurns").getOrElse("100"),
        ))
    }
}

tasks.register<JavaExec>("captureBssFactoryTournamentMatch") {
    group = "verification"
    description = "Runs exact reconstructed entries and selections from a BSS Factory tournament replay."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedBssFactoryTournamentBattle")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(
            providers.gradleProperty("bssFactoryOutput").orNull
                ?: layout.buildDirectory.dir("reports/betterai-bss-factory/${UUID.randomUUID()}").get().asFile.absolutePath,
            providers.gradleProperty("bssFactorySeeds").getOrElse("4"),
            providers.gradleProperty("bssFactoryMaxTurns").getOrElse("200"),
        ))
    }
}

tasks.register<JavaExec>("compareTournamentDifficulties") {
    group = "verification"
    description = "Compares adjacent difficulty tiers with same-entry 3v3 BSS and 4v4 VGC tournament teams."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedTournamentDifficultyComparison")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(
            providers.gradleProperty("tournamentDifficultyOutput").orNull
                ?: layout.buildDirectory.dir("reports/betterai-tournament-difficulty/${UUID.randomUUID()}").get().asFile.absolutePath,
            providers.gradleProperty("tournamentDifficultySeeds").getOrElse("1"),
            providers.gradleProperty("tournamentDifficultyMaxTurns").getOrElse("120"),
        ))
    }
}

tasks.register<JavaExec>("compareNativePolicies") {
    group = "verification"
    description = "Compares existing Local Brain tunings with crossed teams and seats in native battles."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedPolicyComparison")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(rootProject.projectDir.absolutePath,
            providers.gradleProperty("nativePolicyOutput").orNull
                ?: layout.buildDirectory.dir("reports/betterai-native-policies/${UUID.randomUUID()}").get().asFile.absolutePath,
            providers.gradleProperty("nativeTeamPairs").orNull ?: "3",
            providers.gradleProperty("nativeTeamSeed").orNull ?: "20260906",
            providers.gradleProperty("nativeChallenger").orNull ?: "CURRENT",
            providers.gradleProperty("nativeDefender").orNull ?: "LEGACY",
            providers.gradleProperty("nativePairSplit").orNull ?: "TUNING",
            providers.gradleProperty("allowHoldout").orNull ?: "false",
            providers.gradleProperty("nativeMaxTurns").orNull ?: "200",
            providers.gradleProperty("nativeSkillLevel").orNull ?: "0"))
    }
}

tasks.register<JavaExec>("replayNativeFirstDecision") {
    group = "verification"
    description = "Replays a recorded first request through the real Brain and observes final candidate scores."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedFirstDecisionReplay")
    workingDir(rootProject.projectDir)
    doFirst {
        val snapshotIndex = providers.gradleProperty("replaySnapshotIndex").orNull
        val repetitions = providers.gradleProperty("replayRepetitions").orNull
        val depth = providers.gradleProperty("replayDepth").orNull
        val choiceSeed = providers.gradleProperty("replayChoiceSeed").orNull
        require(choiceSeed == null || (snapshotIndex != null && repetitions != null && depth != null)) {
            "Fixed choice seed diagnostics require explicit replaySnapshotIndex, replayRepetitions and replayDepth"
        }
        require(depth == null || (snapshotIndex != null && repetitions != null)) {
            "Depth diagnostics require explicit replaySnapshotIndex and replayRepetitions"
        }
        require(repetitions == null || snapshotIndex != null) {
            "Repeated snapshot diagnostics require an explicit replaySnapshotIndex"
        }
        setArgs(listOf(providers.gradleProperty("replayTrace").get(),
            providers.gradleProperty("replaySide").get(),
            providers.gradleProperty("replayBattleId").get(),
            providers.gradleProperty("nativeSkillLevel").get(),
            providers.gradleProperty("replayTuning").get()) +
            snapshotIndex?.let { listOf(it) }.orEmpty() + repetitions?.let { listOf(it) }.orEmpty() +
            depth?.let { listOf(it) }.orEmpty() + choiceSeed?.let { listOf(it) }.orEmpty())
    }
}

tasks.register<JavaExec>("replayDecisionSnapshot") {
    group = "verification"
    description = "Replays one decision saved by an AI test battle (logs/betterai-decisions/...)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.AiDecisionSnapshotReplay")
    doFirst {
        // -Psnapshot=<file> [-PreplayNative] ; legacy-only by default, which is exact for non-native turns.
        setArgs(listOf(providers.gradleProperty("snapshot").get()) +
            if (project.hasProperty("replayNative")) listOf("native") else emptyList())
    }
}

tasks.register<JavaExec>("compareDamageOracle") {
    group = "verification"
    description = "Compares base damage rolls and KO thresholds against embedded Showdown (requires Node.js)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedDamageDifferential")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("damageOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-damage/${UUID.randomUUID()}").get().asFile.absolutePath))
    }
}

tasks.register<JavaExec>("compareRootAllocation") {
    group = "verification"
    description = "Test-only uniform vs UCB root allocation probe; not a product search benchmark."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.PublicRootAllocationExperiment")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("allocationOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-allocation/${UUID.randomUUID()}").get().asFile.absolutePath))
    }
}

tasks.register<JavaExec>("compareLiveRootSearch") {
    group = "verification"
    description = "Measures live public projection sampling and recursive search costs without changing the AI."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.LiveRootSearchExperiment")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("liveRootOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-live-root/${UUID.randomUUID()}").get().asFile.absolutePath))
    }
}

tasks.register<JavaExec>("compareRootObjective") {
    group = "verification"
    description = "Verifies per-root recursive objective parity and scores live probe choices with a common referee."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.RootObjectiveExperiment")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("rootObjectiveOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-root-objective/${UUID.randomUUID()}").get().asFile.absolutePath))
    }
}

tasks.register<JavaExec>("compareRootDeepening") {
    group = "verification"
    description = "Compares root order with the same live recursive objective and a global reported-node budget."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.RootDeepeningExperiment")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("rootDeepeningOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-root-deepening/${UUID.randomUUID()}").get().asFile.absolutePath))
    }
}

tasks.register<JavaExec>("compareRootTactics") {
    group = "verification"
    description = "Runs the fixed public tactical grid through both recursive root schedules."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.RootDeepeningExperiment")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("rootTacticsOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-root-tactics/${UUID.randomUUID()}").get().asFile.absolutePath,
            "tactical"))
    }
}

tasks.register<JavaExec>("compareTurnOrder") {
    group = "verification"
    description = "Compares native lethal turn order and action cancellation with public projections."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("jbro.cobblemon.mcc.betterai.EmbeddedTurnOrderDifferential")
    workingDir(rootProject.projectDir)
    doFirst {
        setArgs(listOf(providers.gradleProperty("turnOrderOutput").orNull
            ?: layout.buildDirectory.dir("reports/betterai-turn-order/${UUID.randomUUID()}").get().asFile.absolutePath))
    }
}
