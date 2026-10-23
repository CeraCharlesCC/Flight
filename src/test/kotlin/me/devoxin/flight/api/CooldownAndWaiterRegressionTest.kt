package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.annotations.Cooldown
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.cooldown.BucketType
import me.devoxin.flight.api.error.CommandFailure
import me.devoxin.flight.api.execution.BlockingExecutionPolicy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.Event
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class CooldownAndWaiterRegressionTest {
    @Test
    fun `concurrent prefix invocations acquire a global cooldown only once`() {
        assertConcurrentCooldown(slash = false)
    }

    @Test
    fun `prefix and slash invocations share atomic cooldown acquisition`() {
        assertConcurrentCooldown(slash = true)
    }

    private fun assertConcurrentCooldown(slash: Boolean) {
        val failures = CopyOnWriteArrayList<CommandFailure>()
        val client = newClient(failures)
        val cog = ConcurrentCooldownCog()
        val pool = Executors.newFixedThreadPool(2)
        try {
            client.commands.register(cog)
            val first = pool.submit { client.onEvent(messageEvent("!race", 1L)) }
            val second = pool.submit {
                client.onEvent(if (slash) slashEvent("race", 2L) else messageEvent("!race", 2L))
            }
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)

            assertEquals(1, cog.invocations.get())
            val cooldownFailure = assertIs<CommandFailure.CooldownFailure>(failures.single())
            assertEquals("race", cooldownFailure.command.name)
        } finally {
            pool.shutdownNow()
            client.shutdown()
        }
    }

    @Test
    fun `bad arguments do not consume the command cooldown`() {
        val failures = CopyOnWriteArrayList<CommandFailure>()
        val client = newClient(failures)
        val cog = CooldownArgumentCog()
        try {
            client.commands.register(cog)
            client.onEvent(messageEvent("!number invalid"))
            client.onEvent(messageEvent("!number 7"))
            client.onEvent(messageEvent("!number 8"))

            assertEquals(listOf(7), cog.values)
            assertIs<CommandFailure.BadArgumentFailure>(failures[0])
            assertIs<CommandFailure.CooldownFailure>(failures[1])
            assertEquals(2, failures.size)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `one event completes waiters registered for its concrete and base classes`() {
        val client = newClient()
        try {
            val exact = client.waitFor<WaiterChildEvent>({ true }, 0)
            val parent = client.waitFor<WaiterParentEvent>({ true }, 0)
            val root = client.waitFor<Event>({ it is WaiterChildEvent }, 0)
            val unrelated = client.waitFor<WaiterSiblingEvent>({ true }, 0)
            val rejected = client.waitFor<WaiterParentEvent>({ false }, 0)
            val event = WaiterChildEvent()

            client.onEvent(event)

            assertSame(event, exact.get(1, TimeUnit.SECONDS))
            assertSame(event, parent.get(1, TimeUnit.SECONDS))
            assertSame(event, root.get(1, TimeUnit.SECONDS))
            assertFalse(unrelated.isDone)
            assertFalse(rejected.isDone)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `a completed waiter can register another waiter for the same event type`() {
        val client = newClient()
        try {
            val first = client.waitFor<WaiterParentEvent>({ true }, 0)
            val second = first.thenApply { client.waitFor<WaiterParentEvent>({ true }, 0) }
            client.onEvent(WaiterChildEvent())
            assertFalse(second.join().isDone)

            val nextEvent = WaiterChildEvent()
            client.onEvent(nextEvent)
            assertSame(nextEvent, second.join().get(1, TimeUnit.SECONDS))
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `an unmatched superclass waiter times out`() {
        val client = newClient()
        try {
            val future = client.waitFor<WaiterParentEvent>({ false }, 10)
            client.onEvent(WaiterChildEvent())
            val failure = assertFailsWith<ExecutionException> { future.get(3, TimeUnit.SECONDS) }
            assertIs<TimeoutException>(failure.cause)
        } finally {
            client.shutdown()
        }
    }
}

class ConcurrentCooldownCog : Cog {
    private val barrier = CyclicBarrier(2)
    val invocations = AtomicInteger()

    override fun localCheck(ctx: Context, command: CommandFunction): Boolean {
        barrier.await(3, TimeUnit.SECONDS)
        return true
    }

    @Command(description = "Exercise shared cooldown acquisition")
    @Cooldown(duration = 60, timeUnit = TimeUnit.SECONDS, bucket = BucketType.GLOBAL)
    fun race(ctx: Context) {
        invocations.incrementAndGet()
    }
}

class CooldownArgumentCog : Cog {
    val values = mutableListOf<Int>()

    @Command
    @Cooldown(duration = 60, timeUnit = TimeUnit.SECONDS, bucket = BucketType.USER)
    fun number(ctx: MessageContext, value: Int) {
        values += value
    }
}

private open class WaiterParentEvent : Event(proxy<JDA>())
private class WaiterChildEvent : WaiterParentEvent()
private class WaiterSiblingEvent : Event(proxy<JDA>())

private fun newClient(failures: MutableList<CommandFailure> = mutableListOf()): CommandClient {
    return CommandClient.builder()
        .setPrefixes("!")
        .setAllowMentionPrefix(false)
        .configureDefaultHelpCommand { enabled = false }
        .configureExecution { blockingExecutionPolicy = BlockingExecutionPolicy.CALLER_THREAD }
        .registerDefaultParsers()
        .setErrorHandler { failures += it }
        .build()
}

private fun messageEvent(content: String, userId: Long = 1L): MessageReceivedEvent {
    val jda = proxy<JDA>()
    val user = userProxy(userId)
    val channel = channelProxy()
    val message = proxy<Message> { method ->
        when (method.name) {
            "getJDA" -> jda
            "getContentRaw" -> content
            "getAuthor" -> user
            "getChannel" -> channel
            "getChannelType" -> ChannelType.PRIVATE
            else -> null
        }
    }
    return MessageReceivedEvent(jda, 0, message)
}

private fun slashEvent(name: String, userId: Long): SlashCommandInteractionEvent {
    val jda = proxy<JDA>()
    val user = userProxy(userId)
    val channel = channelProxy()
    val interaction = proxy<SlashCommandInteraction> { method ->
        when (method.name) {
            "getJDA" -> jda
            "getName" -> name
            "getUser" -> user
            "getChannel" -> channel
            "getOptions" -> emptyList<Any>()
            else -> null
        }
    }
    return SlashCommandInteractionEvent(jda, 0, interaction)
}

private fun userProxy(id: Long): User = proxy { method ->
    when (method.name) {
        "getIdLong" -> id
        "getId" -> id.toString()
        else -> null
    }
}

private fun channelProxy(): MessageChannelUnion = proxy { method ->
    when (method.name) {
        "getType", "getChannelType" -> ChannelType.PRIVATE
        else -> null
    }
}

private inline fun <reified T> proxy(noinline handler: (Method) -> Any? = { null }): T {
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
        when (method.name) {
            "equals" -> instance === args?.get(0)
            "hashCode" -> System.identityHashCode(instance)
            "toString" -> "${T::class.java.simpleName}Proxy"
            else -> handler(method) ?: when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as T
}
