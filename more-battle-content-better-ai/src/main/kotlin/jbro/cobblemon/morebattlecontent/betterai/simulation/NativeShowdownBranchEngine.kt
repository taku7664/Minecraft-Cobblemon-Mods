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
import java.util.Locale
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
internal interface NativeBranchWorker : AutoCloseable {
    val rulesFingerprint: String

    fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame

    fun rebindMoves(snapshotJson: String, rebindings: List<NativeMoveSetRebinding>): NativeBattleFrame

    fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame
}

internal class NativeShowdownBranchEngine private constructor(
    private val context: Context,
    private val createBattleFunction: Value,
    private val rebindMovesFunction: Value,
    private val branchFunction: Value,
    private val gson: Gson,
    override val rulesFingerprint: String,
    private val ownedRulesGeneration: NativeRulesGeneration?,
) : NativeBranchWorker {
    override fun createBattle(definition: NativeBattleDefinition): NativeBattleFrame = decode(
        createBattleFunction.execute(gson.toJson(definition)).asString(),
    )

    override fun rebindMoves(
        snapshotJson: String,
        rebindings: List<NativeMoveSetRebinding>,
    ): NativeBattleFrame {
        require(snapshotJson.isNotBlank()) { "Native Showdown snapshot cannot be blank" }
        require(rebindings.isNotEmpty()) { "At least one native move-set rebinding is required" }
        return decode(
            rebindMovesFunction.execute(
                gson.toJson(NativeMoveSetRebindRequest(snapshotJson, rebindings)),
            ).asString(),
        )
    }

    override fun branch(snapshotJson: String, p1Choice: String, p2Choice: String): NativeBattleFrame {
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
                val rebindMoves = bindings.getMember("mbcRebindBattleMoves")
                val branch = bindings.getMember("mbcBranchBattle")
                val applyRules = bindings.getMember("mbcApplyRules")
                check(create?.canExecute() == true && rebindMoves?.canExecute() == true &&
                    branch?.canExecute() == true && applyRules?.canExecute() == true) {
                    "Native Showdown bridge did not export its branch functions"
                }
                applyRules.execute(Gson().toJson(rules.sources))
                return NativeShowdownBranchEngine(
                    context,
                    create,
                    rebindMoves,
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

/** Replaces only an unresolved synthetic source-set hypothesis, never a live public battle set. */
internal data class NativeMoveSetRebinding(
    val pokemonUuid: String,
    val expectedMoveIds: List<String>,
    val replacementMoveIds: List<String>,
) {
    init {
        UUID.fromString(pokemonUuid)
        require(expectedMoveIds.isNotEmpty() && expectedMoveIds.size <= 4)
        require(replacementMoveIds.isNotEmpty() && replacementMoveIds.size <= 4)
        require(expectedMoveIds.all(String::isNotBlank) && replacementMoveIds.all(String::isNotBlank))
        require(expectedMoveIds.distinct().size == expectedMoveIds.size)
        require(replacementMoveIds.distinct().size == replacementMoveIds.size)
        require(expectedMoveIds != replacementMoveIds) { "A native move-set rebinding must change the set" }
    }
}

internal data class NativeBattleDefinition(
    val formatId: String,
    val seed: List<Int>,
    val p1Team: List<NativePokemonSet>,
    val p2Team: List<NativePokemonSet>,
    val openingState: NativeBattleOpeningState? = null,
) {
    init {
        require(formatId.isNotBlank())
        require(seed.size == 4) { "Showdown PRNG seed must contain four integers" }
        require(p1Team.size in 1..6 && p2Team.size in 1..6)
        require((p1Team + p2Team).map(NativePokemonSet::uuid).distinct().size == p1Team.size + p2Team.size) {
            "Native battle Pokemon UUIDs must be unique"
        }
        openingState?.let { state ->
            val setsByUuid = (p1Team + p2Team).associateBy(NativePokemonSet::uuid)
            require(state.pokemon.map(NativePokemonOpeningState::uuid).toSet() == setsByUuid.keys) {
                "A native public opening state must describe every Pokemon in the synthetic battle exactly once"
            }
            state.pokemon.forEach { seeded ->
                val set = setsByUuid.getValue(seeded.uuid)
                require(nativeId(set.ability) == nativeId(seeded.ability) &&
                    nativeId(set.item) == nativeId(seeded.item)) {
                    "Native opening ability and item must come from the same public hypothesis as the team set"
                }
            }
        }
    }

    private companion object {
        fun nativeId(value: String): String = value.substringAfter(':')
            .lowercase(Locale.ROOT)
            .filter(Char::isLetterOrDigit)
    }
}

/**
 * Public or hypothesis-owned pre-battle state, never a sanitized live Showdown snapshot.
 *
 * Arbitrary mid-battle reconstruction is deliberately rejected: public HP and boosts alone do not
 * recover active-turn counters, move locks, volatile state or other history-sensitive native fields.
 * The schema therefore has no turn, boost or volatile fields. Showdown applies switch-in callbacks
 * and creates the first move request after this state is installed. Later turns must descend from
 * that synthetic native root until a full public reconciliation contract exists.
 */
internal data class NativeBattleOpeningState(
    val pokemon: List<NativePokemonOpeningState>,
) {
    init {
        require(pokemon.isNotEmpty())
        require(pokemon.map(NativePokemonOpeningState::uuid).distinct().size == pokemon.size)
    }
}

internal data class NativePokemonOpeningState(
    val uuid: String,
    val hp: Int,
    val maxHp: Int,
    val ability: String,
    val item: String = "",
    val status: String = "",
) {
    init {
        UUID.fromString(uuid)
        require(maxHp > 0 && hp in 0..maxHp)
        require(ability.isNotBlank())
        require(status in SIMPLE_OPENING_STATUSES) {
            "Opening sleep and toxic poison require hidden counters and cannot be reconstructed from a status name"
        }
    }

    private companion object {
        val SIMPLE_OPENING_STATUSES = setOf("", "brn", "par", "psn", "frz")
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
    val teraType: String? = null,
    val level: Int = 50,
    val evs: Map<String, Int> = ZERO_EVS,
    val ivs: Map<String, Int> = PERFECT_IVS,
) {
    init {
        require(name.isNotBlank() && species.isNotBlank() && ability.isNotBlank() && uuid.isNotBlank())
        require(moves.isNotEmpty() && moves.size <= 4 && moves.all(String::isNotBlank))
        require(teraType == null || teraType.isNotBlank())
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
    val p1Team: List<NativePokemonFrame>,
    val p2Team: List<NativePokemonFrame>,
    val p1RequestJson: String,
    val p2RequestJson: String,
    val field: NativeBattleFieldFrame = NativeBattleFieldFrame.empty(),
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
    val activeSlot: Int?,
    val level: Int = 50,
    val stats: Map<String, Int> = emptyMap(),
    val sourceSet: NativePokemonSourceSetFrame? = null,
    val baseStabTypes: List<String> = types,
    val terastallizedType: String = "",
)

/** Immutable team-set identity, kept separate from callback-mutated live Pokemon state. */
internal data class NativePokemonSourceSetFrame(
    val species: String,
    val ability: String,
    val item: String,
    val moves: List<String>,
    val nature: String = "",
    val gender: String = "",
    val evs: Map<String, Int> = emptyMap(),
    val ivs: Map<String, Int> = emptyMap(),
    val teraType: String = "",
    val openingHp: Int? = null,
    val openingMaxHp: Int? = null,
    val openingStatus: String = "",
)

internal data class NativeMoveFrame(
    val id: String,
    val pp: Int,
    val maxPp: Int,
    val disabled: Boolean,
)

internal data class NativeBattleFieldFrame(
    val weather: NativeTimedEffectFrame?,
    val terrain: NativeTimedEffectFrame?,
    val pseudoWeather: List<NativeTimedEffectFrame>,
    val p1SideConditions: List<NativeTimedEffectFrame>,
    val p2SideConditions: List<NativeTimedEffectFrame>,
) {
    companion object {
        fun empty() = NativeBattleFieldFrame(null, null, emptyList(), emptyList(), emptyList())
    }
}

internal data class NativeTimedEffectFrame(
    val id: String,
    val remainingTurns: Int?,
    val stacks: Int?,
)

private data class NativeBranchRequest(
    val snapshotJson: String,
    val p1Choice: String,
    val p2Choice: String,
)

private data class NativeMoveSetRebindRequest(
    val snapshotJson: String,
    val rebindings: List<NativeMoveSetRebinding>,
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
