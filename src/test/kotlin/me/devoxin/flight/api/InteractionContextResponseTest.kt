package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.InteractionContext
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.context.SlashContext
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import net.dv8tion.jda.api.utils.messages.MessageEditData
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.LazyThreadSafetyMode.NONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class InteractionContextResponseTest {
    @Test
    fun `interaction context exposes locale and guildLocale and generic Context delegates them`() {
        val ctx = slashContext(
            recorder = InteractionRecorder(),
            locale = DiscordLocale.JAPANESE,
            guildLocale = DiscordLocale.GERMAN
        )
        val generic: Context = ctx

        assertEquals(DiscordLocale.JAPANESE, ctx.locale)
        assertEquals(DiscordLocale.GERMAN, ctx.guildLocale)
        assertEquals(DiscordLocale.JAPANESE, generic.locale)
        assertEquals(DiscordLocale.GERMAN, generic.guildLocale)
        assertNull(messageContext(invokedCommand = ctx.invokedCommand).locale)
        assertNull(messageContext(invokedCommand = ctx.invokedCommand).guildLocale)
    }

    @Test
    fun `followup after acknowledgement sends webhook message`() {
        val recorder = InteractionRecorder()
        val ctx = slashContext(recorder)

        ctx.defer(ephemeral = true).join()
        ctx.followup(MessageCreateData.fromContent("followup"), ephemeral = true).join()

        assertEquals(1, recorder.deferCalls)
        assertEquals(1, recorder.followupCalls)
        assertEquals("followup", recorder.lastFollowupMessage?.content)
        assertEquals(true, recorder.lastFollowupEphemeral)
    }

    @Test
    fun `respond after defer edits original`() {
        val recorder = InteractionRecorder()
        val ctx = slashContext(recorder)

        ctx.defer(ephemeral = true).join()
        ctx.respond(MessageCreateData.fromContent("edited original"), ephemeral = false).join()

        assertEquals(1, recorder.deferCalls)
        assertEquals(1, recorder.editOriginalCalls)
        assertEquals(0, recorder.followupCalls)
        assertEquals("edited original", recorder.lastEditOriginal?.content)
    }

    @Test
    fun `respond after reply sends followup`() {
        val recorder = InteractionRecorder()
        val ctx = slashContext(recorder)

        markReplied(ctx, recorder)
        ctx.respond(MessageCreateData.fromContent("followup reply"), ephemeral = true).join()

        assertEquals(1, recorder.followupCalls)
        assertEquals(0, recorder.editOriginalCalls)
        assertEquals("followup reply", recorder.lastFollowupMessage?.content)
        assertEquals(true, recorder.lastFollowupEphemeral)
    }

    @Test
    fun `failed modal reply does not mark the interaction as replied`() {
        val recorder = InteractionRecorder()
        val ctx = slashContext(recorder)

        recorder.nextModalFailure = IllegalStateException("modal failed")

        val error = assertFailsWith<CompletionException> {
            ctx.reply(testModal()).join()
        }

        assertEquals("modal failed", error.cause?.message)
        assertEquals(false, interactionState(ctx, "replied"))
        assertEquals(0, recorder.modalCalls)
    }

    @Test
    fun `failed defer does not mark the interaction as deferred`() {
        val recorder = InteractionRecorder()
        val ctx = slashContext(recorder)

        recorder.nextDeferFailure = IllegalStateException("defer failed")

        val error = assertFailsWith<CompletionException> {
            ctx.defer().join()
        }

        assertEquals("defer failed", error.cause?.message)
        assertEquals(false, interactionState(ctx, "deferred"))

        ctx.defer().join()

        assertEquals(1, recorder.deferCalls)
        assertEquals(0, recorder.editOriginalCalls)
        assertEquals(0, recorder.followupCalls)
    }

    @Test
    fun `failed original edit keeps respond routed to original edit`() {
        val recorder = InteractionRecorder()
        val ctx = slashContext(recorder)

        ctx.defer().join()
        recorder.nextEditOriginalFailure = IllegalStateException("edit failed")

        val error = assertFailsWith<CompletionException> {
            ctx.editOriginal("first edit").join()
        }

        assertEquals("edit failed", error.cause?.message)

        ctx.respond(MessageCreateData.fromContent("retry edit")).join()

        assertEquals(1, recorder.deferCalls)
        assertEquals(1, recorder.editOriginalCalls)
        assertEquals(0, recorder.followupCalls)
        assertEquals("retry edit", recorder.lastEditOriginal?.content)
    }

    @Test
    fun `editOriginal before acknowledgement throws`() {
        val ctx = slashContext(InteractionRecorder())

        val error = assertFailsWith<IllegalStateException> {
            ctx.editOriginal("too early")
        }

        assertEquals(
            "Cannot edit the original interaction response before the interaction has been acknowledged.",
            error.message
        )
    }

    @Test
    fun `followup before acknowledgement throws`() {
        val ctx = slashContext(InteractionRecorder())

        val error = assertFailsWith<IllegalStateException> {
            ctx.followup("too early")
        }

        assertEquals(
            "Cannot send a follow-up message before the interaction has been acknowledged.",
            error.message
        )
    }

    @Test
    fun `reply and modal reply after acknowledgement throw`() {
        val deferredContext = slashContext(InteractionRecorder())
        deferredContext.defer().join()

        val replyError = assertFailsWith<IllegalStateException> {
            deferredContext.reply("already acknowledged")
        }
        val deferredModalError = assertFailsWith<IllegalStateException> {
            deferredContext.reply(testModal())
        }

        val repliedContext = slashContext(InteractionRecorder())
        markReplied(repliedContext, null)

        val repliedModalError = assertFailsWith<IllegalStateException> {
            repliedContext.reply(testModal())
        }

        assertEquals(
            "Cannot reply to the interaction on an already acknowledged interaction.",
            replyError.message
        )
        assertEquals(
            "Cannot reply with a modal on an already acknowledged interaction.",
            deferredModalError.message
        )
        assertEquals(
            "Cannot reply with a modal on an already acknowledged interaction.",
            repliedModalError.message
        )
    }

    @Test
    fun `builder based edit flow uses DSLMessageEditBuilder output`() {
        val recorder = InteractionRecorder()
        val ctx = slashContext(recorder)

        ctx.defer().join()
        ctx.editOriginal {
            setContent("builder edit")
            setComponents(listOf(ActionRow.of(Button.primary("go", "Go"))))
        }.join()

        assertEquals(1, recorder.editOriginalCalls)
        assertEquals("builder edit", recorder.lastEditOriginal?.content)
        assertEquals(1, recorder.lastEditOriginal?.components?.size)
    }
}

