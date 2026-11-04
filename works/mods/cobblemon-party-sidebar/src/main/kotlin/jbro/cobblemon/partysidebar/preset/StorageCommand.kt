package jbro.cobblemon.partysidebar.preset

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Pokemon
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import jbro.cobblemon.toitem.api.PokemonItemData
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * `/partystorage <player> items|pokemon|presets`, for operators (level 2) answering support questions. Read-only: it
 * shows a player's item storage (and, when they are online, the storage items their party borrows), Pokemon storage
 * and presets, also for players who are offline. Pokemon UUIDs can be clicked to copy, to search the
 * `[pokemon-transfer]` and `[party-storage]` log lines.
 */
object StorageCommand {
    private enum class Section { ITEMS, POKEMON, PRESETS }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val player = Commands.argument("player", GameProfileArgument.gameProfile())
        Section.entries.forEach { section ->
            player.then(Commands.literal(section.name.lowercase()).executes { show(it, section) })
        }
        dispatcher.register(Commands.literal("partystorage").requires { it.hasPermission(2) }.then(player))
    }

    private fun show(context: CommandContext<CommandSourceStack>, section: Section): Int {
        val source = context.source
        for (profile in GameProfileArgument.getGameProfiles(context, "player")) {
            val data = PresetData.of(source.server).peek(profile.id)
            if (data == null) {
                source.sendSystemMessage(key("none", profile.name).withStyle(ChatFormatting.GRAY))
                continue
            }
            when (section) {
                Section.ITEMS -> items(source, profile.name, profile.id, data)
                Section.POKEMON -> pokemon(source, profile.name, data)
                Section.PRESETS -> presets(source, profile.name, data)
            }
        }
        return 1
    }

    private fun items(source: CommandSourceStack, name: String, id: UUID, data: PlayerPresets) {
        val used = data.storage.count { !it.isEmpty }
        source.sendSystemMessage(key("items.header", name, used, data.storage.size).withStyle(ChatFormatting.GOLD))
        data.storage.forEachIndexed { slot, stack ->
            if (!stack.isEmpty) source.sendSystemMessage(Component.literal("  [$slot] ").append(item(stack)))
        }
        val online = source.server.playerList.getPlayer(id)
        if (online == null) {
            source.sendSystemMessage(key("items.offline").withStyle(ChatFormatting.GRAY))
            return
        }
        val party = Cobblemon.storage.getParty(online)
        for (row in 0 until PresetData.PARTY_SIZE) {
            val pokemon = party.get(row) ?: continue
            val held = pokemon.heldItem()
            if (StoredMark.isStored(held)) {
                source.sendSystemMessage(key("items.lent", row + 1, pokemon.getDisplayName(), item(held)))
            }
        }
    }

    private fun pokemon(source: CommandSourceStack, name: String, data: PlayerPresets) {
        val used = data.pokemonStorage.count { !it.isEmpty }
        source.sendSystemMessage(key("pokemon.header", name, used, data.pokemonStorage.size).withStyle(ChatFormatting.GOLD))
        data.pokemonStorage.forEachIndexed { slot, stack ->
            if (stack.isEmpty) return@forEachIndexed
            val tag = PokemonItemData.pokemonTag(stack)
            val pokemon = tag?.let { runCatching { Pokemon.loadFromNBT(source.server.registryAccess(), it) }.getOrNull() }
            val line = Component.literal("  [$slot] ")
            if (pokemon == null) {
                source.sendSystemMessage(line.append(item(stack)).append(key("pokemon.unreadable").withStyle(ChatFormatting.RED)))
                return@forEachIndexed
            }
            line.append(pokemon.getDisplayName()).append(Component.literal(" Lv.${pokemon.level} "))
            if (!pokemon.heldItem().isEmpty) line.append(key("pokemon.held", item(pokemon.heldItem()))).append(" ")
            source.sendSystemMessage(line.append(uuid(pokemon.uuid)))
        }
    }

    private fun presets(source: CommandSourceStack, name: String, data: PlayerPresets) {
        source.sendSystemMessage(key("presets.header", name, data.presets.size).withStyle(ChatFormatting.GOLD))
        data.presets.forEachIndexed { index, preset ->
            val title = if (preset.name.isBlank()) Component.translatable("preset.cobblemon_party_sidebar.default", index + 1)
                else Component.literal(preset.name)
            val header = Component.literal("  ${index + 1}. ").append(title)
            if (index == data.active) header.append(key("presets.active").withStyle(ChatFormatting.GREEN))
            source.sendSystemMessage(header)
            preset.slots.forEachIndexed { row, slot ->
                val id = slot.pokemon ?: return@forEachIndexed
                val line = Component.literal("    ${row + 1}: ").append(speciesName(slot.species)).append(" ").append(uuid(id))
                if (!slot.reference.isEmpty) line.append(" ").append(key("presets.lends", item(slot.reference)))
                if (slot.lost) line.append(" ").append(key("presets.lost").withStyle(ChatFormatting.RED))
                source.sendSystemMessage(line)
            }
        }
    }

    private fun item(stack: ItemStack): Component =
        stack.displayName.copy().append(Component.literal(" x${stack.count}").withStyle(ChatFormatting.GRAY))

    private fun speciesName(species: String): Component =
        ResourceLocation.tryParse(species)?.let(PokemonSpecies::getByIdentifier)?.translatedName
            ?: Component.literal(species.ifBlank { "?" })

    private fun uuid(id: UUID): Component = Component.literal(id.toString()).withStyle { style ->
        style.withColor(ChatFormatting.DARK_GRAY)
            .withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id.toString()))
            .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.copy.click")))
    }

    private fun key(path: String, vararg args: Any): MutableComponent =
        Component.translatable("command.cobblemon_party_sidebar.storage.$path", *args)
}
