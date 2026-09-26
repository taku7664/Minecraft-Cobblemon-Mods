package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

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

/** Fingerprint of the read-only installed engine and captured runtime rule sources. */
internal class NativeRulesGeneration private constructor(
    val fingerprint: String,
    internal val sourceEngineRoot: Path,
    internal val engineRoot: Path,
    sources: List<NativeRuleSource>,
) : AutoCloseable {
    internal val sources: List<NativeRuleSource> = sources.toList()

    // The installed Showdown tree is not owned by this generation. Closing a worker must never delete it.
    override fun close() = Unit

    fun verifySourceUnchanged() {
        capture(sourceEngineRoot, sources).use { current ->
            check(current.fingerprint == fingerprint) {
                "Showdown engine changed after capture; rebuild the native rules generation"
            }
        }
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
            Files.walk(normalizedRoot).use { paths ->
                paths.sorted(compareBy { normalizedRoot.relativize(it).toString().replace('\\', '/') })
                    .forEach { path ->
                        when {
                            Files.isDirectory(path, NOFOLLOW_LINKS) -> Unit
                            Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
                                // Source maps are debugger output, not executable rules. Hash only runtime files.
                                if (path.fileName.toString().endsWith(".map")) return@forEach
                                val relative = normalizedRoot.relativize(path).toString().replace('\\', '/')
                                update(digest, relative)
                                updateFile(digest, path)
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
            return NativeRulesGeneration(digest.digest().toHex(), normalizedRoot, normalizedRoot, orderedSources)
        }

        private fun update(digest: MessageDigest, value: String) = update(digest, value.toByteArray(UTF_8))

        private fun update(digest: MessageDigest, value: ByteArray) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
            digest.update(value)
        }

        private fun updateFile(digest: MessageDigest, path: Path) {
            val size = Files.size(path)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(size).array())
            Files.newInputStream(path).buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(read > 0) { "Showdown rule changed while hashing: $path" }
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
                check(input.read() == -1) { "Showdown rule changed while hashing: $path" }
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    }
}
