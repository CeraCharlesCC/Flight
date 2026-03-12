package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Autocomplete
import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.annotations.SubCommand
import me.devoxin.flight.api.annotations.SubCommandGroup
import me.devoxin.flight.api.autocomplete.AutocompleteHandler
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.api.context.SlashContext
import me.devoxin.flight.api.hooks.DefaultCommandEventAdapter
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.AutoCompleteQuery
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubcommandGroupDispatchTest {
    @Test
    fun `command client dispatches grouped slash subcommands by full path`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .build()
        val cog = GroupedDispatchCog()
        client.commands.register(cog)

        client.onEvent(slashEvent(name = "flight", group = "crew", subcommand = "create"))
        assertTrue(cog.invocationLatch.await(3, TimeUnit.SECONDS))

        assertEquals("crew/create", cog.lastInvocation)
        assertEquals(ContextType.SLASH, cog.lastContextType)

        client.shutdown()
    }

    @Test
    fun `command client still dispatches direct flat slash subcommands`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .build()
        val cog = GroupedDispatchCog()
        client.commands.register(cog)

        client.onEvent(slashEvent(name = "flight", subcommand = "cancel"))
        assertTrue(cog.invocationLatch.await(3, TimeUnit.SECONDS))

        assertEquals("cancel", cog.lastInvocation)
        assertEquals(ContextType.SLASH, cog.lastContextType)

        client.shutdown()
    }

    @Test
    fun `command client dispatches grouped prefix subcommands using group then child`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .build()
        val cog = GroupedDispatchCog()
        client.commands.register(cog)

        client.onEvent(messageReceivedEvent("!flight booking create"))
        assertTrue(cog.invocationLatch.await(3, TimeUnit.SECONDS))

        assertEquals("booking/create", cog.lastInvocation)
        assertEquals(ContextType.MESSAGE, cog.lastContextType)

        client.shutdown()
    }

    @Test
    fun `grouped prefix path missing child is treated as unknown instead of falling back to root`() {
        val adapter = UnknownCommandRecordingAdapter()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .addEventListeners(adapter)
            .configureDefaultHelpCommand { enabled = false }
            .build()
        val cog = GroupedDispatchCog()
        client.commands.register(cog)

        client.onEvent(messageReceivedEvent("!flight booking"))

        assertEquals(0, cog.rootInvocationCount)
        assertNull(cog.lastInvocation)
        assertEquals("flight", adapter.lastUnknownCommand)
        assertContentEquals(listOf("booking"), adapter.lastUnknownArgs)
    }

    @Test
    fun `grouped prefix path with invalid child is treated as unknown instead of falling back to root`() {
        val adapter = UnknownCommandRecordingAdapter()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .addEventListeners(adapter)
            .configureDefaultHelpCommand { enabled = false }
            .build()
        val cog = GroupedDispatchCog()
        client.commands.register(cog)

        client.onEvent(messageReceivedEvent("!flight booking missing"))

        assertEquals(0, cog.rootInvocationCount)
        assertNull(cog.lastInvocation)
        assertEquals("flight", adapter.lastUnknownCommand)
        assertContentEquals(listOf("booking", "missing"), adapter.lastUnknownArgs)
    }

    @Test
    fun `prefix dispatch rejects declared direct subcommands that do not support message context`() {
        val adapter = UnknownCommandRecordingAdapter()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .addEventListeners(adapter)
            .configureDefaultHelpCommand { enabled = false }
            .build()
        val cog = ContextRestrictedDispatchCog()
        client.commands.register(cog)

        client.onEvent(messageReceivedEvent("!hybrid inspect"))

        assertEquals(0, cog.rootInvocationCount)
        assertNull(cog.lastInvocation)
        assertEquals("hybrid", adapter.lastUnknownCommand)
        assertContentEquals(listOf("inspect"), adapter.lastUnknownArgs)
    }

    @Test
    fun `autocomplete resolves grouped subcommands with duplicate child names`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .build()
        val cog = GroupedAutocompleteCog()
        client.commands.register(cog)

        client.onEvent(autocompleteEvent(name = "search", group = "crew", subcommand = "create", focusedOption = "query"))
        assertTrue(cog.autocompleteLatch.await(3, TimeUnit.SECONDS))

        assertEquals("crew", cog.lastAutocompleteGroup)

        client.shutdown()
    }
}

class GroupedDispatchCog : Cog {
    val invocationLatch: CountDownLatch = CountDownLatch(1)
    var lastInvocation: String? = null
    var lastContextType: ContextType? = null
    var rootInvocationCount: Int = 0

    @Command(description = "Flight operations")
    @SubCommandGroup(name = "crew", description = "Crew operations")
    @SubCommandGroup(name = "booking", description = "Booking operations")
    fun flight(ctx: Context) {
        rootInvocationCount += 1
        lastInvocation = "root"
        lastContextType = ctx.contextType
        invocationLatch.countDown()
    }

    @SubCommand(parent = "flight", description = "Cancel flight")
    fun cancel(ctx: Context) {
        lastInvocation = "cancel"
        lastContextType = ctx.contextType
        invocationLatch.countDown()
    }

    @SubCommand(parent = "flight", group = "booking", name = "create", description = "Create booking")
    fun createBooking(ctx: Context) {
        lastInvocation = "booking/create"
        lastContextType = ctx.contextType
        invocationLatch.countDown()
    }

