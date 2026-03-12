package me.devoxin.flight.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import me.devoxin.flight.api.annotations.Autocomplete
import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.annotations.GuildIds
import me.devoxin.flight.api.annotations.SubCommand
import me.devoxin.flight.api.annotations.SubCommandGroup
import me.devoxin.flight.api.annotations.Timeout
import me.devoxin.flight.api.autocomplete.AutocompleteHandler
import me.devoxin.flight.api.check.CheckType
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.context.SlashContext
import me.devoxin.flight.api.execution.BlockingExecutionPolicy
import me.devoxin.flight.api.error.CommandErrorHandler
import me.devoxin.flight.api.error.CommandFailure
import me.devoxin.flight.api.exceptions.AutocompleteCancelledException
import me.devoxin.flight.api.exceptions.AutocompleteInvocationException
import me.devoxin.flight.api.exceptions.AutocompleteTimeoutException
import me.devoxin.flight.api.exceptions.CommandInvocationException
import me.devoxin.flight.api.exceptions.CommandCancelledException
import me.devoxin.flight.api.exceptions.CommandTimeoutException
import me.devoxin.flight.api.hooks.DefaultCommandEventAdapter
import me.devoxin.flight.internal.execution.CommandExecutionCoordinator
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.AutoCompleteQuery
import net.dv8tion.jda.api.interactions.commands.CommandAutoCompleteInteraction
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import sun.misc.Unsafe
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommandExecutionIntegrationTest {
    @Test
    fun `typed autocomplete handlers support objects no arg classes and fail registration for mismatched cog types`() {
        val registry = me.devoxin.flight.api.command.CommandRegistry().apply {
            register(ReusableAutocompleteCog())
        }

        val objectHandlerCommand = registry.findSlashCommand("lookupobject")!!
        assertTrue(objectHandlerCommand.arguments.single().autocompleteSupported)

        val classHandlerCommand = registry.findSlashCommand("lookupclass")!!
        assertTrue(classHandlerCommand.arguments.single().autocompleteSupported)

        val mismatch = assertFailsWith<IllegalStateException> {
            registry.register(MismatchedAutocompleteCog())
        }
        assertTrue(mismatch.message!!.contains("Failed to register cog"))
        assertTrue(mismatch.cause!!.message!!.contains("expects cog type"))
    }

    @Test
    fun `command invocation failures preserve cause and run cog error before adapter then post invoke`() {
        val hookOrder = CopyOnWriteArrayList<String>()
        val adapter = ExecutionRecordingAdapter(hookOrder)
        val cog = InvocationFailureCog(hookOrder)
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!explode", userProxy(0L)))

        assertTrue(adapter.commandErrorLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.postInvokeLatch.await(3, TimeUnit.SECONDS))

        val error = adapter.lastCommandError as? CommandInvocationException
        assertNotNull(error)
        assertSame(cog.failure, error.cause)
        assertEquals(true, adapter.lastPostInvokeFailed)
        assertEquals(
            listOf(
                "cog:onCommandError:CommandInvocationException",
                "adapter:onCommandError:CommandInvocationException",
                "adapter:onCommandPostInvoke:true"
            ),
            hookOrder
        )

        client.shutdown()
    }

    @Test
    fun `locally handled execution failures skip centralized handling but still notify adapters`() {
        val hookOrder = CopyOnWriteArrayList<String>()
        val handler = RecordingCommandErrorHandler(hookOrder)
        val adapter = ExecutionRecordingAdapter(hookOrder)
        val cog = LocallyHandledFailureCog(hookOrder)
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .setErrorHandler(handler)
            .addEventListeners(adapter)
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!localfail", userProxy(100L)))

        assertTrue(adapter.commandErrorLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.postInvokeLatch.await(3, TimeUnit.SECONDS))
        assertTrue(handler.failures.none { it is CommandFailure.CommandExecutionFailure })
        assertEquals(
            listOf(
                "cog:onCommandError:CommandInvocationException",
                "adapter:onCommandError:CommandInvocationException",
                "adapter:onCommandPostInvoke:true"
            ),
            hookOrder
        )

        client.shutdown()
    }

    @Test
    fun `non execution failures reach the centralized handler before adapters`() {
        val hookOrder = CopyOnWriteArrayList<String>()
        val handler = RecordingCommandErrorHandler(hookOrder)
        val adapter = BadArgumentRecordingAdapter(hookOrder)
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .registerDefaultParsers()
            .setErrorHandler(handler)
            .addEventListeners(adapter)
            .build()

        client.commands.register(BadArgumentOrderCog())
        client.onEvent(messageReceivedEvent("!number nope", userProxy(101L)))

        assertEquals(
            listOf(
                "handler:BadArgumentFailure",
                "adapter:onBadArgument:number"
            ),
            hookOrder
        )
        assertTrue(handler.failures.single() is CommandFailure.BadArgumentFailure)

        client.shutdown()
    }

    @Test
    fun `default blocking execution policy dispatches sync handlers off the caller thread`() {
        val cog = BlockingDispatchCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .build()

        client.commands.register(cog)

        val callerThreadId = Thread.currentThread().threadId()
        client.onEvent(messageReceivedEvent("!blocking", userProxy(1L)))

        assertTrue(cog.invoked.await(3, TimeUnit.SECONDS))
        assertNotNull(cog.executingThreadId)
        assertNotEquals(callerThreadId, cog.executingThreadId)

        client.shutdown()
    }

    @Test
    fun `caller thread blocking policy keeps sync handlers inline and does not enforce blocking timeouts`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = InlineBlockingCog(sleepMillis = 120)
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .configureExecution {
                blockingExecutionPolicy = BlockingExecutionPolicy.CALLER_THREAD
                defaultTimeoutMillis = 25
            }
            .build()

        client.commands.register(cog)

        val callerThreadId = Thread.currentThread().threadId()
        client.onEvent(messageReceivedEvent("!inline", userProxy(2L)))

        assertEquals(callerThreadId, cog.executingThreadId)
        assertNull(adapter.lastCommandError)

        client.shutdown()
    }

    @Test
    fun `command timeout annotation overrides the builder default timeout`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = TimeoutOverrideCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .configureExecution {
                defaultTimeoutMillis = 500
            }
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!timed", userProxy(3L)))

        assertTrue(adapter.commandErrorLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.postInvokeLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.lastCommandError is CommandTimeoutException)
        assertEquals(true, adapter.lastPostInvokeFailed)

        client.shutdown()
    }

    @Test
    fun `shutdown cancels in flight suspend commands with a typed cancellation error`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = CancellableCommandCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!wait", userProxy(4L)))
        assertTrue(cog.started.await(3, TimeUnit.SECONDS))

        client.shutdown()

        assertTrue(adapter.commandErrorLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.postInvokeLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.lastCommandError is CommandCancelledException)
        assertEquals(true, adapter.lastPostInvokeFailed)
    }

    @Test
    fun `cancelling the provided parent scope cancels in flight commands`() {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = ExecutionRecordingAdapter()
        val cog = ParentScopeCancellableCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .configureExecution {
                this.parentScope = parentScope
            }
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!parentwait", userProxy(5L)))
        assertTrue(cog.started.await(3, TimeUnit.SECONDS))

        parentScope.cancel()

        assertTrue(adapter.commandErrorLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.postInvokeLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.lastCommandError is CommandCancelledException)
        assertEquals(true, adapter.lastPostInvokeFailed)

        client.shutdown()
    }

    @Test
    fun `shutdown does not cancel a provided parent scope`() {
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(parentJob + Dispatchers.Default)
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .configureExecution {
                this.parentScope = parentScope
            }
            .build()

        client.shutdown()

        assertTrue(parentJob.isActive)
        parentScope.cancel()
    }

    @Test
    fun `autocomplete uses the default timeout and surfaces a typed timeout error`() {
        val registry = me.devoxin.flight.api.command.CommandRegistry().apply {
            register(AutocompleteTimeoutCog())
        }
        val command = registry.findSlashCommand("lookup")!!
        val argument = command.arguments.single { it.name == "query" }
        val coordinator = CommandExecutionCoordinator(
            me.devoxin.flight.api.execution.CommandExecutionOptions(defaultTimeoutMillis = 50)
        )

        var error: Throwable? = null
        val latch = CountDownLatch(1)

        coordinator.executeAutocomplete(argument, autocompleteEvent()) {
            error = it
            latch.countDown()
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertTrue(error is AutocompleteTimeoutException)

        coordinator.shutdown()
    }

    @Test
    fun `autocomplete invocation failures preserve cause through client level error handling`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = AutocompleteInvocationFailureCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .build()

        client.commands.register(cog)
        client.onEvent(autocompleteEvent(name = "lookupfail", focusedOption = "query"))

        assertTrue(adapter.autocompleteErrorLatch.await(3, TimeUnit.SECONDS))

        val error = adapter.lastAutocompleteError as? AutocompleteInvocationException
        assertNotNull(error)
        assertSame(cog.failure, error.cause)

        client.shutdown()
    }

    @Test
    fun `client shutdown cancels in flight autocomplete through onAutocompleteError`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = CancellableAutocompleteCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .build()

        client.commands.register(cog)
        client.onEvent(autocompleteEvent(name = "lookupcancel", focusedOption = "query"))
        assertTrue(cog.started.await(3, TimeUnit.SECONDS))

        client.shutdown()

        assertTrue(adapter.autocompleteErrorLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.lastAutocompleteError is AutocompleteCancelledException)
    }

    @Test
    fun `parent scope cancellation cancels in flight autocomplete through onAutocompleteError`() {
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val adapter = ExecutionRecordingAdapter()
        val cog = ParentScopeCancellableAutocompleteCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .configureExecution {
                this.parentScope = parentScope
            }
            .build()

        client.commands.register(cog)
        client.onEvent(autocompleteEvent(name = "lookupparentcancel", focusedOption = "query"))
        assertTrue(cog.started.await(3, TimeUnit.SECONDS))

        parentScope.cancel()

        assertTrue(adapter.autocompleteErrorLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.lastAutocompleteError is AutocompleteCancelledException)

        client.shutdown()
    }

    @Test
    fun `direct subcommands use builder default timeout when executable timeout is absent`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = TimeoutResolutionCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .configureExecution {
                defaultTimeoutMillis = 150
            }
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!flight direct", userProxy(6L)))

        assertTrue(cog.directCompleted.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.postInvokeLatch.await(3, TimeUnit.SECONDS))
        assertNull(adapter.lastCommandError)
        assertEquals(false, adapter.lastPostInvokeFailed)

        client.shutdown()
    }

    @Test
    fun `grouped subcommands use builder default timeout when executable timeout is absent`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = TimeoutResolutionCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .configureExecution {
                defaultTimeoutMillis = 150
            }
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!flight crew grouped", userProxy(7L)))

        assertTrue(cog.groupedCompleted.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.postInvokeLatch.await(3, TimeUnit.SECONDS))
        assertNull(adapter.lastCommandError)
        assertEquals(false, adapter.lastPostInvokeFailed)

        client.shutdown()
    }

    @Test
    fun `subcommand timeout overrides the builder default timeout`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = TimeoutResolutionCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .configureExecution {
                defaultTimeoutMillis = 150
            }
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!flight override", userProxy(8L)))

        assertTrue(adapter.commandErrorLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.postInvokeLatch.await(3, TimeUnit.SECONDS))
        assertTrue(adapter.lastCommandError is CommandTimeoutException)
        assertEquals(true, adapter.lastPostInvokeFailed)

        client.shutdown()
    }

    @Test
    fun `guild restricted message command executes in allowed guild`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = GuildRestrictedMessageCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .build()

        client.commands.register(cog)
        client.onEvent(guildMessageReceivedEvent("!restricted", userProxy(9L), 123L))

        assertTrue(cog.invoked.await(3, TimeUnit.SECONDS))
        assertEquals(1, cog.invocationCount)
        assertNull(adapter.lastFailedCheck)

        client.shutdown()
    }

    @Test
    fun `guild restricted message command does not execute in wrong guild`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = GuildRestrictedMessageCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .build()

        client.commands.register(cog)
        client.onEvent(guildMessageReceivedEvent("!restricted", userProxy(10L), 999L))

        assertTrue(adapter.checkFailedLatch.await(3, TimeUnit.SECONDS))
        assertEquals(CheckType.GUILD_ID_CHECK, adapter.lastFailedCheck)
        assertEquals(0, cog.invocationCount)
        assertFalse(cog.invoked.await(250, TimeUnit.MILLISECONDS))

        client.shutdown()
    }

    @Test
    fun `guild restricted message command does not execute in dms`() {
        val adapter = ExecutionRecordingAdapter()
        val cog = GuildRestrictedMessageCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(adapter)
            .build()

        client.commands.register(cog)
        client.onEvent(messageReceivedEvent("!restricted", userProxy(11L)))

        assertTrue(adapter.checkFailedLatch.await(3, TimeUnit.SECONDS))
        assertEquals(CheckType.GUILD_ID_CHECK, adapter.lastFailedCheck)
        assertEquals(0, cog.invocationCount)
        assertFalse(cog.invoked.await(250, TimeUnit.MILLISECONDS))

        client.shutdown()
    }
}