private fun markReplied(ctx: InteractionContext, recorder: InteractionRecorder?) {
    InteractionContext::class.java
        .getDeclaredField("replied")
        .apply { isAccessible = true }
        .setBoolean(ctx, true)

    recorder?.apply {
        acknowledged = true
        replyCalls += 1
        lastReplyMessage = MessageCreateData.fromContent("initial reply")
    }
}

private fun interactionState(ctx: InteractionContext, fieldName: String): Boolean {
    return InteractionContext::class.java
        .getDeclaredField(fieldName)
        .apply { isAccessible = true }
        .getBoolean(ctx)
}

private class InteractionRecorder {
    var acknowledged: Boolean = false
    var lastReplyMessage: MessageCreateData? = null
    var lastReplyEphemeral: Boolean? = null
    var lastDeferredEphemeral: Boolean? = null
    var lastFollowupMessage: MessageCreateData? = null
    var lastFollowupEphemeral: Boolean? = null
    var lastEditOriginal: MessageEditData? = null
    var lastModal: Modal? = null
    var replyCalls: Int = 0
    var deferCalls: Int = 0
    var followupCalls: Int = 0
    var editOriginalCalls: Int = 0
    var modalCalls: Int = 0
    var nextReplyFailure: Throwable? = null
    var nextDeferFailure: Throwable? = null
    var nextEditOriginalFailure: Throwable? = null
    var nextModalFailure: Throwable? = null
    var nextFollowupFailure: Throwable? = null

