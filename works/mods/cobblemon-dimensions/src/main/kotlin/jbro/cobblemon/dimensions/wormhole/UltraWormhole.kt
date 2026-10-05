package jbro.cobblemon.dimensions.wormhole

import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * An Ultra Wormhole hanging in the sky: a glowing rift of radius [radius] that lasts [lifetime] ticks. Touching it
 * enters Ultra Space, or, for a [returning] hole inside Ultra Space, goes back home. Holes are never saved; one that is
 * unloaded is gone.
 */
class UltraWormhole(type: EntityType<*>, level: Level) : Entity(type, level) {
    var radius: Float
        get() = entityData.get(RADIUS)
        set(value) = entityData.set(RADIUS, value)
    var lifetime: Int
        get() = entityData.get(LIFETIME)
        set(value) = entityData.set(LIFETIME, value)
    var returning: Boolean
        get() = entityData.get(RETURNING)
        set(value) = entityData.set(RETURNING, value)

    init {
        noPhysics = true
        isNoGravity = true
    }

    /** The rift's middle; the entity stands at its bottom like any other. */
    val center: Vec3 get() = position().add(0.0, bbHeight / 2.0, 0.0)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(RADIUS, 2.5f)
        builder.define(LIFETIME, 1200)
        builder.define(RETURNING, false)
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide) {
            sparkle()
            return
        }
        if (tickCount > lifetime) {
            discard()
            return
        }
        if (tickCount % 60 == 0) {
            level().playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_AMBIENT, SoundSource.AMBIENT, 3f, 0.6f)
        }
        // Only while fully open: the first and last two seconds are the rift opening and closing.
        if (tickCount < 40 || tickCount > lifetime - 40) return
        val reach = radius * 0.8
        for (player in level().getEntitiesOfClass(ServerPlayer::class.java, AABB.ofSize(center, reach * 2.0, reach * 2.0, reach * 2.0))) {
            if (player.position().add(0.0, player.bbHeight / 2.0, 0.0).distanceTo(center) <= reach) {
                Wormholes.touch(player, this)
            }
        }
    }

    private fun sparkle() {
        if (random.nextFloat() > 0.6f) return
        val angle = random.nextDouble() * Math.PI * 2
        val r = radius * (1.1 + random.nextDouble() * 0.6)
        val from = center.add(Math.cos(angle) * r, (random.nextDouble() - 0.5) * r, Math.sin(angle) * r)
        // Drifting in toward the rift.
        val toward = center.subtract(from).scale(0.04)
        level().addParticle(if (random.nextBoolean()) ParticleTypes.END_ROD else ParticleTypes.REVERSE_PORTAL,
            from.x, from.y, from.z, toward.x, toward.y, toward.z)
    }

    override fun getBoundingBoxForCulling(): AABB = AABB.ofSize(center, radius * 4.0, radius * 4.0, radius * 4.0)

    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = distance < 256.0 * 256.0

    override fun isPickable(): Boolean = false

    override fun shouldBeSaved(): Boolean = false

    override fun readAdditionalSaveData(tag: CompoundTag) {
        radius = tag.getFloat("radius")
        lifetime = tag.getInt("lifetime")
        returning = tag.getBoolean("returning")
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        tag.putFloat("radius", radius)
        tag.putInt("lifetime", lifetime)
        tag.putBoolean("returning", returning)
    }

    companion object {
        private val RADIUS: EntityDataAccessor<Float> = SynchedEntityData.defineId(UltraWormhole::class.java, EntityDataSerializers.FLOAT)
        private val LIFETIME: EntityDataAccessor<Int> = SynchedEntityData.defineId(UltraWormhole::class.java, EntityDataSerializers.INT)
        private val RETURNING: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(UltraWormhole::class.java, EntityDataSerializers.BOOLEAN)
    }
}
