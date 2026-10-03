package jbro.cobblemon.mcc.betterai.simulation

import jbro.cobblemon.mcc.internal.ai.PublicIds
import java.util.Locale
import java.util.UUID
import jbro.cobblemon.mcc.internal.ai.BattleActionCandidate
import jbro.cobblemon.mcc.internal.ai.BattleActionKind
import jbro.cobblemon.mcc.internal.ai.BattleFormat
import jbro.cobblemon.mcc.internal.ai.BattleSide

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
        // The engine may explore friendly attacks that the live product deliberately excludes.
        // Keep every other native omission (including healing and status support) a mismatch.
        val policyOmittedNative = if (format == BattleFormat.DOUBLE) {
            val productMoves = productActions.flatMap { it.componentActions.ifEmpty { listOf(it) } }
                .filter { it.kind == BattleActionKind.USE_MOVE }
            nativeActions.filter { action ->
                action.componentActions.ifEmpty { listOf(action) }.any { component ->
                    omittedFriendlyAttack(component, productMoves)
                }
            }.mapTo(hashSetOf(), BattleActionCandidate::actionId)
        } else emptySet()
        val unmatchedNative = nativeActions.asSequence()
            .map(BattleActionCandidate::actionId)
            .filterNot { it in usedNative || it in ambiguousNative || it in policyOmittedNative }
            .toCollection(linkedSetOf())
        return NativeRootActionMapping(
            productToNative = matches,
            unmatchedProductActionIds = unmatchedProduct,
            unmatchedNativeActionIds = unmatchedNative,
            ambiguousProductActionIds = ambiguousProduct,
            ambiguousNativeActionIds = ambiguousNative,
        )
    }

    private fun omittedFriendlyAttack(native: BattleActionCandidate, products: List<BattleActionCandidate>): Boolean {
        if (native.kind != BattleActionKind.USE_MOVE ||
            native.targets.singleOrNull()?.side != BattleSide.ALLY ||
            native.tags.none { it == "native_target_normal" || it == "native_target_any" }
        ) return false
        val moveId = native.moveId ?: return false
        val corresponding = products.filter {
            it.actorSlot == native.actorSlot &&
                it.moveId?.let(::nativeId) == native.moveId?.let(::nativeId) &&
                it.mechanic?.mechanicId?.let(NativeMechanicAllowance::canonical) ==
                    native.mechanic?.mechanicId?.let(NativeMechanicAllowance::canonical)
        }
        if (corresponding.isEmpty()) return false
        return corresponding.all { product ->
            !NativeProductAllyTargetPolicy.permits(native) { product.moveDetails?.damageCategory }
        }
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
            mechanicId = action.mechanic?.mechanicId?.let(NativeMechanicAllowance::canonical),
        )
    }

    private fun nativeId(value: String): String = PublicIds.canonical(value)

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
