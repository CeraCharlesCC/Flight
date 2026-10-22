package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.check.CheckType
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.api.error.CommandFailure
import me.devoxin.flight.api.error.StandardCommandErrorHandler
import me.devoxin.flight.api.error.StandardCommandErrorHandlerConfig
import me.devoxin.flight.api.exceptions.BadArgument
import me.devoxin.flight.internal.entities.Executable
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StandardCommandErrorHandlerTest {
    @Test
    fun `standard handler responds through Context respond for user facing and execution failures`() {
        val registry = me.devoxin.flight.api.command.CommandRegistry().apply {
            register(StandardHandlerCog())
        }
        val command = registry.findMessageCommand("standard")!!
        val argument = command.arguments.single()
        val context = RecordingContext(CommandClient.builder().build(), command)
        val handler = StandardCommandErrorHandler()

        handler.handle(CommandFailure.BadArgumentFailure(context, command, BadArgument(argument, "oops")))
        handler.handle(
            CommandFailure.CommandExecutionFailure(
                context,
                command,
                IllegalStateException("boom")
            )
        )

        assertContentEquals(
            listOf(
                "`name` must be a `String`",
                "Something went wrong while executing that command."
            ),
            context.responses
        )

        context.commandClient.shutdown()
    }

    @Test
    fun `custom sender receives formatted messages for every contextual failure`() {
        val client = CommandClient.builder().register(StandardHandlerCog()).build()
        try {
            val command = client.commands.findMessageCommand("standard")!!
            val context = RecordingContext(client, command)
            val sent = mutableListOf<Pair<Context, String>>()
            val config = StandardCommandErrorHandlerConfig().apply {
                badArgumentMessage = { "argument" }
                checkFailureMessage = { "check" }
                cooldownMessage = { "cooldown" }
                missingUserPermissionsMessage = { "user permissions" }
                missingBotPermissionsMessage = { "bot permissions" }
                parseFailureMessage = { "parse" }
                commandExecutionMessage = { "execution" }
                responseSender = { responseContext, message ->
                    sent += responseContext to message
                    CompletableFuture.completedFuture(Unit)
                }
            }
            val handler = StandardCommandErrorHandler(config)
            val error = IllegalStateException("boom")
            listOf(
                CommandFailure.BadArgumentFailure(context, command, BadArgument(command.arguments.single(), "bad")),
                CommandFailure.CheckFailure(context, command, CheckType.LOCAL_CHECK),
                CommandFailure.CooldownFailure(context, command, 1500),
                CommandFailure.MissingUserPermissionsFailure(context, command, listOf(Permission.MESSAGE_MANAGE)),
                CommandFailure.MissingBotPermissionsFailure(context, command, listOf(Permission.MESSAGE_SEND)),
                CommandFailure.ParseFailure(context, command, error),
                CommandFailure.CommandExecutionFailure(context, command, error)
            ).forEach(handler::handle)

            assertContentEquals(
                listOf("argument", "check", "cooldown", "user permissions", "bot permissions", "parse", "execution")
                    .map { context to it },
                sent
            )
            assertEquals(emptyList(), context.responses)

            config.checkFailureMessage = { null }
            handler.handle(CommandFailure.CheckFailure(context, command, CheckType.LOCAL_CHECK))
            handler.handle(CommandFailure.FrameworkFailure(error))
            assertEquals(7, sent.size)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `ephemeral setting preserves message context responses`() {
        val client = CommandClient.builder().register(StandardHandlerCog()).build()
        try {
            val command = client.commands.findMessageCommand("standard")!!
            val context = RecordingContext(client, command)
            val handler = StandardCommandErrorHandler(StandardCommandErrorHandlerConfig().apply {
                ephemeralResponses = true
            })

            handler.handle(CommandFailure.CooldownFailure(context, command, 1500))

            assertContentEquals(listOf("That command is on cooldown. Try again in 2s."), context.responses)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `sender failures do not escape error handling or prevent later responses`() {
        val client = CommandClient.builder().register(StandardHandlerCog()).build()
        try {
            val command = client.commands.findMessageCommand("standard")!!
            val context = RecordingContext(client, command)
            var attempts = 0
            val handler = StandardCommandErrorHandler(StandardCommandErrorHandlerConfig().apply {
                responseSender = { _, _ ->
                    when (++attempts) {
                        1 -> throw IllegalStateException("synchronous send failure")
                        2 -> CompletableFuture.failedFuture<Unit>(IllegalStateException("asynchronous send failure"))
                        else -> CompletableFuture.completedFuture(Unit)
                    }
                }
            })

            repeat(3) {
                handler.handle(CommandFailure.CooldownFailure(context, command, 1500))
            }
            assertEquals(3, attempts)
        } finally {
            client.shutdown()
        }
    }
}

private class StandardHandlerCog : Cog {
    @Command
    fun standard(ctx: Context, name: String) = Unit
}

private class RecordingContext(
    override val commandClient: CommandClient,
    override val invokedCommand: Executable
) : Context {
    val responses = mutableListOf<String>()

    override val contextType: ContextType = ContextType.MESSAGE
    override val jda: JDA = proxy()
    override val author: User = proxy()
    override val guild: Guild? = null
    override val member: Member? = null
    override val messageChannel: MessageChannel = proxy()
    override val guildChannel: GuildMessageChannel? = null
    override val isFromGuild: Boolean = false

    override fun respond(content: String): CompletableFuture<*> {
        responses += content
        return CompletableFuture.completedFuture(Unit)
    }
}

private inline fun <reified T> proxy(noinline handler: (Method) -> Any? = { defaultValue(it.returnType) }): T {
    return Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "equals" -> false
            "hashCode" -> 0
            "toString" -> "${T::class.java.simpleName}Proxy"
            else -> handler(method) ?: defaultValue(method.returnType)
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
