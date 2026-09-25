package jbro.cobblemon.morebattlecontent.betterai.simulation

/** Gen 8+ shared HP display from Cobblemon's bundled Showdown `Pokemon.getHealth`. */
internal object NativeShowdownPublicHp {
    fun fraction(hp: Int, maxHp: Int): Double {
        require(maxHp > 0 && hp in 0..maxHp)
        if (hp == 0) return 0.0
        if (hp == maxHp) return 1.0
        // Showdown rounds upward but never displays a wounded Pokemon as 100/100.
        val percent = ((hp.toLong() * 100L + maxHp - 1L) / maxHp).coerceAtMost(99L)
        return percent / 100.0
    }
}
