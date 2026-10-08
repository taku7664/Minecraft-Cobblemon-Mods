package jbro.cobblemon.mcc.league.trainer

import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import jbro.cobblemon.mcc.league.MoreCobblemonContentsLeagueChallenge as Mod
import jbro.cobblemon.npc.api.NpcTalkChoice
import jbro.cobblemon.npc.api.NpcTalks
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.resources.ResourceManager

/** A quiz question: three [choices], [answer] the index of the right one, [source] the wiki page it comes from. */
data class WildQuizQuestion(val id: String, val question: String, val choices: List<String>, val answer: Int, val source: String) {
    init {
        require(id.matches(Regex("[a-z0-9_]+"))) { "Invalid question ID: $id" }
        require(question.isNotBlank()) { "$id has no question" }
        require(choices.size == 3 && choices.all { it.isNotBlank() } && choices.toSet().size == 3) { "$id needs three different choices" }
        require(answer in choices.indices) { "$id answer must be 0, 1 or 2" }
        require(source.isNotBlank()) { "$id has no source" }
    }
}

/** Reads `league-challenge/wild_quiz.json` (`docs/WILD_NPC_ROLES.md`). */
object WildQuizBankParser {
    fun parse(json: String): List<WildQuizQuestion> {
        val questions = JsonParser.parseString(json).asJsonObject.getAsJsonArray("questions")
            ?: throw IllegalArgumentException("The quiz bank has no questions")
        val bank = questions.mapIndexed { index, element ->
            try {
                read(element.asJsonObject)
            } catch (failure: RuntimeException) {
                throw IllegalArgumentException("Question ${index + 1}: ${failure.message}", failure)
            }
        }
        require(bank.isNotEmpty()) { "The quiz bank is empty" }
        require(bank.map { it.id }.toSet().size == bank.size) { "Two questions share an ID" }
        return bank
    }

    private fun read(root: JsonObject) = WildQuizQuestion(
        id = root.get("id").asString,
        question = root.get("question").asString,
        choices = root.getAsJsonArray("choices").map { it.asString },
        answer = root.get("answer").asInt,
        source = root.get("source").asString,
    )
}

/**
 * The quiz NPC. It keeps one question, picked the first time anyone accepts, and asks it of each player once: a
 * right answer wins a reward from the pool ([WildRewards]), a wrong one hears the answer. It stays where it is.
 */
internal object WildQuiz {
    private const val KEY = "${WildNpcRoles.KEY}.quiz"
    private const val QUESTION_TAG = "mcc_quiz_question:"

    @Volatile
    private var bank: Map<String, WildQuizQuestion> = emptyMap()

    fun talk(player: ServerPlayer, npc: NPCEntity) {
        if (WildNpcRoles.isDone(npc, player)) return WildTrainers.sayLine(player, npc, "$KEY.done")
        NpcTalks.open(player, WildTrainers.talk(npc, Component.translatable("$KEY.offer"), listOf(
            NpcTalkChoice(Component.translatable("$KEY.yes")) { ask(it, npc) },
            NpcTalkChoice(Component.translatable("$KEY.no")),
        )))
    }

    /** Asks the question with its choices in a new order each time, so the right one is not always in one place. */
    private fun ask(player: ServerPlayer, npc: NPCEntity) {
        val question = questionOf(npc) ?: return WildTrainers.sayLine(player, npc, "$KEY.confused")
        val choices = question.choices.indices.shuffled().map { index ->
            NpcTalkChoice(Component.literal(question.choices[index])) { answer(it, npc, question, index == question.answer) }
        }
        NpcTalks.open(player, WildTrainers.talk(npc, Component.literal(question.question), choices))
    }

    private fun answer(player: ServerPlayer, npc: NPCEntity, question: WildQuizQuestion, right: Boolean) {
        if (!npc.isAlive || WildNpcRoles.isDone(npc, player)) return
        WildNpcRoles.markDone(npc, player)
        if (!right) {
            return NpcTalks.open(player, WildTrainers.talk(npc,
                Component.translatable("$KEY.wrong", question.choices[question.answer])))
        }
        val reward = WildRewards.give(player, "wild_quiz_${question.id}")
        val lines = mutableListOf<Component>(Component.translatable("$KEY.right"))
        lines += if (reward == null) Component.translatable("$KEY.no_reward") else Component.translatable("$KEY.reward", reward)
        Mod.LOGGER.info("{} answered wild quiz {} right; reward {}", player.gameProfile.name, question.id, reward?.string)
        NpcTalks.open(player, WildTrainers.talk(npc, lines))
    }

    /** The question saved on [npc], or a new one saved now; null when the bank is empty. */
    private fun questionOf(npc: NPCEntity): WildQuizQuestion? {
        val saved = npc.tags.firstOrNull { it.startsWith(QUESTION_TAG) }
        saved?.removePrefix(QUESTION_TAG)?.let(bank::get)?.let { return it }
        // A saved question that left the bank is replaced.
        if (saved != null) npc.removeTag(saved)
        val question = bank.values.randomOrNull() ?: return null
        npc.addTag(QUESTION_TAG + question.id)
        return question
    }

    object Resources : SimpleSynchronousResourceReloadListener {
        private val file = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "league-challenge/wild_quiz.json")

        override fun getFabricId(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Mod.MOD_ID, "wild_quiz")

        override fun onResourceManagerReload(manager: ResourceManager) {
            try {
                val text = manager.getResource(file).orElse(null)?.openAsReader()?.use { it.readText() }
                if (text == null) {
                    Mod.LOGGER.warn("No wild quiz bank at {}", file)
                    return
                }
                bank = WildQuizBankParser.parse(text).associateBy { it.id }
                Mod.LOGGER.info("Loaded {} wild quiz questions", bank.size)
            } catch (failure: RuntimeException) {
                Mod.LOGGER.error("Wild quiz bank reload rejected; keeping the previous questions", failure)
            }
        }
    }
}