class InvocationFailureCog(
    private val hookOrder: MutableList<String>
) : Cog {
    val failure: IllegalStateException = IllegalStateException("command boom")

    @Command
    fun explode(ctx: MessageContext) {
        throw failure
    }

    override fun onCommandError(ctx: Context, command: CommandFunction, error: Throwable): Boolean {
        hookOrder += "cog:onCommandError:${error::class.simpleName}"
        return false
    }
}

class LocallyHandledFailureCog(
    private val hookOrder: MutableList<String>
) : Cog {
    private val failure = IllegalStateException("locally handled boom")

    @Command
    fun localfail(ctx: MessageContext) {
        throw failure
    }

    override fun onCommandError(ctx: Context, command: CommandFunction, error: Throwable): Boolean {
        hookOrder += "cog:onCommandError:${error::class.simpleName}"
        return true
    }
}

class BadArgumentOrderCog : Cog {
    @Command
    fun number(ctx: MessageContext, count: Int) = Unit
}

class BlockingDispatchCog : Cog {
    val invoked: CountDownLatch = CountDownLatch(1)
    @Volatile
    var executingThreadId: Long? = null

    @Command
    fun blocking(ctx: MessageContext) {
        executingThreadId = Thread.currentThread().threadId()
        invoked.countDown()
    }
}

