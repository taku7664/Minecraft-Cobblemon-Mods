package jbro.cobblemon.mcc.betterai.engine.sim

/**
 * Showdown's gen 5 linear congruential generator, bit for bit: `x' = a * x + c (mod 2^64)` with the top
 * 32 bits as the output. The same seed gives the same sequence as `sim/prng.js`, which is what lets the
 * engine be compared with Showdown line by line.
 */
class Prng private constructor(private var state: Long, val startingSeed: IntArray) {
    constructor(seed: IntArray) : this(pack(seed), seed.copyOf())

    val seed: IntArray get() = unpack(state)

    fun copy(): Prng = Prng(state, startingSeed.copyOf())

    /** When set, every roll is appended as "from,to=result @caller" (referee debugging only). */
    var trace: MutableList<String>? = null

    private fun record(from: Any?, to: Any?, result: Any) {
        val list = trace ?: return
        val caller = StackWalker.getInstance().walk { frames ->
            frames.map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                .filter { !it.startsWith("Prng.") && !it.startsWith("Battle.random") && !it.startsWith("Battle.sample") }
                .limit(2).toList().joinToString("<")
        }
        list += "$from,$to=$result @$caller"
    }

    private fun step(): Long {
        state = state * A + C
        return state ushr 32
    }

    /** `random()`: a real number in [0, 1). */
    fun next(): Double = (step().toDouble() / TWO_32).also { record(null, null, it) }

    /** `random(n)`: an integer in [0, n). */
    fun next(n: Int): Int {
        val result = step()
        val value = if (n == 0) (result.toDouble() / TWO_32).toInt() else ((result * n) ushr 32).toInt()
        record(n, null, value)
        return value
    }

    /** `random(m, n)`: an integer in [m, n). */
    fun next(from: Int, to: Int): Int {
        val result = step()
        val value = if (to == 0) (if (from == 0) 0 else ((result * from) ushr 32).toInt()) else ((result * (to - from)) ushr 32).toInt() + from
        record(from, to, value)
        return value
    }

    fun randomChance(numerator: Int, denominator: Int): Boolean = next(denominator) < numerator

    fun <T> sample(items: List<T>): T {
        require(items.isNotEmpty()) { "Cannot sample an empty array" }
        return items[next(items.size)]
    }

    fun <T> shuffle(items: MutableList<T>, start: Int = 0, end: Int = items.size) {
        var i = start
        while (i < end - 1) {
            val nextIndex = next(i, end)
            if (i != nextIndex) {
                val swap = items[i]
                items[i] = items[nextIndex]
                items[nextIndex] = swap
            }
            i++
        }
    }

    companion object {
        private const val A = 0x5D588B656C078965L
        private const val C = 0x00269EC3L
        private const val TWO_32 = 4294967296.0

        private fun pack(seed: IntArray): Long {
            require(seed.size == 4) { "A Showdown seed has four 16-bit parts" }
            return (seed[0].toLong() and 0xFFFF shl 48) or (seed[1].toLong() and 0xFFFF shl 32) or
                (seed[2].toLong() and 0xFFFF shl 16) or (seed[3].toLong() and 0xFFFF)
        }

        private fun unpack(state: Long): IntArray = intArrayOf(
            (state ushr 48 and 0xFFFF).toInt(), (state ushr 32 and 0xFFFF).toInt(),
            (state ushr 16 and 0xFFFF).toInt(), (state and 0xFFFF).toInt(),
        )
    }
}
