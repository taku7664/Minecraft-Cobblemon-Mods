package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NativeRuleRegistry {
    ABILITY,
    MOVE,
    SCRIPT,
    CONDITION,
    HELD_ITEM,
    TYPE_CHART,
}

internal data class NativeRuleSource(
    val registry: NativeRuleRegistry,
    val id: String,
    val javaScript: String,
) {
    init {
        require(ID.matches(id)) { "Native rule ID must already be a Showdown ID" }
        require(javaScript.isNotBlank()) { "Native rule source cannot be blank" }
    }

    private companion object {
        val ID = Regex("[a-z0-9]+")
    }
}

/** Immutable engine-file plus runtime-source generation used by every branch in one worker. */
internal class NativeRulesGeneration private constructor(
    val fingerprint: String,
    internal val sourceEngineRoot: Path,
    internal val engineRoot: Path,
    sources: List<NativeRuleSource>,
) : AutoCloseable {
    internal val sources: List<NativeRuleSource> = sources.toList()
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) deleteSnapshot(engineRoot)
    }

    companion object {
        fun capture(engineRoot: Path, sources: List<NativeRuleSource> = emptyList()): NativeRulesGeneration {
            val normalizedRoot = engineRoot.toAbsolutePath().normalize().toRealPath()
            val orderedSources = sources.sortedWith(compareBy<NativeRuleSource> { it.registry }.thenBy { it.id })
            require(orderedSources.zipWithNext().none { (left, right) ->
                left.registry == right.registry && left.id == right.id
            }) { "A native rules generation cannot contain duplicate registry IDs" }
            val digest = MessageDigest.getInstance("SHA-256")
            update(digest, "mbc-native-rules-v1")
            val snapshotRoot = Files.createTempDirectory("mbc-native-showdown-").toAbsolutePath().normalize()
            try {
                Files.walk(normalizedRoot).use { paths ->
                    paths.sorted(compareBy { normalizedRoot.relativize(it).toString().replace('\\', '/') })
                        .forEach { path ->
                            val relative = normalizedRoot.relativize(path)
                            val target = snapshotRoot.resolve(relative.toString()).normalize()
                            check(target.startsWith(snapshotRoot)) { "Showdown snapshot path escaped its root" }
                            when {
                                Files.isDirectory(path, NOFOLLOW_LINKS) -> Files.createDirectories(target)
                                Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
                                    Files.createDirectories(target.parent)
                                    Files.copy(path, target)
                                    update(digest, relative.toString().replace('\\', '/'))
                                    update(digest, Files.readAllBytes(target))
                                }
                                else -> error("Showdown engine contains an unsupported file: $path")
                            }
                        }
                }
                orderedSources.forEach { source ->
                    update(digest, source.registry.name)
                    update(digest, source.id)
                    update(digest, source.javaScript)
                }
                return NativeRulesGeneration(
                    digest.digest().toHex(),
                    normalizedRoot,
                    snapshotRoot,
                    orderedSources,
                )
            } catch (failure: Throwable) {
                deleteSnapshot(snapshotRoot)
                throw failure
            }
        }

        private fun update(digest: MessageDigest, value: String) = update(digest, value.toByteArray(UTF_8))

        private fun update(digest: MessageDigest, value: ByteArray) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
            digest.update(value)
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun deleteSnapshot(root: Path) {
            check(root.fileName.toString().startsWith("mbc-native-showdown-")) {
                "Refusing to delete a non-native-rules directory: $root"
            }
            if (!Files.exists(root, NOFOLLOW_LINKS)) return
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