class InlineBlockingCog(
    private val sleepMillis: Long
) : Cog {
    @Volatile
    var executingThreadId: Long? = null

    @Command
    fun inline(ctx: MessageContext) {
        executingThreadId = Thread.currentThread().threadId()
        Thread.sleep(sleepMillis)
    }
}

class TimeoutOverrideCog : Cog {
    @Timeout(duration = 50)
    @Command
    suspend fun timed(ctx: MessageContext) {
        delay(250)
    }
}

class CancellableCommandCog : Cog {
    val started: CountDownLatch = CountDownLatch(1)

    @Command
    suspend fun wait(ctx: MessageContext) {
        started.countDown()
        delay(5_000)
    }
}

class ParentScopeCancellableCog : Cog {
    val started: CountDownLatch = CountDownLatch(1)

    @Command
    suspend fun parentwait(ctx: MessageContext) {
        started.countDown()
        delay(5_000)
    }
}

class AutocompleteTimeoutCog : Cog {
    @Command(description = "Lookup")
    fun lookup(ctx: SlashContext, @Autocomplete(TimeoutAutocompleteHandler::class) query: String) = Unit
}

class AutocompleteInvocationFailureCog : Cog {
    val failure: IllegalStateException = IllegalStateException("autocomplete boom")

    @Command(description = "Lookup fail")
    fun lookupFail(ctx: SlashContext, @Autocomplete(InvocationFailureAutocompleteHandler::class) query: String) = Unit
}

