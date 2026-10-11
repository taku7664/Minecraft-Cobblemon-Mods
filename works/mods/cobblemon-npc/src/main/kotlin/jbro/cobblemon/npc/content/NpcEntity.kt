package jbro.cobblemon.npc.content

import jbro.cobblemon.npc.CobblemonNpc
import jbro.cobblemon.npc.dialogue.NpcSkinRef
import jbro.cobblemon.npc.server.DialogueSessions
import jbro.cobblemon.npc.server.NpcEditorService
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * A person standing in the world in a player's skin, or a Pokemon when its skin names one. It does not move, take
 * damage or despawn; a right click opens its dialogue, and a right click with the wand opens its settings for an
 * operator.
 */
class NpcEntity(type: EntityType<out NpcEntity>, level: Level) : PathfinderMob(type, level) {
    /** The dialogue a right click opens; blank for none. */
    var dialogueId: String = ""

    /** The player whose skin the NPC wears; blank for the default skin. */
    var skinName: String
        get() = entityData.get(SKIN)
        set(value) = entityData.set(SKIN, value)

    /** A Pokemon skin whose species data was not there yet, so its hitbox is sized again once it arrives. */
    private var hitboxPending = false

    init {
        setPersistenceRequired()
        isInvulnerable = true
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(SKIN, "")
    }

    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(key)
        if (key == SKIN) refreshDimensions()
    }

    // A Pokemon NPC is as large as a wild one of its form, so it is clicked and collides where its model stands.
    override fun getDefaultDimensions(pose: Pose): EntityDimensions {
        val pokemon = NpcSkinRef.parse(skinName) as? NpcSkinRef.Pokemon
        val dimensions = pokemon?.let(NpcPokemonForms::dimensions)
        hitboxPending = pokemon != null && dimensions == null
        return dimensions ?: super.getDefaultDimensions(pose)
    }

    override fun tick() {
        super.tick()
        // Species data reaches a client after the entities around it; size the hitbox once it is there.
        if (hitboxPending && tickCount % 20 == 0) refreshDimensions()
    }

    override fun registerGoals() {
        goalSelector.addGoal(1, LookAtPlayerGoal(this, Player::class.java, 8f))
        goalSelector.addGoal(2, RandomLookAroundGoal(this))
    }

    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS
        if (level().isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)
        if (held.`is`(CobblemonNpc.WAND)) {
            // Sneaking copies the NPC onto the wand, to place it again elsewhere; otherwise its settings open.
            if (player.isSecondaryUseActive && player.hasPermissions(CobblemonNpc.EDIT_PERMISSION)) {
                NpcWandItem.copy(held, this)
                player.displayClientMessage(Component.translatable("message.cobblemon_npc.copied", displayName()), true)
            } else {
                NpcEditorService.open(serverPlayer, this)
            }
            return InteractionResult.CONSUME
        }
        if (dialogueId.isBlank()) return InteractionResult.PASS
        lookAt(player, 180f, 180f)
        DialogueSessions.start(serverPlayer, dialogueId, speaker = displayName(), skin = skinName, npc = this)
        return InteractionResult.CONSUME
    }

    // While talking it watches its listener, over any glance around, so a camera on its face sees it look back.
    override fun customServerAiStep() {
        super.customServerAiStep()
        DialogueSessions.listener(level(), id)?.let { lookControl.setLookAt(it, 30f, 30f) }
    }

    fun displayName(): String = customName?.string.orEmpty()

    // Nothing kills an NPC, /kill included: a dead one cannot be brought back. The wand removes one; only the void
    // takes one that ended up below the world.
    override fun isInvulnerableTo(source: DamageSource) = !source.`is`(DamageTypes.FELL_OUT_OF_WORLD)

    override fun removeWhenFarAway(distance: Double) = false

    override fun isPushable() = false

    override fun doPush(entity: Entity) {}

    override fun canBeLeashed() = false

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        tag.putString("NpcSkin", skinName)
        tag.putString("NpcDialogue", dialogueId)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        skinName = tag.getString("NpcSkin")
        dialogueId = tag.getString("NpcDialogue")
    }

    companion object {
        private val SKIN: EntityDataAccessor<String> = SynchedEntityData.defineId(NpcEntity::class.java, EntityDataSerializers.STRING)

        fun createAttributes(): AttributeSupplier.Builder = Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.0)
            .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
    }
}
