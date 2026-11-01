package jbro.cobblemon.mcc.pwt.system

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.netty.buffer.Unpooled
import java.io.File
import java.net.JarURLConnection
import java.util.UUID
import jbro.cobblemon.mcc.api.presentation.TrainerResourceSkin
import jbro.cobblemon.mcc.internal.battle.LegendaryClassPolicy
import jbro.cobblemon.mcc.pwt.client.PwtBracketLayout
import jbro.cobblemon.mcc.pwt.network.*
import jbro.cobblemon.mcc.pwt.server.PwtSavedData
import jbro.cobblemon.uikit.UiRect
import kotlin.random.Random
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Forged, damaged and misspelled input against the tournament's rules, parser, packets and saved data. */
class PwtHostileInputTest {
    private val resources = File("src/main/resources")
    private val trainerDir = File(resources, "data/more_cobblemon_contents_pwt/pwt/trainers")
    private val roster = (1..17).map { "pwt:t$it" }

    private fun props(line: String) = line.split(' ').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }

    // ---- bracket state machine ----

    @Test
    fun `fuzz every path through the bracket`() {
        val problems = ArrayList<String>()
        for (seed in 0 until 4000) {
            val r = Random(seed)
            var b = PwtBracket.draw(roster, r)
            val met = HashSet<Int>()
            var steps = 0
            while (!b.finished) {
                val opp = b.opponentSlot()
                if (opp == null || opp == b.playerSlot) { problems += "seed $seed bad opponent $opp"; break }
                if (!met.add(opp)) problems += "seed $seed met $opp twice"
                if (opp !in b.alive(b.round)) problems += "seed $seed opponent $opp not alive"
                val win = r.nextInt(4) != 0
                val before = b.round
                b = b.settle(win, r)
                if (b.round != before + 1) problems += "seed $seed round did not advance"
                if (win != (b.playerSlot in b.winners.last())) problems += "seed $seed result not applied"
                if (b.winners.last().toSet().size != b.winners.last().size) problems += "seed $seed repeated winner"
                steps++
            }
            if (steps !in 1..3) problems += "seed $seed took $steps rounds"
            if (b.playerWon != (steps == 3 && b.playerSlot in b.winners.last())) problems += "seed $seed playerWon wrong"
            if (b.opponentSlot() != null) problems += "seed $seed still has an opponent"
            // A rebuilt bracket passes its own validation.
            runCatching { PwtBracket(b.entrants, b.winners) }.onFailure { problems += "seed $seed rebuild: ${it.message}" }
        }
        assertEquals(emptyList<String>(), problems.take(10))
    }

    @Test
    fun `the player is seated at every slot and meets the right neighbour`() {
        val seen = HashSet<Int>()
        repeat(2000) { seen += PwtBracket.draw(roster, Random(it)).playerSlot }
        assertEquals((0 until 8).toSet(), seen)
    }

    @Test
    fun `forged brackets are refused`() {
        val e = listOf(PwtBracket.PLAYER) + (1..7).map { "pwt:t$it" }
        val forged = listOf(
            e to listOf(listOf(0, 2, 4, 6), listOf(0, 0)),
            e to listOf(listOf(0, 2, 4, 6), listOf(1, 4)),
            e to listOf(listOf(0, 2, 4, 6), listOf(0, 4), listOf(2)),
            e to listOf(listOf(0, 2, 4, 6), listOf(0, 4), listOf(0), listOf(0)),
            (e + "pwt:t8") to emptyList(),
            (e.dropLast(1) + PwtBracket.PLAYER) to emptyList(),
            (e.dropLast(1) + "pwt:t1") to emptyList(),
        )
        forged.forEachIndexed { i, (entrants, winners) ->
            assertThrows(IllegalArgumentException::class.java, { PwtBracket(entrants, winners) }, "forged #$i accepted")
        }
    }

    @Test
    fun `draw with hostile rosters`() {
        assertThrows(IllegalArgumentException::class.java) { PwtBracket.draw(roster.take(6) + roster.take(6), Random(1)) }
        assertThrows(IllegalArgumentException::class.java) { PwtBracket.draw(roster + PwtBracket.PLAYER, Random(1)) }
        assertEquals(7, PwtBracket.draw(roster.take(7), Random(1)).entrants.count { it != PwtBracket.PLAYER })
    }

    // ---- entry rules against an independent oracle ----

    @Test
    fun `fuzz entry rules against an independent oracle`() {
        val r = Random(99)
        val problems = ArrayList<String>()
        repeat(20000) {
            val party = List(r.nextInt(0, 7)) { n ->
                PwtRules.Candidate(UUID(0, n.toLong()), "c:s${r.nextInt(5)}", if (r.nextBoolean()) "c:i${r.nextInt(5)}" else null, r.nextInt(6) == 0)
            }
            val picked = List(r.nextInt(0, 7)) { UUID(0, r.nextInt(0, 8).toLong()) }
            val members = picked.mapNotNull { id -> party.firstOrNull { it.id == id } }
            val legal = picked.size == 3 && picked.toSet().size == 3 && members.size == 3 && members.none { it.legendaryClass } &&
                members.map { it.speciesId }.toSet().size == 3 && members.mapNotNull { it.heldItemId }.let { it.toSet().size == it.size }
            val verdict = PwtRules.violations(party, picked)
            if (legal != verdict.isEmpty()) problems += "party=$party picked=$picked verdict=$verdict"
        }
        assertEquals(emptyList<String>(), problems.take(3))
    }

    // ---- hostile datapack ----

    private fun trainerJson(pool: List<String>, skin: String = "pwt:textures/a.png", ace: String? = null, gen: Int = 1) = JsonObject().apply {
        addProperty("schema_version", 1); addProperty("name_key", "k"); addProperty("generation", gen); addProperty("skin", skin)
        addProperty("ace", ace ?: pool.first().substringBefore(' '))
        add("pokemon", com.google.gson.JsonArray().also { a -> pool.forEach(a::add) })
    }.toString()

    private fun line(species: String, item: String? = null, extra: String = "") =
        "$species level=50 moves=tackle ${item?.let { "held_item=$it " } ?: ""}$extra".trim()

    private val legalPool = listOf(line("a", "i:a"), line("b", "i:b"), line("c", "i:c"))

    private fun parseWith(json: String) = PwtCatalogParser.parse((1..7).associate { "pwt:ok$it" to trainerJson(legalPool) } + ("pwt:x" to json))

    private fun assertRejected(label: String, json: String) {
        val accepted = runCatching { parseWith(json) }.isSuccess
        assertFalse(accepted, "parser accepted: $label")
    }

    @Test fun `parser - same species spelled with a namespace`() = assertRejected("cobblemon:a + a", trainerJson(listOf(line("a"), line("cobblemon:a"), line("c")), ace = "a"))
    @Test fun `parser - same species spelled in upper case`() = assertRejected("A + a", trainerJson(listOf(line("a"), line("A"), line("c")), ace = "a"))
    @Test fun `parser - same item spelled without a namespace`() = assertRejected("cobblemon:x + x", trainerJson(listOf(line("a", "cobblemon:x"), line("b", "x"), line("c"))))
    @Test fun `parser - a second level property`() = assertRejected("level=50 level=100", trainerJson(listOf(line("a", extra = "level=100"), line("b"), line("c"))))
    @Test fun `parser - no moves fails at load, not at the fight`() = assertRejected("no moves", trainerJson(listOf("a level=50", "b level=50", "c level=50")))
    @Test fun `parser - a legendary in the pool`() = assertRejected("mewtwo", trainerJson(listOf(line("mewtwo"), line("b"), line("c"))))
    @Test fun `parser - five moves`() = assertRejected("5 moves", trainerJson(listOf("a level=50 moves=q,w,e,r,t", line("b"), line("c")), ace = "a"))
    @Test fun `parser - skin with a parent path that the battle refuses`() {
        val skin = "pwt:textures/../a.png"
        val battleRefuses = runCatching { TrainerResourceSkin(skin) }.isFailure
        assertTrue(battleRefuses, "TrainerResourceSkin should refuse $skin")
        assertRejected(skin, trainerJson(legalPool, skin = skin))
    }
    @Test fun `parser - skin too long for the battle`() {
        val skin = "pwt:textures/" + "a".repeat(300) + ".png"
        assertTrue(runCatching { TrainerResourceSkin(skin) }.isFailure)
        assertRejected("300 char skin", trainerJson(legalPool, skin = skin))
    }
    @Test fun `parser - a line that does not start with its species`() = assertRejected("species=a", trainerJson(listOf(line("species=a"), line("b"), line("c")), ace = "b"))
    @Test fun `parser - a repeated property in another case`() = assertRejected("LEVEL=100", trainerJson(listOf(line("a", extra = "LEVEL=100"), line("b"), line("c"))))
    @Test fun `parser - the obvious rejects stay rejected`() {
        assertRejected("two entries", trainerJson(legalPool.take(2)))
        assertRejected("seven entries", trainerJson((1..7).map { line("s$it") }))
        assertRejected("level 49", trainerJson(listOf("a level=49 moves=tackle", line("b"), line("c")), ace = "a"))
        assertRejected("same item", trainerJson(listOf(line("a", "i:x"), line("b", "i:x"), line("c"))))
        assertRejected("ace outside", trainerJson(legalPool, ace = "zzz"))
        assertRejected("generation 10", trainerJson(legalPool, gen = 10))
        assertRejected("garbage", "{not json")
        assertRejected("array", "[]")
    }

    // ---- bundled data ----

    private val files = trainerDir.listFiles { f -> f.extension == "json" }!!.associate { "more_cobblemon_contents_pwt:${it.nameWithoutExtension}" to it.readText() }
    private val catalog = PwtCatalogParser.parse(files)
    private val jar = (javaClass.classLoader.getResource("data/cobblemon/species/generation1/bulbasaur.json")!!.openConnection() as JarURLConnection).jarFile
    private val species = jar.entries().asSequence().filter { it.name.startsWith("data/cobblemon/species/") && it.name.endsWith(".json") }
        .associate { it.name.substringAfterLast('/').removeSuffix(".json") to JsonParser.parseString(jar.getInputStream(it).reader().readText()).asJsonObject }

    @Test
    fun `every draw of a trainer fields the ace the bracket names`() {
        val problems = catalog.trainers.values.mapNotNull { t ->
            val r = Random(1)
            val hit = (1..2000).count { t.drawTeam(r).any { line -> line.substringBefore(' ') == t.ace } }
            if (hit == 2000) null else "${t.id.substringAfter(':')} ${t.ace} ${hit / 20}%"
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `draws obey the clauses`() {
        catalog.trainers.values.forEach { t ->
            val r = Random(2)
            repeat(500) {
                val team = t.drawTeam(r)
                assertEquals(3, team.size)
                assertEquals(3, team.map { it.substringBefore(' ') }.toSet().size)
                val items = team.mapNotNull { props(it)["held_item"] }
                assertEquals(items.toSet().size, items.size)
            }
        }
    }

    @Test
    fun `bundled pools are legal builds`() {
        val problems = ArrayList<String>()
        catalog.trainers.values.sortedBy { it.id }.forEach { t ->
            t.pool.forEach { line ->
                val name = line.substringBefore(' ')
                val p = props(line)
                val tag = "${t.id.substringAfter(':')}/$name"
                val data = species[name]
                val labels = data?.getAsJsonArray("labels")?.map { it.asString }.orEmpty()
                if (LegendaryClassPolicy.categoryFor("cobblemon:$name", labels) != null) problems += "$tag legendary by labels $labels"
                val moves = p.getValue("moves").split(',')
                if (moves.size != 4) problems += "$tag has ${moves.size} moves"
                if (moves.toSet().size != moves.size) problems += "$tag repeats a move"
                val evs = p.filterKeys { it.endsWith("_ev") }.values.map { it.toInt() }
                if (evs.sum() > 510 || evs.any { it !in 0..252 }) problems += "$tag EVs $evs"
                val ivs = p.filterKeys { it.endsWith("_iv") }.values.map { it.toInt() }
                if (ivs.any { it !in 0..31 }) problems += "$tag IVs $ivs"
                if ("nature" !in p) problems += "$tag has no nature"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `every trainer's skin has its battle variation`() {
        val variation = JsonParser.parseString(File(resources, "assets/more_cobblemon_contents_pwt/bedrock/npcs/variations/managed_trainer/40_more_cobblemon_contents_pwt.json").readText()).asJsonObject
        val aspects = variation.getAsJsonArray("variations").flatMap { v -> v.asJsonObject.getAsJsonArray("aspects").map { it.asString } }.toSet()
        val missing = catalog.trainers.values.map { TrainerResourceSkin(it.skin, it.slim) }.filter { it.aspect !in aspects }.map { it.aspect }
        assertEquals(emptyList<String>(), missing)
    }

    // ---- language ----

    @Test
    fun `every failure the server can show has its text`() {
        val ko = JsonParser.parseString(File(resources, "assets/more_cobblemon_contents_pwt/lang/ko_kr.json").readText()).asJsonObject
        val source = File("src/main/kotlin/jbro/cobblemon/mcc/pwt/server/PwtServer.kt").readText()
        val reasons = Regex("""\{ "([a-z_]+)" \}|error\("([a-z_]+)"\)|ERROR \+ "([a-z_]+)"""").findAll(source)
            .map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.toSet() +
            setOf("team_size", "duplicate_pick", "not_in_party", "legendary", "duplicate_species", "duplicate_item", "unexpected")
        val prefix = "screen.more_cobblemon_contents_pwt.error."
        val missing = reasons.filter { !ko.has(prefix + it) }.toMutableList()
        // MCC prints this one when the tab's entry returns false or the hub session is gone (BattleHubNetworking).
        if (!ko.has("screen.more_cobblemon_contents.hub.unavailable.pwt")) missing += "hub.unavailable.pwt"
        assertEquals(emptyList<String>(), missing)
    }

    // ---- network ----

    private fun buffer() = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)

    @Test
    fun `hostile intents are refused at decode`() {
        val nonce = UUID.randomUUID()
        fun raw(action: Int, count: Int, write: Int = 0) = buffer().apply {
            writeUUID(nonce); writeVarInt(action); writeVarInt(count); repeat(write) { writeUUID(UUID.randomUUID()) }
        }
        assertThrows(Exception::class.java) { PwtIntentPayload.CODEC.decode(raw(0, 7, 7)) }
        assertThrows(Exception::class.java) { PwtIntentPayload.CODEC.decode(raw(0, -1)) }
        assertThrows(Exception::class.java) { PwtIntentPayload.CODEC.decode(raw(0, Int.MAX_VALUE)) }
        assertThrows(Exception::class.java) { PwtIntentPayload.CODEC.decode(raw(99, 0)) }
        assertThrows(Exception::class.java) { PwtIntentPayload.CODEC.decode(raw(-1, 0)) }
        assertThrows(Exception::class.java) { PwtIntentPayload.CODEC.decode(raw(1, 3, 1)) }
        val ok = PwtIntentPayload.CODEC.decode(raw(PwtAction.ENTER.ordinal, 3, 3))
        assertEquals(3, ok.picked.size)
    }

    private fun worstView(): PwtView {
        val entrants = catalog.trainers.values.sortedByDescending { it.skin.length + it.nameKey.length }.take(7)
            .map { PwtEntrantView(it.nameKey, it.skin, it.generation, it.ace, false) } + PwtEntrantView("x".repeat(16), null, 0, null, true)
        return PwtView(UUID.randomUUID().toString(), true,
            List(6) { PwtPartyView(UUID.randomUUID().toString(), "cobblemon:" + "x".repeat(30), "form".repeat(8), 100, "cobblemon:" + "y".repeat(30), false) },
            PwtRunView(entrants, listOf(listOf(0, 2, 4, 6), listOf(0, 4), listOf(0)), 3, true, true, null, false),
            999, 25L, "screen.more_cobblemon_contents_pwt.error.battle_unavailable", true)
    }

    @Test
    fun `state survives the wire with its ints and nulls`() {
        val gson = Gson()
        val json = gson.toJson(worstView())
        assertTrue(json.length < 32767, "state is ${json.length} chars")
        val decoded = PwtStatePayload.CODEC.decode(buffer().also { PwtStatePayload.CODEC.encode(it, PwtStatePayload(json)) })
        val back = gson.fromJson(decoded.json, PwtView::class.java)
        val first: Any = back.run!!.winners[0][0]
        assertTrue(first is Int, "winners decoded as ${first.javaClass}")
        assertTrue(0 in back.run!!.winners[0], "slot lookup must work on decoded winners")
        assertNull(back.run!!.entrants.last().skin)
        assertEquals(25L, back.bp)
        val minimal = gson.fromJson(gson.toJson(PwtView("n", false, emptyList(), null, 0, 25L)), PwtView::class.java)
        assertNull(minimal.run); assertNull(minimal.errorKey); assertFalse(minimal.openScreen)
    }

    // ---- saved data ----

    private fun saved(titles: CompoundTag = CompoundTag(), unpaid: CompoundTag = CompoundTag(), counted: CompoundTag = CompoundTag()) =
        PwtSavedData(titles, unpaid, counted)

    @Test
    fun `a title is counted once per token, even after it was paid`() {
        val s = saved()
        val p = UUID.randomUUID(); val t = UUID.randomUUID()
        s.recordTitle(p, t); s.recordTitle(p, t)
        assertEquals(1, s.titles(p))
        assertEquals(listOf(t), s.unpaid(p))
        s.paid(t)
        assertEquals(emptyList<UUID>(), s.unpaid(p))
        s.recordTitle(p, t)
        assertEquals(1, s.titles(p), "same token counted again after payment")
    }

    @Test
    fun `saved titles and debts survive a save and do not leak between players`() {
        val s = saved()
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val t = UUID.randomUUID()
        s.recordTitle(a, t)
        val tag = s.save(CompoundTag(), RegistryAccess.EMPTY)
        val back = saved(tag.getCompound("titles").copy(), tag.getCompound("unpaid").copy(), tag.getCompound("counted").copy())
        assertEquals(1, back.titles(a)); assertEquals(0, back.titles(b))
        assertEquals(listOf(t), back.unpaid(a)); assertEquals(emptyList<UUID>(), back.unpaid(b))
    }

    @Test
    fun `a damaged debt entry does not throw out of the join handler`() {
        val player = UUID.randomUUID()
        val good = UUID.randomUUID()
        val unpaid = CompoundTag().apply {
            putString("not-a-uuid", "junk")
            putUUID("also-not-a-uuid", player)
            putUUID(good.toString(), player)
        }
        assertEquals(listOf(good), saved(unpaid = unpaid).unpaid(player))
    }

    // ---- bracket layout ----

    private fun states(): List<List<List<Int>>> = buildList {
        add(emptyList())
        repeat(300) { seed ->
            val r = Random(seed)
            var b = PwtBracket.draw(roster, r)
            while (!b.finished) { b = b.settle(r.nextInt(4) != 0, r); add(b.winners) }
        }
    }

    @Test
    fun `fuzz the layout over sizes and every bracket state`() {
        val problems = ArrayList<String>()
        val all = states()
        fun UiRect.inside(o: UiRect) = x >= o.x && right <= o.right && y >= o.y && bottom <= o.bottom
        fun UiRect.hits(o: UiRect) = x < o.right && o.x < right && y < o.bottom && o.y < bottom
        for (w in 0..720 step 9) for (h in 0..420 step 9) {
            val body = UiRect(13, 29, w, h)
            val winners = all[(w * 31 + h) % all.size]
            val layout = PwtBracketLayout.calculate(body, winners)
            if (layout == null) {
                if (PwtBracketLayout.width(PwtBracketLayout.MIN_SIZE) <= w && PwtBracketLayout.height(PwtBracketLayout.MIN_SIZE) <= h) problems += "$w x $h fits but no layout"
                continue
            }
            layout.boxes.forEach { if (!it.rect.inside(body)) problems += "$w x $h box out $it" }
            layout.lines.forEach { l -> l.segments.forEach { if (!it.inside(body)) problems += "$w x $h line out $it" } }
            if (!layout.trophy.inside(body)) problems += "$w x $h trophy out ${layout.trophy}"
            layout.boxes.forEach { if (it.rect.hits(layout.trophy)) problems += "$w x $h trophy hits $it" }
            layout.boxes.forEachIndexed { i, a -> layout.boxes.drop(i + 1).forEach { b -> if (a.rect.hits(b.rect)) problems += "$w x $h overlap $a $b" } }
            if (layout.boxes.size != 15) problems += "$w x $h has ${layout.boxes.size} boxes"
            winners.forEachIndexed { round, slots ->
                if (layout.boxes.filter { it.round == round + 1 }.map { it.slot } != slots) problems += "$w x $h round $round slots"
            }
        }
        assertEquals(emptyList<String>(), problems.take(8))
    }
}
