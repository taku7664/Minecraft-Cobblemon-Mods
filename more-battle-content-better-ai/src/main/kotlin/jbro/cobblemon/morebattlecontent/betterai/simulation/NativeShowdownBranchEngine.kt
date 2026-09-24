package jbro.cobblemon.morebattlecontent.betterai.simulation

import com.google.gson.Gson
import java.io.IOException
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessMode
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryStream
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.util.Collections
import java.util.UUID
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.FileSystem

/**
 * A side-effect-free Showdown branch runner for the local Brain.
 *
 * It owns a Graal context separate from Cobblemon's live battle context. Callers can only create a
 * synthetic battle or branch an already synthetic snapshot; there is deliberately no live
 * [com.cobblemon.mod.common.api.battles.model.PokemonBattle] input.
 */
internal class NativeShowdownBranchEngine private constructor(
    private val context: Context,
    private val createBattleFunction: Value,
    private val branchFunction: Value,
    private val gson: Gson,
    val rulesFingerprint: String,
    private val ownedRulesGeneration: NativeRulesGeneration?,
) : AutoCloseable {
    fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame = decode(
        createBattleFunction.execute(gson.toJson(definition)).asString(),
    )

    fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame {
        require(snapshotJson.isNotBlank()) { "Native Showdown snapshot cannot be blank" }
        require(p1Choice.isNotBlank() && p2Choice.isNotBlank()) { "Both native choices are required" }
        return decode(
            branchFunction.execute(
                gson.toJson(NativeBranchRequest(snapshotJson, p1Choice, p2Choice)),
            ).asString(),
        )
    }

    private fun decode(json: String): NativeBattleFrame =
        gson.fromJson(json, NativeBattleFrame::class.java)

    override fun close() {
        try {
            context.close(true)
        } finally {
            ownedRulesGeneration?.close()
        }
    }

    companion object {
        fun open(engineRoot: Path): NativeShowdownBranchEngine {
            val rules = NativeRulesGeneration.capture(engineRoot)
            return try {
                open(engineRoot, rules, rules)
            } catch (failure: Throwable) {
                rules.close()
                throw failure
            }
        }

        fun open(engineRoot: Path, rules: NativeRulesGeneration): NativeShowdownBranchEngine =
            open(engineRoot, rules, null)

        private fun open(
            engineRoot: Path,
            rules: NativeRulesGeneration,
            ownedRulesGeneration: NativeRulesGeneration?,
        ): NativeShowdownBranchEngine {
            val sourceRoot = engineRoot.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS)
            require(sourceRoot == rules.sourceEngineRoot) {
                "Native rules generation belongs to a different Showdown directory"
            }
            val normalizedRoot = rules.engineRoot
            require(normalizedRoot.resolve("sim/battle.js").toFile().isFile) {
                "Showdown sim/battle.js is missing under $normalizedRoot"
            }
            val indexPath = normalizedRoot.resolve("index.js")
            require(indexPath.toFile().isFile) { "Showdown index.js is missing under $normalizedRoot" }
            val rootForJs = normalizedRoot.toString().replace('\\', '/')
            val rootLiteral = Gson().toJson(rootForJs)
            val fileSystem = ReadOnlyRootFileSystem(normalizedRoot)
            val context = Context.newBuilder("js")
                .allowIO(true)
                .fileSystem(fileSystem)
                .allowExperimentalOptions(true)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowCreateThread(false)
                .allowHostClassLoading(false)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .option("js.commonjs-require", "true")
                .option("js.commonjs-require-cwd", rootForJs)
                .option(
                    "js.commonjs-core-modules-replacements",
                    "buffer:buffer/,crypto:crypto-browserify,path:path-browserify",
                )
                .build()
            try {
                context.eval(
                    "js",
                    "globalThis.process = { cwd: function() { return $rootLiteral; } };",
                )
                context.eval(Source.newBuilder("js", indexPath.toFile()).build())
                val bridge = requireNotNull(
                    NativeShowdownBranchEngine::class.java.getResourceAsStream(
                        "/native-showdown/branch-engine.cjs",
                    ),
                ) { "Native Showdown bridge resource is missing" }.reader().use { it.readText() }
                context.eval(Source.newBuilder("js", bridge, "mbc-native-showdown-branch-engine.cjs").build())
                val bindings = context.getBindings("js")
                val create = bindings.getMember("mbcCreateBattle")
                val branch = bindings.getMember("mbcBranchBattle")
                val applyRules = bindings.getMember("mbcApplyRules")
                check(create?.canExecute() == true && branch?.canExecute() == true && applyRules?.canExecute() == true) {
                    "Native Showdown bridge did not export its branch functions"
                }
                applyRules.execute(Gson().toJson(rules.sources))
                return NativeShowdownBranchEngine(
                    context,
                    create,
                    branch,
                    Gson(),
                    rules.fingerprint,
                    ownedRulesGeneration,
                )
            } catch (failure: Throwable) {
                context.close(true)
                throw failure
            }
        }
    }
}

