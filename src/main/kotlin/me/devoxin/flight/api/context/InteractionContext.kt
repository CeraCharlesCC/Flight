package me.devoxin.flight.api.context

import kotlinx.coroutines.future.await
import me.devoxin.flight.api.CommandClient
import me.devoxin.flight.api.util.DSLMessageCreateBuilder
import me.devoxin.flight.api.util.DSLMessageEditBuilder
import me.devoxin.flight.internal.entities.Executable
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.interaction.command.GenericCommandInteractionEvent
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import net.dv8tion.jda.api.utils.messages.MessageEditData
import java.util.concurrent.CompletableFuture

abstract class InteractionContext(
    override val commandClient: CommandClient,
    baseEvent: GenericCommandInteractionEvent,
    override val invokedCommand: Executable
) : Context {
    open val event: GenericCommandInteractionEvent = baseEvent
    override val jda: JDA = baseEvent.jda
    override val author = baseEvent.user
    override val guild = baseEvent.guild
    override val member = baseEvent.member
    override val messageChannel: MessageChannel = baseEvent.channel as MessageChannel
    override val guildChannel: GuildMessageChannel? = baseEvent.takeIf { it.isFromGuild }?.guildChannel as? GuildMessageChannel
    override val isFromGuild = baseEvent.isFromGuild
    override val locale: DiscordLocale
        get() = event.userLocale
    override val guildLocale: DiscordLocale
        get() = event.guildLocale

    var replied = false
        protected set
    var deferred = false
        protected set

    val isAcknowledged: Boolean
        get() = deferred || replied || event.isAcknowledged

    fun defer(ephemeral: Boolean = false): CompletableFuture<InteractionHook> = defer0(ephemeral)

    suspend fun deferAsync(ephemeral: Boolean = false): InteractionHook {
        return defer(ephemeral).await()
    }

    /**
     * This will only call the raw interaction reply API with no special handling.
     * Use [respond] or [respondAsync] to handle things such as deferral or already acknowledged events.
     */
    fun reply(content: String, ephemeral: Boolean = false): CompletableFuture<InteractionHook> {
        return reply(MessageCreateData.fromContent(content), ephemeral)
    }

    /**
     * This will only call the raw interaction reply API with no special handling.
     * Use [respond] or [respondAsync] to handle things such as deferral or already acknowledged events.
     */
    fun reply(builder: DSLMessageCreateBuilder.() -> Unit, ephemeral: Boolean = false): CompletableFuture<InteractionHook> {
        val built = DSLMessageCreateBuilder().apply(builder).build()
        return reply(built, ephemeral)
    }

    /**
     * This will only call the raw interaction reply API with no special handling.
     * Use [respond] or [respondAsync] to handle things such as deferral or already acknowledged events.
     */
    fun reply(modal: Modal): CompletableFuture<Void> {
        requireUnacknowledged("reply with a modal")
        return updateStateOnSuccess(event.replyModal(modal).submit()) {
            replied = true
        }
    }

    /**
     * This will only call the raw interaction reply API with no special handling.
     * Use [respond] or [respondAsync] to handle things such as deferral or already acknowledged events.
     */
    fun reply(message: MessageCreateData, ephemeral: Boolean = false): CompletableFuture<InteractionHook> {
        requireUnacknowledged("reply to the interaction")
        return updateStateOnSuccess(event.reply(message).setEphemeral(ephemeral).submit()) {
            replied = true
        }
    }

    /**
     * This will only call the raw interaction reply API with no special handling.
     * Use [respond] or [respondAsync] to handle things such as deferral or already acknowledged events.
     */
    suspend fun replyAsync(content: String, ephemeral: Boolean = false): InteractionHook {
        return reply(content, ephemeral).await()
    }

    /**
     * This will only call the raw interaction reply API with no special handling.
     * Use [respond] or [respondAsync] to handle things such as deferral or already acknowledged events.
     */
    suspend fun replyAsync(builder: DSLMessageCreateBuilder.() -> Unit, ephemeral: Boolean = false): InteractionHook {
        return reply(builder, ephemeral).await()
    }

    /**
     * This will only call the raw interaction reply API with no special handling.
     * Use [respond] or [respondAsync] to handle things such as deferral or already acknowledged events.
     */
    suspend fun replyAsync(modal: Modal) {
        reply(modal).await()
    }

    /**
     * This will only call the raw interaction reply API with no special handling.
     * Use [respond] or [respondAsync] to handle things such as deferral or already acknowledged events.
     */
    suspend fun replyAsync(message: MessageCreateData, ephemeral: Boolean = false): InteractionHook {
        return reply(message, ephemeral).await()
    }

    /**
     * This will only call [InteractionHook.editOriginal] with no special handling.
     */
    fun editOriginal(content: String): CompletableFuture<Message> {
        return editOriginal(MessageEditData.fromContent(content))
    }

    /**
     * This will only call [InteractionHook.editOriginal] with no special handling.
     */
    fun editOriginal(builder: DSLMessageEditBuilder.() -> Unit): CompletableFuture<Message> {
        val built = DSLMessageEditBuilder().apply(builder).build()
        return editOriginal(built)
    }

    /**
     * This will only call [InteractionHook.editOriginal] with no special handling.
     */
    fun editOriginal(message: MessageEditData): CompletableFuture<Message> {
        requireAcknowledged("edit the original interaction response")
        return updateStateOnSuccess(event.hook.editOriginal(message).submit()) {
            replied = true
        }
    }

    /**
     * This will only call [InteractionHook.editOriginal] with no special handling.
     */
    suspend fun editOriginalAsync(content: String): Message {
        return editOriginal(content).await()
    }

    /**
     * This will only call [InteractionHook.editOriginal] with no special handling.
     */
    suspend fun editOriginalAsync(builder: DSLMessageEditBuilder.() -> Unit): Message {
        return editOriginal(builder).await()
    }

    /**
     * This will only call [InteractionHook.editOriginal] with no special handling.
     */
    suspend fun editOriginalAsync(message: MessageEditData): Message {
        return editOriginal(message).await()
    }

    /**
     * This will only call [InteractionHook.sendMessage] with no special handling.
     */
    fun followup(content: String, ephemeral: Boolean = false): CompletableFuture<Message> {
        return followup(MessageCreateData.fromContent(content), ephemeral)
    }

    /**
     * This will only call [InteractionHook.sendMessage] with no special handling.
     */
    fun followup(builder: DSLMessageCreateBuilder.() -> Unit, ephemeral: Boolean = false): CompletableFuture<Message> {
        val built = DSLMessageCreateBuilder().apply(builder).build()
        return followup(built, ephemeral)
    }

    /**
     * This will only call [InteractionHook.sendMessage] with no special handling.
     */
    fun followup(message: MessageCreateData, ephemeral: Boolean = false): CompletableFuture<Message> {
        requireAcknowledged("send a follow-up message")
        return event.hook.sendMessage(message).setEphemeral(ephemeral).submit()
    }

    /**
     * Alias for [followup]. Prefer the explicit followup naming for interaction webhook messages.
     */
    @Deprecated(
        message = "Use followup(content, ephemeral) for interaction follow-up messages.",
        replaceWith = ReplaceWith("followup(content, ephemeral)")
    )
    fun send(content: String, ephemeral: Boolean = false): CompletableFuture<Message> {
        return followup(content, ephemeral)
    }

    /**
     * Alias for [followup]. Prefer the explicit followup naming for interaction webhook messages.
     */
    @Deprecated(
        message = "Use followup(builder, ephemeral) for interaction follow-up messages.",
        replaceWith = ReplaceWith("followup(builder, ephemeral)")
    )
    fun send(builder: DSLMessageCreateBuilder.() -> Unit, ephemeral: Boolean = false): CompletableFuture<Message> {
        return followup(builder, ephemeral)
    }

    /**
     * Alias for [followup]. Prefer the explicit followup naming for interaction webhook messages.
     */
    @Deprecated(
        message = "Use followup(message, ephemeral) for interaction follow-up messages.",
        replaceWith = ReplaceWith("followup(message, ephemeral)")
    )
    fun send(message: MessageCreateData, ephemeral: Boolean = false): CompletableFuture<Message> {
        return followup(message, ephemeral)
    }

    /**
     * This will only call [InteractionHook.sendMessage] with no special handling.
     */
    suspend fun followupAsync(content: String, ephemeral: Boolean = false): Message {
        return followupAsync(MessageCreateData.fromContent(content), ephemeral)
    }

    /**
     * This will only call [InteractionHook.sendMessage] with no special handling.
     */
    suspend fun followupAsync(builder: DSLMessageCreateBuilder.() -> Unit, ephemeral: Boolean = false): Message {
        val built = DSLMessageCreateBuilder().apply(builder).build()
        return followupAsync(built, ephemeral)
    }

    /**
     * This will only call [InteractionHook.sendMessage] with no special handling.
     */
    suspend fun followupAsync(message: MessageCreateData, ephemeral: Boolean = false): Message {
        return followup(message, ephemeral).await()
    }

    /**
     * Alias for [followupAsync]. Prefer the explicit followup naming for interaction webhook messages.
     */
    @Deprecated(
        message = "Use followupAsync(content, ephemeral) for interaction follow-up messages.",
        replaceWith = ReplaceWith("followupAsync(content, ephemeral)")
    )
    suspend fun sendAsync(content: String, ephemeral: Boolean = false): Message {
        return followupAsync(content, ephemeral)
    }

    /**
     * Alias for [followupAsync]. Prefer the explicit followup naming for interaction webhook messages.
     */
    @Deprecated(
        message = "Use followupAsync(builder, ephemeral) for interaction follow-up messages.",
        replaceWith = ReplaceWith("followupAsync(builder, ephemeral)")
    )
    suspend fun sendAsync(builder: DSLMessageCreateBuilder.() -> Unit, ephemeral: Boolean = false): Message {
        return followupAsync(builder, ephemeral)
    }

    /**
     * Alias for [followupAsync]. Prefer the explicit followup naming for interaction webhook messages.
     */
    @Deprecated(
        message = "Use followupAsync(message, ephemeral) for interaction follow-up messages.",
        replaceWith = ReplaceWith("followupAsync(message, ephemeral)")
    )
    suspend fun sendAsync(message: MessageCreateData, ephemeral: Boolean = false): Message {
        return followupAsync(message, ephemeral)
    }

    /**
     * Convenience method which handles replying the correct way for you.
     *
     * The [ephemeral] setting is ignored if the interaction is deferred.
     * Instead, the ephemeral setting when deferring is used. This is a Discord limitation.
     */
    fun respond(content: String, ephemeral: Boolean = false): CompletableFuture<*> {
        return respond(MessageCreateData.fromContent(content), ephemeral)
    }

    /**
     * Convenience method which handles replying the correct way for you.
     *
     * The [ephemeral] setting is ignored if the interaction is deferred.
     * Instead, the ephemeral setting when deferring is used. This is a Discord limitation.
     */
    fun respond(message: MessageCreateData, ephemeral: Boolean = false): CompletableFuture<*> {
        return respond0(message, ephemeral)
    }

    /**
     * Convenience method which handles replying the correct way for you.
     *
     * The [ephemeral] setting is ignored if the interaction is deferred.
     * Instead, the ephemeral setting when deferring is used. This is a Discord limitation.
     */
    fun respond(builder: DSLMessageCreateBuilder.() -> Unit, ephemeral: Boolean = false): CompletableFuture<*> {
        val built = DSLMessageCreateBuilder().apply(builder).build()
        return respond(built, ephemeral)
    }

    /**
     * Convenience method which handles replying the correct way for you.
     *
     * The [ephemeral] setting is ignored if the interaction is deferred.
     * Instead, the ephemeral setting when deferring is used. This is a Discord limitation.
     */
    suspend fun respondAsync(content: String, ephemeral: Boolean = false) {
        respond(content, ephemeral).await()
    }

    /**
     * Convenience method which handles replying the correct way for you.
     *
     * The [ephemeral] setting is ignored if the interaction is deferred.
     * Instead, the ephemeral setting when deferring is used. This is a Discord limitation.
     */
    suspend fun respondAsync(message: MessageCreateData, ephemeral: Boolean = false) {
        respond(message, ephemeral).await()
    }

    /**
     * Convenience method which handles replying the correct way for you.
     *
     * The [ephemeral] setting is ignored if the interaction is deferred.
     * Instead, the ephemeral setting when deferring is used. This is a Discord limitation.
     */
    suspend fun respondAsync(builder: DSLMessageCreateBuilder.() -> Unit, ephemeral: Boolean = false) {
        respond(builder, ephemeral).await()
    }

    internal fun defer0(ephemeral: Boolean): CompletableFuture<InteractionHook> {
        if (isAcknowledged) {
            return CompletableFuture.completedFuture(event.hook)
        }

        return updateStateOnSuccess(event.deferReply(ephemeral).submit()) {
            deferred = true
        }
    }

    internal fun respond0(message: MessageCreateData, ephemeral: Boolean = false): CompletableFuture<*> {
        return when {
            replied -> followup(message, ephemeral)
            isAcknowledged -> editOriginal(MessageEditData.fromCreateData(message))
            else -> reply(message, ephemeral)
        }
    }

    private fun requireUnacknowledged(action: String) {
        if (isAcknowledged) {
            throw IllegalStateException("Cannot $action on an already acknowledged interaction.")
        }
    }

    private fun requireAcknowledged(action: String) {
        if (!isAcknowledged) {
            throw IllegalStateException("Cannot $action before the interaction has been acknowledged.")
        }
    }

    private fun <T> updateStateOnSuccess(
        future: CompletableFuture<T>,
        onSuccess: () -> Unit
    ): CompletableFuture<T> {
        return future.whenComplete { _, throwable ->
            if (throwable == null) {
                onSuccess()
            }
        }
    }
}
