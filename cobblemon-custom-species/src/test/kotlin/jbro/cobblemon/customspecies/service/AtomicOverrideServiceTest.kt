package jbro.cobblemon.customspecies.service

import jbro.cobblemon.customspecies.config.CustomSpeciesConfigParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AtomicOverrideServiceTest {
    @Test
    fun `specific inherited form receives independent collections`() {
        val catalog = FakeSpeciesCatalog.rotomWithInheritedWashForm()
        val service = AtomicOverrideService(catalog)
        val config = CustomSpeciesConfigParser().parse(
            """{"schema":1,"overrides":[{"species":"cobblemon:rotom","form":"wash","moves":{"add":["tm:hydropump"]},"base_stats":{"speed":99}}]}"""
        )

        service.apply(config)

        val base = catalog.requireTarget("cobblemon:rotom", "base")
        val wash = catalog.requireTarget("cobblemon:rotom", "wash")
        assertNotSame(base.moves, wash.moves)
        assertEquals(setOf("1:thundershock"), base.moves)
        assertEquals(setOf("1:thundershock", "tm:hydropump"), wash.moves)
        assertEquals(99, wash.baseStats["speed"])
        assertEquals(91, base.baseStats["speed"])
    }

    @Test
    fun `failed candidate leaves the previous good snapshot active`() {
        val catalog = FakeSpeciesCatalog.charizard()
        val service = AtomicOverrideService(catalog)
        val parser = CustomSpeciesConfigParser()
        service.apply(parser.parse("""{"schema":1,"overrides":[{"species":"cobblemon:charizard","form":"base","moves":{"add":["tm:scaleshot"]}}]}"""))

        assertThrows(OverrideApplicationException::class.java) {
            service.apply(parser.parse("""{"schema":1,"overrides":[{"species":"cobblemon:missingno","form":"base","moves":{"add":["tm:surf"]}}]}"""))
        }

        assertEquals(setOf("1:growl", "tm:scaleshot"), catalog.requireTarget("cobblemon:charizard", "base").moves)
    }

    @Test
    fun `empty successful config restores original baseline`() {
        val catalog = FakeSpeciesCatalog.charizard()
        val service = AtomicOverrideService(catalog)
        val parser = CustomSpeciesConfigParser()
        service.apply(parser.parse("""{"schema":1,"overrides":[{"species":"cobblemon:charizard","form":"base","base_stats":{"attack":100}}]}"""))
        service.apply(parser.parse("""{"schema":1,"overrides":[]}"""))

        assertEquals(84, catalog.requireTarget("cobblemon:charizard", "base").baseStats["attack"])
    }

    @Test
    fun `publish failure rolls back to the previous good state rather than the original baseline`() {
        val backing = FakeSpeciesCatalog.charizard()
        val catalog = FailOnceCatalog(backing)
        val service = AtomicOverrideService(catalog)
        val parser = CustomSpeciesConfigParser()
        service.apply(parser.parse("""{"schema":1,"overrides":[{"species":"cobblemon:charizard","form":"base","base_stats":{"attack":100}}]}"""))
        catalog.failNextWrite = true

        assertThrows(OverrideApplicationException::class.java) {
            service.apply(parser.parse("""{"schema":1,"overrides":[{"species":"cobblemon:charizard","form":"base","base_stats":{"attack":120}}]}"""))
        }

        assertEquals(100, backing.requireTarget("cobblemon:charizard", "base").baseStats["attack"])
    }

    private class FailOnceCatalog(private val delegate: SpeciesCatalog) : SpeciesCatalog by delegate {
        var failNextWrite = false

        override fun write(key: SpeciesTargetKey, state: SpeciesTargetState) {
            if (failNextWrite) {
                failNextWrite = false
                error("simulated publication failure")
            }
            delegate.write(key, state)
        }
    }
}
