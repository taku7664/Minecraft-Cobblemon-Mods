package jbro.cobblemon.mcc.betterai.engine.sim

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import jbro.cobblemon.mcc.betterai.engine.dex.Effect
import jbro.cobblemon.mcc.betterai.engine.dex.EngineDex

/**
 * Deep copies of a battle for search: every mutable object reachable from the battle is duplicated once
 * (shared references stay shared), while dex data, handlers and strings are shared with the original.
 *
 * Objects are allocated without running their constructors, so a copy never re-runs switch-ins, rolls the
 * PRNG, or logs anything. Field plans are computed once per class.
 */
object BattleCopier {
    private val unsafe: sun.misc.Unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").let {
        it.isAccessible = true
        it.get(null) as sun.misc.Unsafe
    }

    private val plans = HashMap<Class<*>, Array<Field>>()

    /** One instance field read and written through its raw offset; reflective access dominated forking. */
    private class Slot(val offset: Long, val kind: Char)

    private val slots = object : ClassValue<Array<Slot>>() {
        override fun computeValue(type: Class<*>): Array<Slot> = fieldsOf(type).map { field ->
            val kind = when (field.type) {
                Int::class.javaPrimitiveType -> 'I'
                Long::class.javaPrimitiveType -> 'J'
                Boolean::class.javaPrimitiveType -> 'Z'
                Double::class.javaPrimitiveType -> 'D'
                Float::class.javaPrimitiveType -> 'F'
                Byte::class.javaPrimitiveType -> 'B'
                Short::class.javaPrimitiveType -> 'S'
                Char::class.javaPrimitiveType -> 'C'
                else -> 'L'
            }
            Slot(unsafe.objectFieldOffset(field), kind)
        }.toTypedArray()
    }

    /** [shared] depends only on the class, so it is decided once per class. */
    private val sharedByClass = object : ClassValue<Boolean>() {
        override fun computeValue(type: Class<*>): Boolean = sharedClass(type)
    }

    /** Classes whose instances are immutable or owned by the dex: copied by reference. */
    private fun shared(value: Any): Boolean = sharedByClass.get(value.javaClass)

    private fun sharedClass(type: Class<*>): Boolean = when {
        String::class.java.isAssignableFrom(type) || Number::class.java.isAssignableFrom(type) ||
            type == java.lang.Boolean::class.java || type == java.lang.Character::class.java ||
            type == Unit::class.java || Enum::class.java.isAssignableFrom(type) -> true
        Effect::class.java.isAssignableFrom(type) || EngineDex::class.java.isAssignableFrom(type) ||
            BattleOptions::class.java.isAssignableFrom(type) || PokemonSet::class.java.isAssignableFrom(type) ||
            SecondaryHit::class.java.isAssignableFrom(type) || LiteralHit::class.java.isAssignableFrom(type) -> true
        Function::class.java.isAssignableFrom(type) -> true
        type == IntArray::class.java -> false
        else -> type.name.startsWith("kotlin.") && !Collection::class.java.isAssignableFrom(type) &&
            !Map::class.java.isAssignableFrom(type)
    }

    fun copy(battle: Battle, keepLog: Boolean = false): Battle {
        val seen = IdentityHashMap<Any, Any>()
        val copy = clone(battle, seen) as Battle
        if (!keepLog) {
            copy.log.clear()
            setField(copy, "logEnabled", false)
        }
        return copy
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = fieldsOf(target.javaClass).first { it.name == name }
        field.set(target, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun clone(value: Any?, seen: IdentityHashMap<Any, Any>): Any? {
        if (value == null) return null
        if (shared(value)) return value
        seen[value]?.let { return it }
        return when (value) {
            is IntArray -> value.copyOf().also { seen[value] = it }
            is DoubleArray -> value.copyOf().also { seen[value] = it }
            is Array<*> -> {
                val out = java.lang.reflect.Array.newInstance(value.javaClass.componentType, value.size) as Array<Any?>
                seen[value] = out
                for (i in value.indices) out[i] = clone(value[i], seen)
                out
            }
            is LinkedHashMap<*, *> -> LinkedHashMap<Any?, Any?>(value.size).also { out ->
                seen[value] = out
                for ((k, v) in value) out[clone(k, seen)] = clone(v, seen)
            }
            is HashMap<*, *> -> HashMap<Any?, Any?>(value.size).also { out ->
                seen[value] = out
                for ((k, v) in value) out[clone(k, seen)] = clone(v, seen)
            }
            is LinkedHashSet<*> -> LinkedHashSet<Any?>().also { out ->
                seen[value] = out
                for (v in value) out.add(clone(v, seen))
            }
            is HashSet<*> -> HashSet<Any?>().also { out ->
                seen[value] = out
                for (v in value) out.add(clone(v, seen))
            }
            is ArrayList<*> -> ArrayList<Any?>(value.size).also { out ->
                seen[value] = out
                for (v in value) out.add(clone(v, seen))
            }
            is List<*> -> ArrayList<Any?>(value.size).also { out ->
                // Immutable lists (listOf, map results) are copied as plain lists: they are only ever read.
                seen[value] = out
                for (v in value) out.add(clone(v, seen))
            }
            is Map<*, *> -> LinkedHashMap<Any?, Any?>(value.size).also { out ->
                seen[value] = out
                for ((k, v) in value) out[clone(k, seen)] = clone(v, seen)
            }
            else -> {
                val out = unsafe.allocateInstance(value.javaClass)
                seen[value] = out
                for (slot in slots.get(value.javaClass)) {
                    val o = slot.offset
                    when (slot.kind) {
                        'L' -> unsafe.putObject(out, o, clone(unsafe.getObject(value, o), seen))
                        'I' -> unsafe.putInt(out, o, unsafe.getInt(value, o))
                        'J' -> unsafe.putLong(out, o, unsafe.getLong(value, o))
                        'Z' -> unsafe.putBoolean(out, o, unsafe.getBoolean(value, o))
                        'D' -> unsafe.putDouble(out, o, unsafe.getDouble(value, o))
                        'F' -> unsafe.putFloat(out, o, unsafe.getFloat(value, o))
                        'B' -> unsafe.putByte(out, o, unsafe.getByte(value, o))
                        'S' -> unsafe.putShort(out, o, unsafe.getShort(value, o))
                        else -> unsafe.putChar(out, o, unsafe.getChar(value, o))
                    }
                }
                out
            }
        }
    }

    private fun fieldsOf(type: Class<*>): Array<Field> = synchronized(plans) {
        plans.getOrPut(type) {
            val fields = ArrayList<Field>()
            var c: Class<*>? = type
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    fields.add(f)
                }
                c = c.superclass
            }
            fields.toTypedArray()
        }
    }
}

/** An independent copy of this battle that can be played on without touching the original. */
fun Battle.fork(keepLog: Boolean = false): Battle = BattleCopier.copy(this, keepLog)
