package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.hooks.DefaultCommandEventAdapter
import me.devoxin.flight.api.prefix.PrefixProvider
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefixResolutionTest {
    @Test
    fun `isCommand chooses the longest matching prefix`() {
        val client = newClient(StaticPrefixProvider(listOf("!", "!!")))
        client.commands.register(PrefixRecordingCog())

        assertTrue(client.isCommand(message("!!ping")))
        assertFalse(client.isCommand(message("!!")))

        client.shutdown()
    }

    @Test
    fun `message dispatch uses the longest matching prefix from a custom provider`() {
        val adapter = PrefixUnknownCommandRecordingAdapter()
        val cog = PrefixRecordingCog()
        val client = newClient(StaticPrefixProvider(listOf("!", "!!")), adapter)
        client.commands.register(cog)

        client.onEvent(messageReceivedEvent("!!ping"))

        assertTrue(cog.invoked.await(3, TimeUnit.SECONDS))
        assertEquals(1, cog.invocationCount)
        assertEquals("!!", cog.lastTrigger)
        assertNull(adapter.lastUnknownCommand)

        client.shutdown()
    }

    @Test
    fun `shorter repetitive prefixes no longer produce an unknown command`() {
        val adapter = PrefixUnknownCommandRecordingAdapter()
        val cog = PrefixRecordingCog()
        val client = newClient(StaticPrefixProvider(listOf("!", "!!", "!!!")), adapter)
        client.commands.register(cog)

        client.onEvent(messageReceivedEvent("!!!ping"))

        assertTrue(cog.invoked.await(3, TimeUnit.SECONDS))
        assertEquals(1, cog.invocationCount)
        assertEquals("!!!", cog.lastTrigger)
        assertNull(adapter.lastUnknownCommand)

        client.shutdown()
    }

    @Test
    fun `duplicate equal length prefixes still resolve deterministically`() {
        val adapter = PrefixUnknownCommandRecordingAdapter()
        val cog = PrefixRecordingCog()
        val client = newClient(StaticPrefixProvider(listOf("!!", "!!", "!")), adapter)
        client.commands.register(cog)

        client.onEvent(messageReceivedEvent("!!ping"))

        assertTrue(cog.invoked.await(3, TimeUnit.SECONDS))
        assertEquals(1, cog.invocationCount)
        assertEquals("!!", cog.lastTrigger)
        assertNull(adapter.lastUnknownCommand)

        client.shutdown()
    }
}

private class StaticPrefixProvider(
    private val prefixes: List<String>
) : PrefixProvider {
    override fun provide(message: Message): List<String> = prefixes
}

class PrefixRecordingCog : Cog {
    val invoked: CountDownLatch = CountDownLatch(1)
    var invocationCount: Int = 0
    var lastTrigger: String? = null

    @Command
    fun ping(ctx: MessageContext) {
        invocationCount += 1
        lastTrigger = ctx.trigger
        invoked.countDown()
    }
}

private class PrefixUnknownCommandRecordingAdapter : DefaultCommandEventAdapter() {
    var lastUnknownCommand: String? = null

    override fun onUnknownCommand(event: MessageReceivedEvent, command: String, args: List<String>) {
        lastUnknownCommand = command
    }
}

private fun newClient(
    prefixProvider: PrefixProvider,
    vararg adapters: DefaultCommandEventAdapter
): CommandClient {
    return CommandClient.builder()
        .setPrefixProvider(prefixProvider)
        .setAllowMentionPrefix(false)
        .configureDefaultHelpCommand { enabled = false }
        .apply {
            adapters.forEach { addEventListeners(it) }
        }
        .build()
}

private fun message(content: String, author: User = userProxy(42L)): Message {
    val jda = proxy<JDA>()
    val channel = interactionChannel()

    return proxy { method ->
        when (method.name) {
            "getContentRaw" -> content
            "getAuthor" -> author
            "getChannel" -> channel
            "getMember" -> null
            "isWebhookMessage" -> false
            "isFromGuild" -> false
            "getChannelType" -> ChannelType.PRIVATE
            "getJDA" -> jda
            else -> defaultValue(method.returnType)
        }
    }
}

private fun messageReceivedEvent(content: String, author: User = userProxy(42L)): MessageReceivedEvent {
    val jda = proxy<JDA>()
    return MessageReceivedEvent(jda, 0L, message(content, author))
}

private fun interactionChannel(): MessageChannelUnion {
    return proxyWithArgs<MessageChannelUnion>(MessageChannel::class.java) { method, _ ->
        when (method.name) {
            "getType", "getChannelType" -> ChannelType.PRIVATE
            else -> defaultValue(method.returnType)
        }
    }
}

private fun userProxy(id: Long): User {
    return proxy { method ->
        when (method.name) {
            "getIdLong" -> id
            "getId" -> id.toString()
            "isBot" -> false
            else -> defaultValue(method.returnType)
        }
    }
}

private inline fun <reified T> proxy(noinline handler: (Method) -> Any? = { defaultValue(it.returnType) }): T {
    return proxyWithArgs(handler = { method, _ -> handler(method) })
}

private inline fun <reified T> proxyWithArgs(
    vararg extraInterfaces: Class<*>,
    noinline handler: (Method, Array<out Any?>?) -> Any? = { method, _ -> defaultValue(method.returnType) }
): T {
    return Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java, *extraInterfaces)
    ) { _, method, args ->
        when (method.name) {
            "equals" -> false
            "hashCode" -> 0
            "toString" -> "${T::class.java.simpleName}Proxy"
            else -> handler(method, args) ?: defaultValue(method.returnType)
        }
    } as T
}

private fun defaultValue(type: Class<*>): Any? {
    return when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> 0.toChar()
        Void.TYPE -> null
        else -> null
    }
}
