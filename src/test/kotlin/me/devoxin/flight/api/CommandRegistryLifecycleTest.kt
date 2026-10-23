package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.command.CommandRegistry
import me.devoxin.flight.api.context.MessageContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandRegistryLifecycleTest {
    @Test
    fun `clear unloads every cog instance once despite shared names and equality`() {
        for (name in listOf(null, "shared")) {
            val first = FirstLifecycleCog(name)
            val second = SecondLifecycleCog(name)
            assertTrue(first.equals(second), "The fixture must exercise identity rather than equality")
            val registry = CommandRegistry().apply { register(first, second) }

            assertEquals(4, registry.size)
            registry.clear()
            registry.clear()

            assertEquals(1, first.unloadCount)
            assertEquals(1, second.unloadCount)
            assertEquals(0, registry.size)
        }
    }

    @Test
    fun `unloading one cog preserves another instance that compares equal`() {
        val first = FirstLifecycleCog(null)
        val second = SecondLifecycleCog(null)
        val registry = CommandRegistry().apply { register(first, second) }

        assertEquals(2, registry.findCommandsByCog(first).size)
        registry.unload(first)

        assertEquals(1, first.unloadCount)
        assertEquals(0, second.unloadCount)
        assertEquals(2, registry.size)
        assertTrue(registry.values.all { it.cog === second })

        registry.clear()
        assertEquals(1, second.unloadCount)
    }
}

private abstract class LifecycleCog(private val displayName: String?) : Cog {
    var unloadCount = 0
    override fun name(): String? = displayName
    override fun unload() { unloadCount++ }
    override fun equals(other: Any?): Boolean = other is LifecycleCog
    override fun hashCode(): Int = 0
}

private class FirstLifecycleCog(name: String?) : LifecycleCog(name) {
    @Command fun first(ctx: MessageContext) = Unit
    @Command fun another_first(ctx: MessageContext) = Unit
}

private class SecondLifecycleCog(name: String?) : LifecycleCog(name) {
    @Command fun second(ctx: MessageContext) = Unit
    @Command fun another_second(ctx: MessageContext) = Unit
}
