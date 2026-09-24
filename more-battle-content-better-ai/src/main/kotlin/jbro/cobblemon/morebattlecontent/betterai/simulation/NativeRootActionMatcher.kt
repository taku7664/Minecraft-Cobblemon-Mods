package jbro.cobblemon.morebattlecontent.betterai.simulation

import java.util.Locale
import java.util.UUID
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionCandidate
import jbro.cobblemon.morebattlecontent.api.ai.BattleActionKind
import jbro.cobblemon.morebattlecontent.api.ai.BattleFormat
import jbro.cobblemon.morebattlecontent.api.ai.BattleSide

/** Maps server candidate identities to equivalent actions from the native Showdown request. */
internal object NativeRootActionMatcher {
    fun match(
        format: BattleFormat,
        productActions: List<BattleActionCandidate>,
        nativeActions: List<BattleActionCandidate>,
    ): NativeRootActionMapping {
        require(productActions.map(BattleActionCandidate::actionId).distinct().size == productActions.size)
        require(nativeActions.map(BattleActionCandidate::actionId).distinct().size == nativeActions.size)
        val productSignatures = productActions.associateWith { signature(format, it) }
        val nativeSignatures = nativeActions.associateWith { signature(format, it) }
        val productBySignature = productSignatures.entries
            .filter { it.value != null }
            .groupBy({ requireNotNull(it.value) }, { it.key })
        val nativeBySignature = nativeSignatures.entries
            .filter { it.value != null }
            .groupBy({ requireNotNull(it.value) }, { it.key })
        val matches = linkedMapOf<String, BattleActionCandidate>()
        val unmatchedProduct = linkedSetOf<String>()
        val ambiguousProduct = linkedSetOf<String>()
        val ambiguousNative = linkedSetOf<String>()

        productActions.forEach { product ->
            val actionSignature = productSignatures.getValue(product)
            if (actionSignature == null) {
                unmatchedProduct += product.actionId
                return@forEach
            }
            val equivalentProducts = productBySignature.getValue(actionSignature)
            val equivalentNative = nativeBySignature[actionSignature].orEmpty()
            when {
                equivalentProducts.size > 1 || equivalentNative.size > 1 -> {
                    ambiguousProduct += equivalentProducts.map(BattleActionCandidate::actionId)
                    ambiguousNative += equivalentNative.map(BattleActionCandidate::actionId)
                }
                equivalentNative.size == 1 -> matches[product.actionId] = equivalentNative.single()
                else -> unmatchedProduct += product.actionId
            }
        }
        val usedNative = matches.values.mapTo(hashSetOf(), BattleActionCandidate::actionId)
        val unmatchedNative = nativeActions.asSequence()
            .map(BattleActionCandidate::actionId)
            .filterNot { it in usedNative || it in ambiguousNative }
            .toCollection(linkedSetOf())
        return NativeRootActionMapping(
            productToNative = matches,
            unmatchedProductActionIds = unmatchedProduct,
            unmatchedNativeActionIds = unmatchedNative,
            ambiguousProductActionIds = ambiguousProduct,
            ambiguousNativeActionIds = ambiguousNative,
        )
    }

    private fun signature(format: BattleFormat, action: BattleActionCandidate): ActionSignature? = when (action.kind) {
        BattleActionKind.COMPOSITE -> {
            if (action.componentActions.isEmpty()) return null
            val components = action.componentActions.map { component ->
                primitiveSignature(format, component) ?: return null
            }.sortedBy(PrimitiveSignature::sortKey)
            CompositeSignature(components)
        }
        else -> primitiveSignature(format, action)
    }

    private fun primitiveSignature(format: BattleFormat, action: BattleActionCandidate): PrimitiveSignature? {
        if (action.kind == BattleActionKind.COMPOSITE) return null
        val moveId = action.moveId?.let(::nativeId)
        return PrimitiveSignature(
            kind = action.kind,
            actorSlot = action.actorSlot,
            // Slot numbers encode each request's local move ordering. A normalized native
            // hypothesis may place the same move in a different slot than the live request, so
            // the stable move ID owns semantic matching whenever it is available.
            moveSlot = action.moveSlot.takeIf { moveId == null },
            moveId = moveId,
            targets = if (format == BattleFormat.SINGLE) emptyList() else {
                action.targets.map { TargetSignature(it.side, it.slot) }
                    .sortedWith(compareBy(TargetSignature::side, TargetSignature::slot))
            },
            switchPokemonId = action.switchPokemonId,
            mechanicId = action.mechanic?.mechanicId?.let(::canonicalMechanic),
        )
    }

    private fun canonicalMechanic(value: String): String = when (nativeId(value)) {
        "mega", "megaevolution" -> "mega"
        "dynamax", "dmax" -> "dynamax"
        "tera", "terastallize", "terastallization" -> "tera"
        else -> nativeId(value)
    }

    private fun nativeId(value: String): String = value.substringAfter(':')
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private sealed interface ActionSignature

    private data class CompositeSignature(val components: List<PrimitiveSignature>) : ActionSignature

    private data class PrimitiveSignature(
        val kind: BattleActionKind,
        val actorSlot: Int?,
        val moveSlot: Int?,
        val moveId: String?,
        val targets: List<TargetSignature>,
        val switchPokemonId: UUID?,
        val mechanicId: String?,
    ) : ActionSignature {
        fun sortKey(): String = listOf(
            actorSlot?.toString().orEmpty(),
            kind.name,
            moveSlot?.toString().orEmpty(),
            moveId.orEmpty(),
            switchPokemonId?.toString().orEmpty(),
            mechanicId.orEmpty(),
            targets.joinToString(",") { "${it.side.name}:${it.slot}" },
        ).joinToString("|")
    }

    private data class TargetSignature(val side: BattleSide, val slot: Int)
}

internal data class NativeRootActionMapping(
    val productToNative: Map<String, BattleActionCandidate>,
    val unmatchedProductActionIds: Set<String>,
    val unmatchedNativeActionIds: Set<String>,
    val ambiguousProductActionIds: Set<String>,
    val ambiguousNativeActionIds: Set<String>,
) {
    val complete: Boolean = unmatchedProductActionIds.isEmpty() &&
        unmatchedNativeActionIds.isEmpty() &&
        ambiguousProductActionIds.isEmpty() &&
        ambiguousNativeActionIds.isEmpty()
}