class CancellableAutocompleteCog : Cog {
    val started: CountDownLatch = CountDownLatch(1)

    @Command(description = "Lookup cancel")
    fun lookupCancel(ctx: SlashContext, @Autocomplete(CancellableAutocompleteHandler::class) query: String) = Unit
}

class ParentScopeCancellableAutocompleteCog : Cog {
    val started: CountDownLatch = CountDownLatch(1)

    @Command(description = "Lookup parent cancel")
    fun lookupParentCancel(ctx: SlashContext, @Autocomplete(ParentScopeCancellableAutocompleteHandler::class) query: String) = Unit
}

class ReusableAutocompleteCog : Cog {
    @Command(description = "Lookup object")
    fun lookupObject(ctx: SlashContext, @Autocomplete(ReusableObjectAutocompleteHandler::class) query: String) = Unit

    @Command(description = "Lookup class")
    fun lookupClass(ctx: SlashContext, @Autocomplete(ReusableClassAutocompleteHandler::class) query: String) = Unit
}

class MismatchedAutocompleteCog : Cog {
    @Command(description = "Mismatch")
    fun mismatch(ctx: SlashContext, @Autocomplete(ReusableObjectAutocompleteHandler::class) query: String) = Unit
}

object TimeoutAutocompleteHandler : AutocompleteHandler<AutocompleteTimeoutCog> {
    override suspend fun complete(cog: AutocompleteTimeoutCog, event: CommandAutoCompleteInteractionEvent) {
        delay(250)
    }
}

