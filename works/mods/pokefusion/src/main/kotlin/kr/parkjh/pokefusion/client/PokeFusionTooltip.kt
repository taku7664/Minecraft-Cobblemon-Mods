package kr.parkjh.pokefusion.client

import kr.parkjh.pokefusion.PokemonToItemFormat
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * PokemonToItem 아이템 툴팁에 크기와 우두머리 여부를 보여준다. PokemonToItem이 만든 설명에는 이 두 값이 없다.
 * 아이템 데이터는 읽기만 한다.
 */
object PokeFusionTooltip {
    fun register() {
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return@register
            @Suppress("DEPRECATION")
            val pokemon = PokemonToItemFormat.pokemonData(customData.unsafe) ?: return@register

            val added = mutableListOf<Component>()
            if (PokemonToItemFormat.isAlpha(pokemon)) {
                added += Component.translatable("tooltip.pokefusion.alpha").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
            }
            PokemonToItemFormat.scaleModifier(pokemon)?.let { scale ->
                added += Component.translatable("tooltip.pokefusion.size", String.format(Locale.ROOT, "%.2f", scale))
                    .withStyle(ChatFormatting.GOLD)
            }
            // 이름 바로 아래에 둬서 긴 설명 사이에 묻히지 않게 한다.
            if (added.isNotEmpty()) lines.addAll(minOf(1, lines.size), added)
        }
    }
}