internal data class NativeBattleDefinition(
    val formatId: String,
    val seed: List<Int>,
    val p1Team: List<NativePokemonSet>,
    val p2Team: List<NativePokemonSet>,
) {
    init {
        require(formatId.isNotBlank())
        require(seed.size == 4) { "Showdown PRNG seed must contain four integers" }
        require(p1Team.size in 1..6 && p2Team.size in 1..6)
        require((p1Team + p2Team).map(NativePokemonSet::uuid).distinct().size == p1Team.size + p2Team.size) {
            "Native battle Pokemon UUIDs must be unique"
        }
    }
}

internal data class NativePokemonSet(
    val name: String,
    val species: String,
    val moves: List<String>,
    val ability: String,
    val uuid: String,
    val item: String = "",
    val nature: String = "Serious",
    val gender: String = "M",
    val level: Int = 50,
    val evs: Map<String, Int> = ZERO_EVS,
    val ivs: Map<String, Int> = PERFECT_IVS,
) {
    init {
        require(name.isNotBlank() && species.isNotBlank() && ability.isNotBlank() && uuid.isNotBlank())
        require(moves.isNotEmpty() && moves.size <= 4 && moves.all(String::isNotBlank))
        require(level in 1..100)
        UUID.fromString(uuid)
    }

    companion object {
        private val STATS = listOf("hp", "atk", "def", "spa", "spd", "spe")
        private val ZERO_EVS: Map<String, Int> = Collections.unmodifiableMap(STATS.associateWith { 0 })
        private val PERFECT_IVS: Map<String, Int> = Collections.unmodifiableMap(STATS.associateWith { 31 })
    }
}

internal data class NativeBattleFrame(
    val snapshotJson: String,
    val turn: Int,
    val requestState: String,
    val ended: Boolean,
    val p1Active: List<NativePokemonFrame>,
    val p2Active: List<NativePokemonFrame>,
    val log: List<String>,
)

internal data class NativePokemonFrame(
    val uuid: String,
    val species: String,
    val hp: Int,
    val maxHp: Int,
    val status: String,
    val ability: String,
    val item: String,
    val types: List<String>,
    val boosts: Map<String, Int>,
    val volatiles: List<String>,
    val moves: List<NativeMoveFrame>,
)

internal data class NativeMoveFrame(
    val id: String,
    val pp: Int,
    val maxPp: Int,
    val disabled: Boolean,
)

private data class NativeBranchRequest(
    val snapshotJson: String,
    val p1Choice: String,
    val p2Choice: String,
)

/** Graal CommonJS may read only the chosen, already-unbundled Showdown directory. */
private class ReadOnlyRootFileSystem(root: Path) : FileSystem {
    private val delegate = FileSystem.newDefaultFileSystem()
    private val root = root.toRealPath(LinkOption.NOFOLLOW_LINKS)

    override fun parsePath(uri: URI): Path = delegate.parsePath(uri)

    override fun parsePath(path: String): Path = delegate.parsePath(path)

    override fun checkAccess(path: Path, modes: MutableSet<out AccessMode>, vararg linkOptions: LinkOption) {
        val checked = checkedRealPath(path, *linkOptions)
        if (modes.any { it != AccessMode.READ }) throw AccessDeniedException(checked.toString())
        delegate.checkAccess(checked, modes, *linkOptions)
    }

    override fun newByteChannel(
        path: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel {
        if (options.any { it != StandardOpenOption.READ }) throw AccessDeniedException(path.toString())
        return delegate.newByteChannel(checkedRealPath(path), options, *attrs)
    }

    override fun newDirectoryStream(
        dir: Path,
        filter: DirectoryStream.Filter<in Path>,
    ): DirectoryStream<Path> = delegate.newDirectoryStream(checkedRealPath(dir), filter)

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption,
    ): Map<String, Any> = delegate.readAttributes(checkedRealPath(path, *options), attributes, *options)

    override fun toAbsolutePath(path: Path): Path = absolute(path)

    override fun toRealPath(path: Path, vararg linkOptions: LinkOption): Path = checkedRealPath(path, *linkOptions)

    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) = denied(dir)

    override fun delete(path: Path) = denied(path)

    private fun absolute(path: Path): Path = (if (path.isAbsolute) path else root.resolve(path)).normalize()

    private fun checkedRealPath(path: Path, vararg options: LinkOption): Path {
        val real = absolute(path).toRealPath(*options)
        if (!real.startsWith(root)) throw AccessDeniedException(real.toString())
        return real
    }

    private fun denied(path: Path): Nothing = throw AccessDeniedException(path.toString())
}
