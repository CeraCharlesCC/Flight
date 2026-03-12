package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.check.CheckType
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.error.CommandErrorHandler
import me.devoxin.flight.api.error.CommandFailure
import me.devoxin.flight.api.error.StandardCommandErrorHandler
import me.devoxin.flight.api.error.StandardCommandErrorHandlerConfig
import me.devoxin.flight.api.hooks.DefaultCommandEventAdapter
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalCheckFailureIntegrationTest {
    @Test
    fun `failing localCheck reaches the builder level handler before adapters and skips execution`() {
        val hookOrder = CopyOnWriteArrayList<String>()
        val handler = LocalCheckRecordingCommandErrorHandler(hookOrder)
        val adapter = LocalCheckRecordingAdapter(hookOrder)
        val cog = LocalCheckBlockingMessageCog(hookOrder)
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .setErrorHandler(handler)
            .addEventListeners(adapter)
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!gated"))

        assertTrue(adapter.checkFailedLatch.await(3, TimeUnit.SECONDS))
        assertEquals(CheckType.LOCAL_CHECK, adapter.lastCheckType)
        assertEquals(
            listOf(
                "cog:localCheck",
                "handler:CheckFailure",
                "adapter:onCheckFailed:LOCAL_CHECK"
            ),
            hookOrder
        )

        val failure = assertIs<CommandFailure.CheckFailure>(handler.failures.single())
        assertEquals(CheckType.LOCAL_CHECK, failure.checkType)
        assertEquals(0, cog.invocationCount)
        assertEquals(0, cog.commandErrorCalls)
        assertFalse(cog.invoked.await(250, TimeUnit.MILLISECONDS))

        client.shutdown()
    }

    @Test
    fun `localCheck failures remain command safe through the standard handler`() {
        val recorder = MessageChannelRecorder()
        val cog = LocalCheckBlockingMessageCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .setErrorHandler(standardHandler())
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!gated", channel = recordingMessageChannel(recorder)))

        assertEquals(listOf("blocked:LOCAL_CHECK:MESSAGE"), recorder.messages)
        assertEquals(0, cog.invocationCount)
        assertEquals(0, cog.commandErrorCalls)

        client.shutdown()
    }

    @Test
    fun `localCheck failures remain interaction safe through the standard handler`() {
        val cog = LocalCheckBlockingSlashCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .build()

        client.commands.register(cog)
        val registeredSlashCommand = client.commands.findCommandsByCog(cog)
            .single { it.isSlashCapable }
        val ctx = RecordingSlashContext(client, registeredSlashCommand)

        standardHandler().handle(CommandFailure.CheckFailure(ctx, registeredSlashCommand, CheckType.LOCAL_CHECK))

        assertEquals(listOf("blocked:LOCAL_CHECK:SLASH"), ctx.responses)
        assertEquals(0, cog.invocationCount)

        client.shutdown()
    }
}

private fun standardHandler(): StandardCommandErrorHandler {
    return StandardCommandErrorHandler(
        StandardCommandErrorHandlerConfig().apply {
            checkFailureMessage = { failure -> "blocked:${failure.checkType}:${failure.context.contextType}" }
        }
    )
}

private class LocalCheckBlockingMessageCog(
    private val hookOrder: MutableList<String>? = null
) : Cog {
    val invoked: CountDownLatch = CountDownLatch(1)
    @Volatile
    var invocationCount: Int = 0
    @Volatile
    var commandErrorCalls: Int = 0

    @Command
    fun gated(ctx: MessageContext) {
        invocationCount += 1
        invoked.countDown()
    }

    override fun localCheck(ctx: Context, command: CommandFunction): Boolean {
        hookOrder?.add("cog:localCheck")
        return false
    }

    override fun onCommandError(ctx: Context, command: CommandFunction, error: Throwable): Boolean {
        commandErrorCalls += 1
        hookOrder?.add("cog:onCommandError:${error::class.simpleName}")
        return false
    }
}

private class LocalCheckBlockingSlashCog : Cog {
    @Volatile
    var invocationCount: Int = 0

    @Command(description = "Gated slash command")
    fun gatedSlash(ctx: Context) {
        invocationCount += 1
    }

    override fun localCheck(ctx: Context, command: CommandFunction): Boolean = false
}

private class LocalCheckRecordingCommandErrorHandler(
    private val hookOrder: MutableList<String>? = null
) : CommandErrorHandler {
    val failures: CopyOnWriteArrayList<CommandFailure> = CopyOnWriteArrayList()

    override fun handle(failure: CommandFailure) {
        failures += failure
        hookOrder?.add("handler:${failure::class.simpleName}")
    }
}

private class LocalCheckRecordingAdapter(
    private val hookOrder: MutableList<String>
) : DefaultCommandEventAdapter() {
    val checkFailedLatch: CountDownLatch = CountDownLatch(1)
    @Volatile
    var lastCheckType: CheckType? = null

    override fun onCheckFailed(ctx: Context, command: CommandFunction, checkType: CheckType) {
        lastCheckType = checkType
        hookOrder += "adapter:onCheckFailed:$checkType"
        checkFailedLatch.countDown()
    }
}

private class MessageChannelRecorder {
    val messages: MutableList<String> = mutableListOf()
}

private class RecordingSlashContext(
    override val commandClient: CommandClient,
    override val invokedCommand: me.devoxin.flight.internal.entities.Executable
) : Context {
    val responses: MutableList<String> = mutableListOf()

    override val contextType: ContextType = ContextType.SLASH
    override val jda: JDA = proxy()
    override val author: User = userProxy(84L)
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

private fun messageReceivedEvent(
    content: String,
    author: User = userProxy(42L),
    channel: MessageChannelUnion = passiveMessageChannel()
): MessageReceivedEvent {
    val jda = proxy<JDA>()
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

private fun passiveMessageChannel(): MessageChannelUnion {
    return proxyWithArgs<MessageChannelUnion>(MessageChannel::class.java) { method, _ ->
        when (method.name) {
            "getType", "getChannelType" -> ChannelType.PRIVATE
            else -> defaultValue(method.returnType)
        }
    }
}

private fun recordingMessageChannel(recorder: MessageChannelRecorder): MessageChannelUnion {
    return proxyWithArgs<MessageChannelUnion>(MessageChannel::class.java) { method, args ->
        when (method.name) {
            "getType", "getChannelType" -> ChannelType.PRIVATE
            "sendMessage" -> {
                val payload = args!![0]
                val content = when (payload) {
                    is String -> payload
                    is MessageCreateData -> payload.content
                    else -> payload.toString()
                }
                completedRestAction(method.returnType) {
                    recorder.messages += content
                    messageProxy(700L)
                }
            }

            else -> defaultValue(method.returnType)
        }
    }
}

private fun messageProxy(id: Long): Message {
    return proxy { method ->
        when (method.name) {
            "getIdLong" -> id
            "getId" -> id.toString()
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

private fun completedRestAction(returnType: Class<*>, supplier: () -> Any?): Any {
    val interfaces = when {
        returnType.isInterface -> arrayOf(returnType)
        returnType.interfaces.isNotEmpty() -> returnType.interfaces
        else -> arrayOf(java.util.concurrent.CompletionStage::class.java)
    }

    return Proxy.newProxyInstance(
        LocalCheckFailureIntegrationTest::class.java.classLoader,
        interfaces
    ) { _, method, _ ->
        when (method.name) {
            "submit" -> CompletableFuture.completedFuture(supplier())
            "queue" -> {
                supplier()
                null
            }

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
