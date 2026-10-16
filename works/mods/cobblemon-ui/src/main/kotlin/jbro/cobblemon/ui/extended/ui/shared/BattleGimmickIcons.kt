package jbro.cobblemon.ui.extended.ui.shared

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation

/** A gimmick Cobblemon offers, by the id in its button's texture name, and the name shown on the tile. */
data class BattleGimmickIconSpec(
    val textureId: String,
    val labelKey: String,
)

/** A symbol lifted off a gimmick button: [pixels] (as [NativeImage] stores them) row by row, [width] by [height]. */
class BattleGimmickGlyph(val width: Int, val height: Int, val pixels: IntArray)

/**
 * The gimmick tiles' symbols, taken from Cobblemon's own `battle_gimmick_*.png` instead of new art. Each texture is
 * a 36x68 button, its top half the off state and its bottom half the on state, the symbol drawn on the button's face.
 * Only the symbol is kept: its shape is what differs from the off face's colours (read off the strip left of the
 * symbol, so no colour is written here), and its colours come from whichever state's symbol is the brighter, so it
 * reads on the battle theme's dark tiles. The tile itself shows the on state.
 */
object BattleGimmickIcons {
    const val TEXTURE_WIDTH = 36
    const val STATE_HEIGHT = 34
    const val TEXTURE_HEIGHT = STATE_HEIGHT * 2

    /** Columns at the button's left end that never hold the symbol: their colours are the face's. */
    private const val FACE_STRIP = 6

    @JvmField
    val SPECS: Map<String, BattleGimmickIconSpec> = listOf("mega", "max", "terastal", "zmove", "ultra")
        .map { BattleGimmickIconSpec(it, "cobblemon_ui.battle.gimmick.$it") }
        .associateBy(BattleGimmickIconSpec::textureId)

    @JvmStatic
    fun spec(textureId: String): BattleGimmickIconSpec? = SPECS[textureId]

    /**
     * The symbol of a whole button texture given as [pixels] ([TEXTURE_WIDTH] x [TEXTURE_HEIGHT], ABGR as
     * [NativeImage] holds them), cropped to its bounds and transparent around it; null if no symbol stands out.
     */
    @JvmStatic
    fun extract(pixels: IntArray): BattleGimmickGlyph? {
        require(pixels.size == TEXTURE_WIDTH * TEXTURE_HEIGHT) { "A gimmick texture is $TEXTURE_WIDTH x $TEXTURE_HEIGHT" }
        fun off(x: Int, y: Int) = pixels[y * TEXTURE_WIDTH + x]
        fun on(x: Int, y: Int) = pixels[(y + STATE_HEIGHT) * TEXTURE_WIDTH + x]
        val face = HashSet<Int>()
        for (y in 0 until STATE_HEIGHT) for (x in 0 until FACE_STRIP) face += off(x, y)
        val symbol = ArrayList<Pair<Int, Int>>()
        for (y in 0 until STATE_HEIGHT) for (x in 0 until TEXTURE_WIDTH) {
            val pixel = off(x, y)
            if (alpha(pixel) > 0 && pixel !in face) symbol += x to y
        }
        if (symbol.isEmpty()) return null
        val useOn = symbol.sumOf { (x, y) -> luminance(on(x, y)) } > symbol.sumOf { (x, y) -> luminance(off(x, y)) }
        val left = symbol.minOf { it.first }
        val top = symbol.minOf { it.second }
        val width = symbol.maxOf { it.first } - left + 1
        val height = symbol.maxOf { it.second } - top + 1
        val glyph = IntArray(width * height)
        for ((x, y) in symbol) glyph[(y - top) * width + (x - left)] = if (useOn) on(x, y) else off(x, y)
        return BattleGimmickGlyph(width, height, glyph)
    }

    private fun alpha(abgr: Int) = abgr ushr 24 and 0xFF

    private fun luminance(abgr: Int): Int {
        val red = abgr and 0xFF
        val green = abgr shr 8 and 0xFF
        val blue = abgr shr 16 and 0xFF
        return red * 299 + green * 587 + blue * 114
    }

    /** A drawable symbol: where its texture is registered and its size. */
    class Drawable(val texture: ResourceLocation, val width: Int, val height: Int)

    private val drawables = HashMap<String, Drawable?>()

    /** The symbol for [textureId] as a texture, lifted once from the loaded Cobblemon texture; null if it cannot be read. */
    @JvmStatic
    fun drawable(textureId: String): Drawable? = drawables.getOrPut(textureId) {
        val source = ResourceLocation.fromNamespaceAndPath("cobblemon", "textures/gui/battle/battle_gimmick_$textureId.png")
        val glyph = runCatching {
            Minecraft.getInstance().resourceManager.getResource(source).orElse(null)?.open()?.use { stream ->
                NativeImage.read(stream).use { image ->
                    if (image.width != TEXTURE_WIDTH || image.height != TEXTURE_HEIGHT) null
                    else extract(IntArray(TEXTURE_WIDTH * TEXTURE_HEIGHT) { image.getPixelRGBA(it % TEXTURE_WIDTH, it / TEXTURE_WIDTH) })
                }
            }
        }.getOrNull() ?: return@getOrPut null
        val image = NativeImage(glyph.width, glyph.height, true)
        for (y in 0 until glyph.height) for (x in 0 until glyph.width) image.setPixelRGBA(x, y, glyph.pixels[y * glyph.width + x])
        val location = ResourceLocation.fromNamespaceAndPath("cobblemon_ui", "gimmick_glyph/$textureId")
        Minecraft.getInstance().textureManager.register(location, DynamicTexture(image))
        Drawable(location, glyph.width, glyph.height)
    }
}