    @SubCommand(parent = "flight", group = "crew", name = "create", description = "Create crew")
    fun createCrew(ctx: Context) {
        lastInvocation = "crew/create"
        lastContextType = ctx.contextType
        invocationLatch.countDown()
    }
}

class GroupedAutocompleteCog : Cog {
    val autocompleteLatch: CountDownLatch = CountDownLatch(1)
    var lastAutocompleteGroup: String? = null

    @Command(description = "Search operations")
    @SubCommandGroup(name = "crew", description = "Crew operations")
    @SubCommandGroup(name = "booking", description = "Booking operations")
    fun search(ctx: SlashContext) = Unit

    @SubCommand(parent = "search", group = "booking", name = "create", description = "Create booking")
    fun createBooking(ctx: SlashContext, @Autocomplete(BookingAutocompleteHandler::class) query: String) = Unit

    @SubCommand(parent = "search", group = "crew", name = "create", description = "Create crew")
    fun createCrew(ctx: SlashContext, @Autocomplete(CrewAutocompleteHandler::class) query: String) = Unit
}

class ContextRestrictedDispatchCog : Cog {
    var rootInvocationCount: Int = 0
    var lastInvocation: String? = null

    @Command(description = "Hybrid operations")
    fun hybrid(ctx: Context) {
        rootInvocationCount += 1
        lastInvocation = "root"
    }

    @SubCommand(parent = "hybrid", description = "Inspect")
    fun inspect(ctx: SlashContext) {
        lastInvocation = "inspect"
    }
}

object BookingAutocompleteHandler : AutocompleteHandler<GroupedAutocompleteCog> {
    override suspend fun complete(cog: GroupedAutocompleteCog, event: CommandAutoCompleteInteractionEvent) {
        cog.lastAutocompleteGroup = "booking"
        cog.autocompleteLatch.countDown()
    }
}

object CrewAutocompleteHandler : AutocompleteHandler<GroupedAutocompleteCog> {
    override suspend fun complete(cog: GroupedAutocompleteCog, event: CommandAutoCompleteInteractionEvent) {
        cog.lastAutocompleteGroup = "crew"
        cog.autocompleteLatch.countDown()
    }
}

private class UnknownCommandRecordingAdapter : DefaultCommandEventAdapter() {
    var lastUnknownCommand: String? = null
    var lastUnknownArgs: List<String> = emptyList()

    override fun onUnknownCommand(event: MessageReceivedEvent, command: String, args: List<String>) {
        lastUnknownCommand = command
        lastUnknownArgs = args.toList()
    }
}

private fun slashEvent(
    name: String,
    group: String? = null,
    subcommand: String? = null,
    invokingUser: User = userProxy(42L)
): SlashCommandInteractionEvent {
    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val interaction = proxyWithArgs<SlashCommandInteraction> { method, _ ->
        when (method.name) {
            "getName" -> name
            "getSubcommandGroup" -> group
            "getSubcommandName" -> subcommand
            "getOptions" -> emptyList<Any>()
            "getUser" -> invokingUser
            "getChannel" -> channel
            "getGuild" -> null
            "getMember" -> null
            "isFromGuild" -> false
            "isAcknowledged" -> false
            "getJDA" -> jda
            "getCommandType" -> JdaCommand.Type.SLASH
            else -> defaultValue(method.returnType)
        }
    }

    return SlashCommandInteractionEvent(jda, 0L, interaction)
}

private fun autocompleteEvent(
    name: String,
    group: String? = null,
    subcommand: String? = null,
    focusedOption: String,
    invokingUser: User = userProxy(42L)
): CommandAutoCompleteInteractionEvent {
    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val query = createAutoCompleteQuery(focusedOption)
    val interaction = proxyWithArgs<net.dv8tion.jda.api.interactions.commands.CommandAutoCompleteInteraction> { method, _ ->
        when (method.name) {
            "getName" -> name
            "getSubcommandGroup" -> group
            "getSubcommandName" -> subcommand
            "getFocusedOption" -> query
            "getUser" -> invokingUser
            "getChannel" -> channel
            "getGuild" -> null
            "getMember" -> null
            "isFromGuild" -> false
            "isAcknowledged" -> false
            "getJDA" -> jda
            "getCommandType" -> JdaCommand.Type.SLASH
            "getOptions" -> emptyList<Any>()
            else -> defaultValue(method.returnType)
        }
    }

    return CommandAutoCompleteInteractionEvent(jda, 0L, interaction)
}

private fun createAutoCompleteQuery(focusedOption: String): AutoCompleteQuery {
    val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val unsafe = unsafeField.get(null) as Unsafe
    val query = unsafe.allocateInstance(AutoCompleteQuery::class.java) as AutoCompleteQuery

    setField(unsafe, query, "name", focusedOption)
    setField(unsafe, query, "value", "typed")
    setField(unsafe, query, "type", OptionType.STRING)

    return query
}

private fun setField(unsafe: Unsafe, target: Any, fieldName: String, value: Any?) {
    val field = target::class.java.getDeclaredField(fieldName)
    unsafe.putObject(target, unsafe.objectFieldOffset(field), value)
}

private fun messageReceivedEvent(content: String, author: User = userProxy(42L)): MessageReceivedEvent {
    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val message = proxy<Message> { method ->
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

    return MessageReceivedEvent(jda, 0L, message)
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
