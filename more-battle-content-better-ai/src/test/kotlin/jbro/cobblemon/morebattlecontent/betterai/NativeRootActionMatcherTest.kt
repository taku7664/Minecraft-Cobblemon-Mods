package jbro.cobblemon.morebattlecontent.betterai

import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleMechanicCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide
import jbro.cobblemon.morebattlecontent.api.ai.BattleTargetSlot
import jbro.cobblemon.morebattlecontent.betterai.simulation.NativeRootActionMatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeRootActionMatcherTest {
    @Test
    fun `namespaced moves and every mechanic alias map to native actions`() {
        val aliases = listOf(
            "megaevolution" to "mega",
            "dmax" to "dynamax",
            "terastallization" to "tera",
        )
        val product = aliases.mapIndexed { index, (productMechanic, _) ->
            move(
                actionId = "product:moonblast:$productMechanic",
                actorSlot = 0,
                moveSlot = index,
                moveId = "cobblemon:Moon-Blast",
                target = BattleTargetSlot(BattleSide.OPPONENT, 0),
                mechanic = productMechanic,
            )
        }
        val native = aliases.mapIndexed { index, (_, nativeMechanic) ->
            move(
                actionId = "native:moonblast:$nativeMechanic",
                actorSlot = 0,
                moveSlot = index,
                moveId = "moonblast",
                mechanic = nativeMechanic,
            )
        }

        val result = NativeRootActionMatcher.match(BattleFormat.SINGLE, product, native)

        assertTrue(result.complete)
        product.zip(native).forEach { (productAction, nativeAction) ->
            assertEquals(nativeAction.actionId, result.productToNative.getValue(productAction.actionId).actionId)
        }
    }

    @Test
    fun `same move maps when product and native sets place it in different slots`() {
        val product = move("product:moonblast", 0, 3, "cobblemon:moonblast")
        val native = move("native:moonblast", 0, 0, "moonblast")

        val result = NativeRootActionMatcher.match(BattleFormat.SINGLE, listOf(product), listOf(native))

        assertTrue(result.complete)
        assertEquals(native.actionId, result.productToNative.getValue(product.actionId).actionId)
    }

    @Test
    fun `double composites map by semantic slot action instead of component ids or order`() {
        val productLeft = move(
            "product:left",
            0,
            0,
            "thunderbolt",
            BattleTargetSlot(BattleSide.OPPONENT, 1),
        )
        val productRight = move(
            "product:right",
            1,
            1,
            "cobblemon:Moonblast",
            BattleTargetSlot(BattleSide.OPPONENT, 0),
        )
        val product = composite("product:joint", listOf(productRight, productLeft))
        val nativeLeft = move(
            "native:left",
            0,
            0,
            "thunderbolt",
            BattleTargetSlot(BattleSide.OPPONENT, 1),
        )
        val nativeRight = move(
            "native:right",
            1,
            1,
            "moonblast",
            BattleTargetSlot(BattleSide.OPPONENT, 0),
        )
        val native = composite("native:joint", listOf(nativeLeft, nativeRight))

        val result = NativeRootActionMatcher.match(BattleFormat.DOUBLE, listOf(product), listOf(native))

        assertTrue(result.complete)
        assertEquals(native.actionId, result.productToNative.getValue(product.actionId).actionId)
    }

    @Test
    fun `double target mismatch fails closed`() {
        val product = composite(
            "product:joint",
            listOf(
                move("product:left", 0, 0, "thunderbolt", BattleTargetSlot(BattleSide.OPPONENT, 1)),
                move("product:right", 1, 0, "moonblast", BattleTargetSlot(BattleSide.OPPONENT, 0)),
            ),
        )
        val wrongNativeTarget = composite(
            "native:joint",
            listOf(
                move("native:left", 0, 0, "thunderbolt", BattleTargetSlot(BattleSide.OPPONENT, 0)),
                move("native:right", 1, 0, "moonblast", BattleTargetSlot(BattleSide.OPPONENT, 0)),
            ),
        )

        val result = NativeRootActionMatcher.match(
            BattleFormat.DOUBLE,
            listOf(product),
            listOf(wrongNativeTarget),
        )

        assertFalse(result.complete)
        assertEquals(setOf(product.actionId), result.unmatchedProductActionIds)
        assertEquals(setOf(wrongNativeTarget.actionId), result.unmatchedNativeActionIds)
    }

    @Test
    fun `unmatched semantics fail closed`() {
        val productMove = move("product:move", 0, 0, "powergem")
        val nativeMove = move("native:move", 0, 0, "moonblast")

        val result = NativeRootActionMatcher.match(
            BattleFormat.SINGLE,
            listOf(productMove),
            listOf(nativeMove),
        )

        assertFalse(result.complete)
        assertEquals(setOf(productMove.actionId), result.unmatchedProductActionIds)
        assertEquals(setOf(nativeMove.actionId), result.unmatchedNativeActionIds)
        assertTrue(result.productToNative.isEmpty())
    }

    @Test
    fun `duplicate product semantics fail closed against one native action`() {
        val productOne = move("product:one", 0, 0, "moonblast")
        val productTwo = move("product:two", 0, 0, "moonblast")
        val nativeOne = move("native:one", 0, 0, "moonblast")

        val result = NativeRootActionMatcher.match(
            BattleFormat.SINGLE,
            listOf(productOne, productTwo),
            listOf(nativeOne),
        )

        assertFalse(result.complete)
        assertEquals(setOf(productOne.actionId, productTwo.actionId), result.ambiguousProductActionIds)
        assertTrue(result.productToNative.isEmpty())
    }

    @Test
    fun `duplicate native semantics fail closed against one product action`() {
        val product = move("product:one", 0, 0, "moonblast")
        val nativeOne = move("native:one", 0, 0, "moonblast")
        val nativeTwo = move("native:two", 0, 0, "moonblast")

        val result = NativeRootActionMatcher.match(
            BattleFormat.SINGLE,
            listOf(product),
            listOf(nativeOne, nativeTwo),
        )

        assertFalse(result.complete)
        assertEquals(setOf(product.actionId), result.ambiguousProductActionIds)
        assertEquals(setOf(nativeOne.actionId, nativeTwo.actionId), result.ambiguousNativeActionIds)
        assertTrue(result.productToNative.isEmpty())
    }

    @Test
    fun `switches map by actor slot and battle pokemon identity`() {
        val switchId = UUID.fromString("00000000-0000-0000-0000-000000000333")
        val product = BattleActionCandidate("product:switch", BattleActionKind.SWITCH, 0, switchPokemonId = switchId)
        val native = BattleActionCandidate("native:switch", BattleActionKind.SWITCH, 0, switchPokemonId = switchId)

        val result = NativeRootActionMatcher.match(BattleFormat.SINGLE, listOf(product), listOf(native))

        assertTrue(result.complete)
        assertEquals(native.actionId, result.productToNative.getValue(product.actionId).actionId)
    }

    private fun move(
        actionId: String,
        actorSlot: Int,
        moveSlot: Int,
        moveId: String,
        target: BattleTargetSlot? = null,
        mechanic: String? = null,
    ) = BattleActionCandidate(
        actionId = actionId,
        kind = BattleActionKind.USE_MOVE,
        actorSlot = actorSlot,
        moveSlot = moveSlot,
        moveId = moveId,
        targets = listOfNotNull(target),
        mechanic = mechanic?.let { BattleMechanicCandidate(it, null, null) },
    )

    private fun composite(actionId: String, components: List<BattleActionCandidate>) = BattleActionCandidate(
        actionId = actionId,
        kind = BattleActionKind.COMPOSITE,
        componentActionIds = components.map(BattleActionCandidate::actionId),
        componentActions = components,
    )
}
