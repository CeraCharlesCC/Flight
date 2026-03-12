package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.api.error.CommandFailure
import me.devoxin.flight.api.error.StandardCommandErrorHandler
import me.devoxin.flight.api.exceptions.BadArgument
import me.devoxin.flight.internal.entities.Executable
import net.dv8tion.jda.api.JDA
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