object InvocationFailureAutocompleteHandler : AutocompleteHandler<AutocompleteInvocationFailureCog> {
    override suspend fun complete(cog: AutocompleteInvocationFailureCog, event: CommandAutoCompleteInteractionEvent) {
        throw cog.failure
    }
}

class CancellableAutocompleteHandler : AutocompleteHandler<CancellableAutocompleteCog> {
    override suspend fun complete(cog: CancellableAutocompleteCog, event: CommandAutoCompleteInteractionEvent) {
        cog.started.countDown()
        delay(5_000)
    }
}

class ParentScopeCancellableAutocompleteHandler : AutocompleteHandler<ParentScopeCancellableAutocompleteCog> {
    override suspend fun complete(
        cog: ParentScopeCancellableAutocompleteCog,
        event: CommandAutoCompleteInteractionEvent
    ) {
        cog.started.countDown()
        delay(5_000)
    }
}

object ReusableObjectAutocompleteHandler : AutocompleteHandler<ReusableAutocompleteCog> {
    override suspend fun complete(cog: ReusableAutocompleteCog, event: CommandAutoCompleteInteractionEvent) = Unit
}

class ReusableClassAutocompleteHandler : AutocompleteHandler<ReusableAutocompleteCog> {
    override suspend fun complete(cog: ReusableAutocompleteCog, event: CommandAutoCompleteInteractionEvent) = Unit
}

class TimeoutResolutionCog : Cog {
    val directCompleted: CountDownLatch = CountDownLatch(1)
    val groupedCompleted: CountDownLatch = CountDownLatch(1)

    @Timeout(duration = 50)
    @Command(description = "Flight operations")
    @SubCommandGroup(name = "crew", description = "Crew operations")
    suspend fun flight(ctx: Context) {
        delay(100)
    }

    @SubCommand(parent = "flight", description = "Direct command")
    suspend fun direct(ctx: Context) {
        delay(80)
        directCompleted.countDown()
    }

    @Timeout(duration = 30)
    @SubCommand(parent = "flight", name = "override", description = "Override command")
    suspend fun overrideTimeout(ctx: Context) {
        delay(80)
    }

    @SubCommand(parent = "flight", group = "crew", name = "grouped", description = "Grouped command")
    suspend fun grouped(ctx: Context) {
        delay(80)
        groupedCompleted.countDown()
    }
}

class GuildRestrictedMessageCog : Cog {
    val invoked: CountDownLatch = CountDownLatch(1)
    @Volatile
    var invocationCount: Int = 0

    @GuildIds([123L])
    @Command
    fun restricted(ctx: MessageContext) {
        invocationCount += 1
        invoked.countDown()
    }
}

private class ExecutionRecordingAdapter(
    private val hookOrder: MutableList<String>? = null
) : DefaultCommandEventAdapter() {
    val commandErrorLatch: CountDownLatch = CountDownLatch(1)
    val autocompleteErrorLatch: CountDownLatch = CountDownLatch(1)
    val checkFailedLatch: CountDownLatch = CountDownLatch(1)
    val postInvokeLatch: CountDownLatch = CountDownLatch(1)
    @Volatile
    var lastAutocompleteError: Throwable? = null
    @Volatile
    var lastFailedCheck: CheckType? = null
    @Volatile
    var lastPostInvokeFailed: Boolean? = null
    @Volatile
    var lastCommandError: Throwable? = null

    override fun onCheckFailed(ctx: Context, command: CommandFunction, checkType: CheckType) {
        lastFailedCheck = checkType
        checkFailedLatch.countDown()
    }

    override fun onCommandError(ctx: Context, command: CommandFunction, error: Throwable) {
        lastCommandError = error
        hookOrder?.add("adapter:onCommandError:${error::class.simpleName}")
        commandErrorLatch.countDown()
    }

    override fun onCommandPostInvoke(ctx: Context, command: CommandFunction, failed: Boolean) {
        lastPostInvokeFailed = failed
        hookOrder?.add("adapter:onCommandPostInvoke:$failed")
        postInvokeLatch.countDown()
    }

    override fun onAutocompleteError(event: CommandAutoCompleteInteractionEvent, error: Throwable) {
        lastAutocompleteError = error
        autocompleteErrorLatch.countDown()
    }
}

