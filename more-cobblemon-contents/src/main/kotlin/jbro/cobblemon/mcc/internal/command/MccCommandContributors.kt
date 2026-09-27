package jbro.cobblemon.mcc.internal.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.commands.CommandSourceStack

/** Adds one subcommand under `/mcc`; contents register theirs while initializing, before commands are built. */
internal fun interface MccCommandContributor {
    fun build(): LiteralArgumentBuilder<CommandSourceStack>
}

internal object MccCommandContributors {
    private val contributors = CopyOnWriteArrayList<MccCommandContributor>()

    fun register(contributor: MccCommandContributor): AutoCloseable {
        contributors += contributor
        return AutoCloseable { contributors.remove(contributor) }
    }

    fun all(): List<MccCommandContributor> = contributors.toList()
}
