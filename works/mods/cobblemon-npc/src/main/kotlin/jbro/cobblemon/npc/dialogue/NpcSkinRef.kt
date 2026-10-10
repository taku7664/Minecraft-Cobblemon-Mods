package jbro.cobblemon.npc.dialogue

/**
 * What an NPC's skin field names: a player (`Steve`), an RCT Trainers+ trainer (`rct:clerk`, short for
 * `rctmod:textures/trainers/single/clerk.png`), or any skin texture in a resource pack (`namespace:path.png`).
 */
sealed interface NpcSkinRef {
    data object Default : NpcSkinRef

    data class Player(val name: String) : NpcSkinRef

    data class Texture(val namespace: String, val path: String) : NpcSkinRef {
        override fun toString() = "$namespace:$path"
    }

    companion object {
        const val RCT_PREFIX = "rct:"
        const val RCT_NAMESPACE = "rctmod"
        const val RCT_FOLDER = "textures/trainers/single"
        private val PLAYER = Regex("[A-Za-z0-9_]{1,16}")
        private val NAMESPACE = Regex("[a-z0-9_.-]+")
        private val PATH = Regex("[a-z0-9/._-]+\\.png")
        private val FILE = Regex("[a-z0-9_.-]+")

        fun isSkinFolder(path: String): Boolean = path.split('/').any {
            it in setOf("trainers", "npcs", "npc", "skins", "skin", "player", "players")
        }

        fun parse(value: String): NpcSkinRef {
            val text = value.trim()
            if (text.isEmpty()) return Default
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

        /** The short form for an RCT trainer texture file name (without `.png`). */
        fun rct(file: String) = "$RCT_PREFIX$file"
    }
}