private class BadArgumentRecordingAdapter(
    private val hookOrder: MutableList<String>
) : DefaultCommandEventAdapter() {
    override fun onBadArgument(ctx: Context, command: CommandFunction, error: me.devoxin.flight.api.exceptions.BadArgument) {
        hookOrder += "adapter:onBadArgument:${command.name}"
    }
}

private class RecordingCommandErrorHandler(
    private val hookOrder: MutableList<String>? = null
) : CommandErrorHandler {
    val failures: CopyOnWriteArrayList<CommandFailure> = CopyOnWriteArrayList()

    override fun handle(failure: CommandFailure) {
        failures += failure
        hookOrder?.add("handler:${failure::class.simpleName}")
    }
}

private fun messageReceivedEvent(content: String, author: User): MessageReceivedEvent {
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

private fun guildMessageReceivedEvent(content: String, author: User, guildId: Long): MessageReceivedEvent {
    val jda = proxy<JDA>()
    val guild = guildProxy(guildId)
    val member = memberProxy(author, guild)
    val channel = guildInteractionChannel(jda, guild)
    val message = proxy<Message> { method ->
        when (method.name) {
            "getContentRaw" -> content
            "getAuthor" -> author
            "getChannel" -> channel
            "getGuild" -> guild
            "getMember" -> member
            "isWebhookMessage" -> false
            "isFromGuild" -> true
            "getChannelType" -> ChannelType.TEXT
            "getJDA" -> jda
            else -> defaultValue(method.returnType)
        }
    }

    return MessageReceivedEvent(jda, 0L, message)
}

private fun autocompleteEvent(
    name: String = "lookup",
    focusedOption: String = "query",
    group: String? = null,
    subcommand: String? = null,
    invokingUser: User = userProxy(42L)
): CommandAutoCompleteInteractionEvent {
    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val query = createAutoCompleteQuery(focusedOption)
    val interaction = proxyWithArgs<CommandAutoCompleteInteraction> { method, _ ->
        when (method.name) {
            "getName" -> name
            "getFocusedOption" -> query
            "getSubcommandGroup" -> group
            "getSubcommandName" -> subcommand
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
    setField(unsafe, query, "type", net.dv8tion.jda.api.interactions.commands.OptionType.STRING)

    return query
}

private fun setField(unsafe: Unsafe, target: Any, fieldName: String, value: Any?) {
    val field = target::class.java.getDeclaredField(fieldName)
    unsafe.putObject(target, unsafe.objectFieldOffset(field), value)
}

private fun interactionChannel(): MessageChannelUnion {
    return proxyWithArgs<MessageChannelUnion>(MessageChannel::class.java) { method, _ ->
        when (method.name) {
            "getType", "getChannelType" -> ChannelType.PRIVATE
            else -> defaultValue(method.returnType)
        }
    }
}

private fun guildInteractionChannel(jda: JDA, guild: Guild): MessageChannelUnion {
    return proxyWithArgs<MessageChannelUnion>(
        MessageChannel::class.java,
        GuildMessageChannel::class.java,
        GuildMessageChannelUnion::class.java
    ) { method, _ ->
        when (method.name) {
            "getType", "getChannelType" -> ChannelType.TEXT
            "getGuild" -> guild
            "getJDA" -> jda
            else -> defaultValue(method.returnType)
        }
    }
}

private fun guildProxy(id: Long): Guild {
    return proxy { method ->
        when (method.name) {
            "getIdLong" -> id
            "getId" -> id.toString()
            else -> defaultValue(method.returnType)
        }
    }
}

private fun memberProxy(user: User, guild: Guild): Member {
    return proxy { method ->
        when (method.name) {
            "getIdLong" -> user.idLong
            "getId" -> user.id
            "getUser" -> user
            "getGuild" -> guild
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
