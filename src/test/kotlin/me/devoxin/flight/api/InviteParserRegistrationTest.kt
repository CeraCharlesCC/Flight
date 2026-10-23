package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.arguments.types.Invite
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.command.CommandRegistry
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.internal.arguments.ArgParser
import me.devoxin.flight.internal.parsers.Parser
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class InviteParserRegistrationTest {
    @AfterTest
    fun clearParsers() = ArgParser.parsers.clear()

    @Test
    fun `default invite parser returns the deferred Flight invite wrapper`() {
        val cog = DeferredInviteRegressionCog()
        val client = CommandClient.builder()
            .configureDefaultHelpCommand { enabled = false }
            .registerDefaultParsers()
            .register(cog)
            .build()
        try {
            assertNotNull(ArgParser.parsers[Invite::class.java])
            assertNull(ArgParser.parsers[net.dv8tion.jda.api.entities.Invite::class.java])
            val command = client.commands.findMessageCommand("deferred_invite")!!
            val ctx = inviteContext(client, command)
            val parsed = ArgParser.parseArguments(command, ctx, listOf("discord.gg/example"), ' ')
            command.invokeBlocking(command.bindArguments(ctx, parsed))

            assertEquals("example", cog.received!!.code)
            assertEquals("discord.gg/example", cog.received!!.url)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `JDA invite parameters fail registration with actionable guidance`() {
        CommandClient.builder().registerDefaultParsers()
        val registry = CommandRegistry()

        val error = assertFailsWith<IllegalStateException> { registry.register(JdaInviteRegressionCog()) }

        assertContains(error.message!!, "net.dv8tion.jda.api.entities.Invite")
        assertContains(error.message!!, "me.devoxin.flight.api.arguments.types.Invite")
        assertContains(error.message!!, "custom parser")
        assertEquals(0, registry.size)
    }

    @Test
    fun `applications can opt into a parser that returns an actual JDA invite`() {
        val invite = inviteProxy<net.dv8tion.jda.api.entities.Invite>()
        val cog = JdaInviteRegressionCog()
        val client = CommandClient.builder()
            .configureDefaultHelpCommand { enabled = false }
            .registerDefaultParsers()
            .addCustomParser(net.dv8tion.jda.api.entities.Invite::class.java, object : Parser<net.dv8tion.jda.api.entities.Invite> {
                override fun parse(ctx: MessageContext, param: String): net.dv8tion.jda.api.entities.Invite = invite
            })
            .register(cog)
            .build()
        try {
            val command = client.commands.findMessageCommand("jda_invite")!!
            val ctx = inviteContext(client, command)
            val parsed = ArgParser.parseArguments(command, ctx, listOf("discord.gg/example"), ' ')
            command.invokeBlocking(command.bindArguments(ctx, parsed))

            assertSame(invite, cog.received)
        } finally {
            client.shutdown()
        }
    }
}

class DeferredInviteRegressionCog : Cog {
    var received: Invite? = null
    @Command fun deferred_invite(ctx: MessageContext, invite: Invite) { received = invite }
}

class JdaInviteRegressionCog : Cog {
    var received: net.dv8tion.jda.api.entities.Invite? = null
    @Command fun jda_invite(ctx: MessageContext, invite: net.dv8tion.jda.api.entities.Invite) { received = invite }
}

private fun inviteContext(client: CommandClient, command: CommandFunction): MessageContext {
    val jda = inviteProxy<JDA>()
    val author = inviteProxy<User>()
    val channel = inviteProxy<MessageChannelUnion> { if (it == "getType") ChannelType.PRIVATE else null }
    val message = inviteProxy<Message> { method ->
        when (method) {
            "getJDA" -> jda
            "getAuthor" -> author
            "getChannel" -> channel
            "getChannelType" -> ChannelType.PRIVATE
            "getContentRaw" -> "!${command.name}"
            else -> null
        }
    }
    return MessageContext(client, MessageReceivedEvent(jda, 0L, message), "!", command)
}

private inline fun <reified T> inviteProxy(crossinline result: (String) -> Any? = { null }): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        result(method.name) ?: when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Long.TYPE -> 0L
            java.lang.Integer.TYPE -> 0
            else -> null
        }
    } as T
