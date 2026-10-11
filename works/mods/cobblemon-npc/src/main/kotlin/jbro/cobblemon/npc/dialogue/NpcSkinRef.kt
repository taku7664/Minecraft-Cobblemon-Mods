package jbro.cobblemon.npc.dialogue

/**
 * What an NPC's skin field names: a player (`Steve`), an RCT Trainers+ trainer (`rct:clerk`, short for
 * `rctmod:textures/trainers/single/clerk.png`), any skin texture in a resource pack (`namespace:path.png`), or a
 * Cobblemon Pokemon (`pokemon:pikachu`, `pokemon:vulpix alolan shiny`, `pokemon:mymod:species`) followed by the
 * aspects that pick its form and texture.
 */
sealed interface NpcSkinRef {
    data object Default : NpcSkinRef

    data class Player(val name: String) : NpcSkinRef

    data class Texture(val namespace: String, val path: String) : NpcSkinRef {
        override fun toString() = "$namespace:$path"
    }

    /** A Pokemon species, `cobblemon` unless named otherwise, drawn with Cobblemon's model in place of a player. */
    data class Pokemon(val namespace: String, val path: String, val aspects: Set<String> = emptySet()) : NpcSkinRef {
        override fun toString() = buildString {
            append(NpcSkinRef.POKEMON_PREFIX)
            if (namespace != NpcSkinRef.COBBLEMON) append(namespace).append(':')
            append(path)
            aspects.forEach { append(' ').append(it) }
        }
    }

    companion object {
        const val POKEMON_PREFIX = "pokemon:"
        const val COBBLEMON = "cobblemon"
        const val RCT_PREFIX = "rct:"
        const val RCT_NAMESPACE = "rctmod"
        const val RCT_FOLDER = "textures/trainers/single"
        private val PLAYER = Regex("[A-Za-z0-9_]{1,16}")
        private val NAMESPACE = Regex("[a-z0-9_.-]+")
        private val PATH = Regex("[a-z0-9/._-]+\\.png")
        private val FILE = Regex("[a-z0-9_.-]+")
        private val ASPECT = Regex("[a-z0-9_.=-]+")

        fun isSkinFolder(path: String): Boolean = path.split('/').any {
            it in setOf("trainers", "npcs", "npc", "skins", "skin", "player", "players")
        }

        fun parse(value: String): NpcSkinRef {
            val text = value.trim()
            if (text.isEmpty()) return Default
            // A pack whose namespace is `pokemon` still names its textures by path.
            if (text.startsWith(POKEMON_PREFIX)) pokemon(text.removePrefix(POKEMON_PREFIX))?.let { return it }
            if (text.startsWith(RCT_PREFIX)) {
                val file = text.removePrefix(RCT_PREFIX).removeSuffix(".png")
                return if (FILE.matches(file)) Texture(RCT_NAMESPACE, "$RCT_FOLDER/$file.png") else Default
            }
            if (':' in text) {
                val namespace = text.substringBefore(':')
                val path = text.substringAfter(':')
                return if (NAMESPACE.matches(namespace) && PATH.matches(path) && ".." !in path) Texture(namespace, path) else Default
            }
            return if (PLAYER.matches(text)) Player(text) else Default
        }

        private fun pokemon(text: String): Pokemon? {
            val words = text.lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
            val species = words.firstOrNull() ?: return null
            val namespace = if (':' in species) species.substringBefore(':') else COBBLEMON
            val path = species.substringAfter(':')
            if (!NAMESPACE.matches(namespace) || !FILE.matches(path) || words.drop(1).any { !ASPECT.matches(it) }) return null
            return Pokemon(namespace, path, words.drop(1).toCollection(LinkedHashSet()))
        }

        /** The short form for a Cobblemon species, as the skin picker lists it. */
        fun pokemon(namespace: String, path: String) = Pokemon(namespace, path).toString()

        /** The short form for an RCT trainer texture file name (without `.png`). */
        fun rct(file: String) = "$RCT_PREFIX$file"
    }
}