    val hook: InteractionHook by lazy(NONE) {
        interactionHook(this)
    }

    fun consumeNextReplyFailure(): Throwable? = nextReplyFailure.also { nextReplyFailure = null }

    fun consumeNextDeferFailure(): Throwable? = nextDeferFailure.also { nextDeferFailure = null }

    fun consumeNextEditOriginalFailure(): Throwable? = nextEditOriginalFailure.also { nextEditOriginalFailure = null }

    fun consumeNextModalFailure(): Throwable? = nextModalFailure.also { nextModalFailure = null }

    fun consumeNextFollowupFailure(): Throwable? = nextFollowupFailure.also { nextFollowupFailure = null }
}

private fun slashContext(
    recorder: InteractionRecorder,
    locale: DiscordLocale = DiscordLocale.ENGLISH_US,
    guildLocale: DiscordLocale = DiscordLocale.FRENCH
): SlashContext {
    val client = CommandClient.builder()
        .setPrefixes("!")
        .configureDefaultHelpCommand { enabled = false }
        .build()
    client.commands.register(InteractionResponseCog())

    return SlashContext(
        commandClient = client,
        event = slashEvent(recorder, locale, guildLocale),
        invokedCommand = client.commands.findSlashCommand("interaction")!!
    )
}

private fun slashEvent(
    recorder: InteractionRecorder,
    locale: DiscordLocale,
    guildLocale: DiscordLocale
): SlashCommandInteractionEvent {
    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val interaction = proxyWithArgs<SlashCommandInteraction> { method, args ->
        when (method.name) {
            "getName" -> "interaction"
            "getOptions" -> emptyList<Any>()
            "getUser" -> userProxy(42L)
            "getChannel" -> channel
            "getGuild" -> null
            "getMember" -> null
            "isFromGuild" -> false
            "isAcknowledged" -> recorder.acknowledged
            "getJDA" -> jda
            "getCommandType" -> JdaCommand.Type.SLASH
            "getHook" -> recorder.hook
            "getUserLocale" -> locale
            "getGuildLocale" -> guildLocale
            "reply" -> {
                val message = args!![0] as MessageCreateData
                replyCallbackAction({ recorder.consumeNextReplyFailure() }) { ephemeral ->
                    recorder.acknowledged = true
                    recorder.replyCalls += 1
                    recorder.lastReplyMessage = message
                    recorder.lastReplyEphemeral = ephemeral
                }.also { return@proxyWithArgs it }
            }

            "deferReply" -> {
                val ephemeral = args?.firstOrNull() as? Boolean ?: false
                replyCallbackAction({ recorder.consumeNextDeferFailure() }) { _ ->
                    recorder.acknowledged = true
                    recorder.deferCalls += 1
                    recorder.lastDeferredEphemeral = ephemeral
                }.also { return@proxyWithArgs it }
            }

            "replyModal" -> {
                val modal = args!![0] as Modal
                modalCallbackAction({ recorder.consumeNextModalFailure() }) {
                    recorder.acknowledged = true
                    recorder.modalCalls += 1
                    recorder.lastModal = modal
                }.also { return@proxyWithArgs it }
            }

            else -> defaultValue(method.returnType)
        }
    }

    return SlashCommandInteractionEvent(jda, 0L, interaction)
}

private fun interactionHook(recorder: InteractionRecorder): InteractionHook {
    return proxyWithArgs { method, args ->
        when (method.name) {
            "sendMessage" -> {
                val message = args!![0] as MessageCreateData
                webhookMessageCreateAction(recorder, message)
            }

            "editOriginal" -> {
                val message = args!![0] as MessageEditData
                webhookMessageEditAction(recorder, message)
            }

            else -> defaultValue(method.returnType)
        }
    }
}

