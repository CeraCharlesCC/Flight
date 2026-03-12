package me.devoxin.flight.api

import me.devoxin.flight.api.check.CheckType
import me.devoxin.flight.api.command.CommandRegistry
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.api.context.InteractionContext
import me.devoxin.flight.api.context.MessageCommandContext
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.context.SlashContext
import me.devoxin.flight.api.context.UserCommandContext
import me.devoxin.flight.api.cooldown.BucketType
import me.devoxin.flight.api.cooldown.CooldownProvider
import me.devoxin.flight.api.execution.CommandExecutionOptions
import me.devoxin.flight.api.error.CommandErrorHandler
import me.devoxin.flight.api.error.CommandFailure
import me.devoxin.flight.api.exceptions.BadArgument
import me.devoxin.flight.api.hooks.CommandEventAdapter
import me.devoxin.flight.api.localization.CommandLocalizationProvider
import me.devoxin.flight.api.prefix.PrefixProvider
import me.devoxin.flight.api.sync.CommandSyncOptions
import me.devoxin.flight.api.sync.CommandSyncPlan
import me.devoxin.flight.api.sync.CommandSyncResult
import me.devoxin.flight.internal.arguments.ArgParser
import me.devoxin.flight.internal.entities.WaitingEvent
import me.devoxin.flight.internal.execution.CommandExecutionCoordinator
import me.devoxin.flight.internal.sync.CommandSyncExecutor
import me.devoxin.flight.internal.sync.CommandSyncPlanner
import me.devoxin.flight.internal.sync.JdaCommandSyncBackend
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel
import net.dv8tion.jda.api.events.Event
import net.dv8tion.jda.api.events.GenericEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.SubscribeEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.reflect.KParameter

class CommandClient(
    private val prefixProvider: PrefixProvider,
    val cooldownProvider: CooldownProvider,
    private val ignoreBots: Boolean,
    private val eventListeners: List<CommandEventAdapter>,
    executionOptions: CommandExecutionOptions,
    val ownerIds: MutableSet<Long>,
    val commandLocalizationProvider: CommandLocalizationProvider? = null,
    val errorHandler: CommandErrorHandler? = null
) {
    private val waiterScheduler = Executors.newSingleThreadScheduledExecutor()
    private val pendingEvents = ConcurrentHashMap<Class<*>, MutableSet<WaitingEvent<*>>>()
    private val executionCoordinator = CommandExecutionCoordinator(executionOptions)
    val commands = CommandRegistry()

    /**
     * Checks whether the provided [message] is a command.
     *
     * @param message
     *        The message to check.
     * @return True, if the message is a command.
     */
    fun isCommand(message: Message): Boolean {
        val trigger = resolveTrigger(message)
            ?: return false

        if (trigger.length == message.contentRaw.length) {
            return false
        }

        val args = message.contentRaw.substring(trigger.length).split(" +".toRegex()).toMutableList()
        val command = args.removeAt(0).lowercase()

        return commands.findMessageCommand(command) != null
    }

    /**
     * Shuts down the internal waiter scheduler.
     */
    fun shutdown() {
        executionCoordinator.shutdown()
        waiterScheduler.shutdown()
        cooldownProvider.shutdown()
    }

    private fun onMessageReceived(event: MessageReceivedEvent) {
        if (ignoreBots && (event.author.isBot || event.isWebhookMessage)) {
            return
        }

        val trigger = resolveTrigger(event.message)
            ?: return

        if (trigger.length == event.message.contentRaw.length) {
            return
        }

        val args = event.message.contentRaw.substring(trigger.length).split(" +".toRegex()).toMutableList()
        val command = args.removeAt(0).lowercase()

        val cmd = commands.findMessageCommand(command)
            ?: return handleFailure(
                CommandFailure.UnknownCommandFailure(event, command, args)
            ) { it.onUnknownCommand(event, command, args) }

        val firstToken = args.getOrNull(0)
        val secondToken = args.getOrNull(1)
        val subcommand = cmd.findSubcommandPath(firstToken, secondToken, ContextType.MESSAGE)

        if (subcommand == null && cmd.hasSubcommandGroup(firstToken)) {
            return handleFailure(
                CommandFailure.UnknownCommandFailure(event, command, args)
            ) { it.onUnknownCommand(event, command, args) }
        }

        val invoked = subcommand ?: cmd

        if (subcommand != null) {
            args.removeAt(0)

            if (subcommand.isGrouped && args.isNotEmpty()) {
                args.removeAt(0)
            }
        }

        val ctx = MessageContext(this, event, trigger, invoked)

        if (isOnCooldown(cmd, ctx)) { // This function dispatches the event.
            return
        }

        if (!shouldExecuteCommand(ctx, cmd)) {
            return
        }

        val arguments: HashMap<KParameter, Any?>

        try {
            arguments = ArgParser.parseArguments(invoked, ctx, args, cmd.properties.argDelimiter)
        } catch (e: BadArgument) {
            return handleFailure(
                CommandFailure.BadArgumentFailure(ctx, cmd, e)
            ) { it.onBadArgument(ctx, cmd, e) }
        } catch (e: Throwable) {
            return handleFailure(
                CommandFailure.ParseFailure(ctx, cmd, e)
            ) { it.onParseError(ctx, cmd, e) }
        }

        setCooldown(cmd, ctx)
        executionCoordinator.executeCommand(invoked, ctx, arguments, createCompletionCallback(ctx, cmd))
    }

    private fun onSlashCommand(event: SlashCommandInteractionEvent) {
        val cmd = commands.findSlashCommand(event.name) ?: return
        val subcommand = cmd.findSubcommand(event.subcommandGroup, event.subcommandName, ContextType.SLASH)
        val invoked = when {
            event.subcommandGroup != null || event.subcommandName != null -> subcommand ?: return
            else -> cmd
        }
        val ctx = SlashContext(this, event, invoked)

        executeInteractionCommand(cmd, invoked, ctx) {
            invoked.resolveArguments(event.options)
        }
    }

    private fun onUserCommand(event: UserContextInteractionEvent) {
        val cmd = commands.findUserCommand(event.name) ?: return
        val ctx = UserCommandContext(this, event, cmd)

        executeInteractionCommand(cmd, cmd, ctx) {
            buildSelectedTargetArguments(cmd, event.target)
        }
    }

    private fun onMessageContextCommand(event: MessageContextInteractionEvent) {
        val cmd = commands.findMessageContextCommand(event.name) ?: return
        val ctx = MessageCommandContext(this, event, cmd)

        executeInteractionCommand(cmd, cmd, ctx) {
            buildSelectedTargetArguments(cmd, event.target)
        }
    }

    private fun executeInteractionCommand(
        command: CommandFunction,
        invoked: me.devoxin.flight.internal.entities.Executable,
        ctx: InteractionContext,
        resolveArguments: () -> HashMap<KParameter, Any?>
    ) {
        if (isOnCooldown(command, ctx)) {
            return
        }

        if (!shouldExecuteCommand(ctx, command)) {
            return
        }

        val arguments = resolveArguments()

        setCooldown(command, ctx)
        executionCoordinator.executeCommand(invoked, ctx, arguments, createCompletionCallback(ctx, command))
    }

    private fun buildSelectedTargetArguments(command: CommandFunction, target: Any): HashMap<KParameter, Any?> {
        return hashMapOf<KParameter, Any?>().apply {
            command.selectedTargetParameter?.let { put(it, target) }
        }
    }

    private fun onAutocomplete(event: CommandAutoCompleteInteractionEvent) {
        val commandName = event.name

        val command = commands.findSlashCommand(commandName)
            ?: return

        val subcommand = command.findSubcommand(event.subcommandGroup, event.subcommandName, ContextType.SLASH)

        val executable = when {
            event.subcommandGroup != null || event.subcommandName != null -> subcommand ?: return
            else -> command
        }
        val argument = executable.arguments.find { it.slashFriendlyName == event.focusedOption.name }
            ?: return

        val cb = { err: Throwable? ->
            if (err != null) {
                handleFailure(
                    CommandFailure.AutocompleteFailure(event, err)
                ) { it.onAutocompleteError(event, err) }
            }
        }

        executionCoordinator.executeAutocomplete(argument, event, cb)
    }

    private fun resolveTrigger(message: Message): String? {
        val content = message.contentRaw
        var resolved: String? = null

        for (prefix in prefixProvider.provide(message)) {
            if (!content.startsWith(prefix)) {
                continue
            }

            if (resolved == null || prefix.length > resolved.length) {
                resolved = prefix
            }
        }

        return resolved
    }


    // +-------------------+
    // | Execution-Related |
    // +-------------------+
    @SubscribeEvent
    fun onEvent(event: GenericEvent) {
        onGenericEvent(event)

        try {
            when (event) {
                is ReadyEvent -> onReady(event)
                is MessageReceivedEvent -> onMessageReceived(event)
                is SlashCommandInteractionEvent -> onSlashCommand(event)
                is UserContextInteractionEvent -> onUserCommand(event)
                is MessageContextInteractionEvent -> onMessageContextCommand(event)
                is CommandAutoCompleteInteractionEvent -> onAutocomplete(event)
                //else -> println(event)
            }
        } catch (e: Throwable) {
            handleFailure(CommandFailure.FrameworkFailure(e)) { it.onInternalError(e) }
        }
    }

    private fun onReady(event: ReadyEvent) {
        if (ownerIds.isEmpty()) {
            event.jda.retrieveApplicationInfo().queue {
                ownerIds.add(it.owner.idLong)
            }
        }
    }

    private fun onGenericEvent(event: GenericEvent) {
        val key = event::class.java
        val matched = mutableListOf<WaitingEvent<*>>()

        pendingEvents.computeIfPresent(key) { _, events ->
            events.removeIf { waiter ->
                if (waiter.check(event)) {
                    matched += waiter
                    true
                } else {
                    false
                }
            }

            if (events.isEmpty()) null else events
        }

        if (matched.isEmpty()) {
            return
        }

        val jdaEvent = event as Event
        matched.forEach { it.accept(jdaEvent) }
    }

    inline fun <reified T : Event> waitFor(
        noinline predicate: (T) -> Boolean,
        timeout: Long
    ): CompletableFuture<T> {
        return waitFor(T::class.java, predicate, timeout)
    }

    fun <T : Event> waitFor(
        event: Class<T>,
        predicate: (T) -> Boolean,
        timeout: Long
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val we = WaitingEvent(event, predicate, future)

        val set = pendingEvents.computeIfAbsent(event) {
            ConcurrentHashMap.newKeySet<WaitingEvent<*>>()
        }
        set.add(we)

        if (timeout > 0) {
            waiterScheduler.schedule({
                if (!future.isDone) {
                    future.completeExceptionally(TimeoutException())

                    pendingEvents.compute(event) { _, existing ->
                        existing?.apply { remove(we) }?.takeIf { it.isNotEmpty() }
                    }
                }
            }, timeout, TimeUnit.MILLISECONDS)
        }

        return future
    }

    private fun dispatchSafely(invoker: (CommandEventAdapter) -> Unit) {
        try {
            eventListeners.forEach(invoker)
        } catch (e: Throwable) {
            try {
                eventListeners.forEach { it.onInternalError(e) }
            } catch (inner: Throwable) {
                log.error("An uncaught exception occurred during event dispatch!", inner)
            }
        }
    }

    private fun handleFailure(failure: CommandFailure, adapterInvoker: (CommandEventAdapter) -> Unit) {
        runErrorHandler(failure)
        dispatchSafely(adapterInvoker)
    }

    private fun runErrorHandler(failure: CommandFailure) {
        val handler = errorHandler ?: return

        try {
            handler.handle(failure)
        } catch (throwable: Throwable) {
            dispatchSafely { it.onInternalError(throwable) }
        }
    }

    /**
    * Builds a deterministic sync plan for the currently registered application commands.
     *
     * Sync planning is authoritative per targeted scope: any targeted global or guild scope is treated as the
     * complete desired command set, so an empty targeted scope represents a clear operation.
     */
    fun planCommandSync(options: CommandSyncOptions = CommandSyncOptions()): CommandSyncPlan {
        return createSyncPlanner().plan(options).toPlan()
    }

    /**
    * Synchronizes the currently registered application commands to Discord using authoritative scope replacement.
     *
    * Warning: every targeted scope is replaced in full. If a targeted global or guild scope has zero application
     * commands after planning, Flight will issue an empty sync for that scope and Discord will delete its existing
     * application commands there.
     */
    fun syncCommands(
        jda: JDA,
        options: CommandSyncOptions = CommandSyncOptions()
    ): CompletableFuture<CommandSyncResult> {
        val plan = createSyncPlanner().plan(options)
        return CommandSyncExecutor(JdaCommandSyncBackend(jda)).execute(plan)
    }

    private fun shouldExecuteCommand(ctx: Context, cmd: CommandFunction): Boolean {
        val props = cmd.properties
        val subcommandProperties = (ctx.invokedCommand as? SubCommandFunction)?.properties
        val requiresGuildContext = props.requiresGuildContext || subcommandProperties?.guildOnly == true

        val contextType = ctx.contextType
        if (!cmd.supportsContext(contextType)) {
            handleFailure(
                CommandFailure.CheckFailure(ctx, cmd, CheckType.EXECUTION_CONTEXT)
            ) { it.onCheckFailed(ctx, cmd, CheckType.EXECUTION_CONTEXT) }
            return false
        }

        if (props.developerOnly && !ownerIds.contains(ctx.author.idLong)) {
            handleFailure(
                CommandFailure.CheckFailure(ctx, cmd, CheckType.DEVELOPER_CHECK)
            ) { it.onCheckFailed(ctx, cmd, CheckType.DEVELOPER_CHECK) }
            return false
        }

        if (!ctx.isFromGuild && requiresGuildContext) {
            handleFailure(
                CommandFailure.CheckFailure(ctx, cmd, CheckType.GUILD_CHECK)
            ) { it.onCheckFailed(ctx, cmd, CheckType.GUILD_CHECK) }
            return false
        }

        if (cmd.guildIds.isNotEmpty()) {
            val guildId = ctx.guild?.idLong

            if (guildId == null || guildId !in cmd.guildIds) {
                handleFailure(
                    CommandFailure.CheckFailure(ctx, cmd, CheckType.GUILD_ID_CHECK)
                ) { it.onCheckFailed(ctx, cmd, CheckType.GUILD_ID_CHECK) }
                return false
            }
        }

        if (ctx.isFromGuild) {
            if (props.userPermissions.isNotEmpty()) {
                val userCheck = props.userPermissions.filterNot { ctx.member!!.hasPermission(ctx.guildChannel!!, it) }

                if (userCheck.isNotEmpty()) {
                    handleFailure(
                        CommandFailure.MissingUserPermissionsFailure(ctx, cmd, userCheck)
                    ) { it.onUserMissingPermissions(ctx, cmd, userCheck) }
                    return false
                }
            }

            if (props.botPermissions.isNotEmpty()) {
                val botCheck =
                    props.botPermissions.filterNot { ctx.guild!!.selfMember.hasPermission(ctx.guildChannel!!, it) }

                if (botCheck.isNotEmpty()) {
                    handleFailure(
                        CommandFailure.MissingBotPermissionsFailure(ctx, cmd, botCheck)
                    ) { it.onBotMissingPermissions(ctx, cmd, botCheck) }
                    return false
                }
            }

            if (props.nsfw && (ctx.guildChannel as? StandardGuildMessageChannel)?.isNSFW != true) {
                handleFailure(
                    CommandFailure.CheckFailure(ctx, cmd, CheckType.NSFW_CHECK)
                ) { it.onCheckFailed(ctx, cmd, CheckType.NSFW_CHECK) }
                return false
            }
        } else {
            if (requiresGuildContext) {
                handleFailure(
                    CommandFailure.CheckFailure(ctx, cmd, CheckType.GUILD_CHECK)
                ) { it.onCheckFailed(ctx, cmd, CheckType.GUILD_CHECK) }
                return false
            }
        }

        if (!eventListeners.all { it.onCommandPreInvoke(ctx, cmd) }) {
            return false
        }

        if (!cmd.cog.localCheck(ctx, cmd)) {
            handleFailure(
                CommandFailure.CheckFailure(ctx, cmd, CheckType.LOCAL_CHECK)
            ) { it.onCheckFailed(ctx, cmd, CheckType.LOCAL_CHECK) }
            return false
        }

        return true
    }

    private fun createCompletionCallback(
        ctx: Context,
        cmd: CommandFunction
    ): (Boolean, Throwable?) -> Unit = { success, err ->
        if (err != null) {
            val handled = cmd.cog.onCommandError(ctx, cmd, err)
            val failure = CommandFailure.CommandExecutionFailure(ctx, cmd, err)

            if (!handled) {
                runErrorHandler(failure)
            }

            dispatchSafely { it.onCommandError(ctx, cmd, err) }
        }

        dispatchSafely { it.onCommandPostInvoke(ctx, cmd, !success) }
    }

    private fun isOnCooldown(cmd: CommandFunction, ctx: Context): Boolean {
        if (cmd.cooldown != null) {
            val entityId = when (cmd.cooldown.bucket) {
                BucketType.USER -> ctx.author.idLong
                BucketType.GUILD -> ctx.guild?.idLong //?: ctx.messageChannel.idLong
                BucketType.GLOBAL -> -1
            }

            if (entityId != null) {
                if (cooldownProvider.isOnCooldown(entityId, cmd.cooldown.bucket, cmd)) {
                    val time = cooldownProvider.getCooldownTime(entityId, cmd.cooldown.bucket, cmd)
                    handleFailure(
                        CommandFailure.CooldownFailure(ctx, cmd, time)
                    ) { it.onCommandCooldown(ctx, cmd, time) }

                    return true
                }
            }
        }

        return false
    }

    private fun setCooldown(cmd: CommandFunction, ctx: Context) {
        if (cmd.cooldown != null && cmd.cooldown.duration > 0) {
            val entityId = when (cmd.cooldown.bucket) {
                BucketType.USER -> ctx.author.idLong
                BucketType.GUILD -> ctx.guild?.idLong
                BucketType.GLOBAL -> -1
            }

            if (entityId != null) {
                val time = cmd.cooldown.timeUnit.toMillis(cmd.cooldown.duration)
                cooldownProvider.setCooldown(entityId, cmd.cooldown.bucket, time, cmd)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CommandClient::class.java)

        fun builder() = CommandClientBuilder()

        fun create(config: CommandClientBuilder.() -> Unit): CommandClient {
            return CommandClientBuilder().apply(config).build()
        }
    }

    private fun createSyncPlanner(): CommandSyncPlanner {
        return CommandSyncPlanner(commands, commandLocalizationProvider)
    }
}