private fun replyCallbackAction(
    consumeFailure: () -> Throwable?,
    onSubmit: (Boolean) -> Unit
): ReplyCallbackAction {
    lateinit var action: ReplyCallbackAction
    var ephemeral = false

    action = proxyWithArgs { method, args ->
        when (method.name) {
            "setEphemeral" -> {
                ephemeral = args!![0] as Boolean
                action
            }

            "submit" -> {
                consumeFailure()?.let { return@proxyWithArgs CompletableFuture.failedFuture<InteractionHook>(it) }
                onSubmit(ephemeral)
                CompletableFuture.completedFuture(proxy<InteractionHook>())
            }

            else -> defaultValue(method.returnType)
        }
    }

    return action
}

private fun modalCallbackAction(
    consumeFailure: () -> Throwable?,
    onSubmit: () -> Unit
): ModalCallbackAction {
    return proxyWithArgs { method, _ ->
        when (method.name) {
            "submit" -> {
                consumeFailure()?.let { return@proxyWithArgs CompletableFuture.failedFuture<Void>(it) }
                onSubmit()
                CompletableFuture.completedFuture(null)
            }

            else -> defaultValue(method.returnType)
        }
    }
}

private fun webhookMessageCreateAction(
    recorder: InteractionRecorder,
    message: MessageCreateData
): WebhookMessageCreateAction<Message> {
    lateinit var action: WebhookMessageCreateAction<Message>
    var ephemeral = false

    action = proxyWithArgs { method, args ->
        when (method.name) {
            "setEphemeral" -> {
                ephemeral = args!![0] as Boolean
                action
            }

            "submit" -> {
                recorder.consumeNextFollowupFailure()?.let {
                    return@proxyWithArgs CompletableFuture.failedFuture<Message>(it)
                }
                recorder.followupCalls += 1
                recorder.lastFollowupMessage = message
                recorder.lastFollowupEphemeral = ephemeral
                CompletableFuture.completedFuture(messageProxy(101L))
            }

            else -> defaultValue(method.returnType)
        }
    }

    return action
}

private fun webhookMessageEditAction(
    recorder: InteractionRecorder,
    message: MessageEditData
): WebhookMessageEditAction<Message> {
    return proxyWithArgs { method, _ ->
        when (method.name) {
            "submit" -> {
                recorder.consumeNextEditOriginalFailure()?.let {
                    return@proxyWithArgs CompletableFuture.failedFuture<Message>(it)
                }
                recorder.editOriginalCalls += 1
                recorder.lastEditOriginal = message
                CompletableFuture.completedFuture(messageProxy(102L))
            }

            else -> defaultValue(method.returnType)
        }
    }
}

private fun messageContext(invokedCommand: me.devoxin.flight.internal.entities.Executable): MessageContext {
    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val message = proxy<Message> { method ->
        when (method.name) {
            "getContentRaw" -> "!interaction"
            "getJDA" -> jda
            "getAuthor" -> userProxy(7L)
            "getChannel" -> channel
            "getMember" -> null
            "isFromGuild" -> false
            "getChannelType" -> ChannelType.PRIVATE
            else -> defaultValue(method.returnType)
        }
    }

    return MessageContext(
        commandClient = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .build(),
        event = MessageReceivedEvent(jda, 0L, message),
        trigger = "!",
        invokedCommand = invokedCommand
    )
}

private fun testModal(): Modal {
    return Modal.create("test-modal", "Test Modal")
        .addComponents(
            Label.of("Name", TextInput.of("name", TextInputStyle.SHORT))
        )
        .build()
}

private class InteractionResponseCog : Cog {
    @Command(description = "Interaction response test")
    fun interaction(ctx: SlashContext) = Unit
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
