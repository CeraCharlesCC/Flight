package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Choices
import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.annotations.Range
import me.devoxin.flight.api.annotations.choice.DoubleChoice
import me.devoxin.flight.api.annotations.choice.LongChoice
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.exceptions.BadArgument
import me.devoxin.flight.internal.arguments.ArgParser
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
import kotlin.test.assertIs

class ArgumentChoiceRegressionTest {
    @AfterTest
    fun clearParsers() = ArgParser.parsers.clear()

    @Test
    fun `numeric choices bind to the declared types and invoke handlers`() = withCommands { client, cog ->
        for ((name, choice, expected) in listOf(
            Triple("int_choice", "one", 1),
            Triple("int_choice", "maximum", Int.MAX_VALUE),
            Triple("int_choice", "minimum", Int.MIN_VALUE),
            Triple("long_choice", "large", Long.MAX_VALUE),
            Triple("float_choice", "fraction", 1.5f),
            Triple("double_choice", "fraction", 1.5)
        )) {
            val command = client.commands.findMessageCommand(name)!!
            val ctx = choiceContext(client, command)
            val parsed = ArgParser.parseArguments(command, ctx, listOf(choice), ' ')

            assertEquals(expected::class, parsed.values.single()!!::class)
            command.invokeBlocking(command.bindArguments(ctx, parsed))
            assertEquals(expected, cog.received)
        }
    }

    @Test
    fun `unrepresentable choices fail as bad arguments before invocation`() = withCommands { client, _ ->
        for ((name, choice) in listOf(
            "int_choice" to "overflow",
            "int_choice" to "underflow",
            "float_choice" to "overflow",
            "float_choice" to "rounded_overflow",
            "float_choice" to "underflow",
            "double_choice" to "non_finite"
        )) {
            val error = assertFailsWith<BadArgument> { parseChoice(client, name, choice) }
            assertContains(error.cause!!.message!!, "cannot be represented")
        }
    }

    @Test
    fun `choices still enforce declared ranges and reject unknown names`() = withCommands { client, _ ->
        assertFailsWith<BadArgument> { parseChoice(client, "bounded_int", "large") }
        assertFailsWith<BadArgument> { parseChoice(client, "bounded_float", "fraction") }
        assertFailsWith<BadArgument> { parseChoice(client, "long_bounded_float", "fraction") }
        assertFailsWith<BadArgument> { parseChoice(client, "long_limit_float", "too_large_for_long") }
        val error = assertFailsWith<BadArgument> { parseChoice(client, "int_choice", "unknown") }
        assertContains(error.cause!!.message!!, "Valid choices")
    }

    @Test
    fun `normal parser failures retain their causes`() = withCommands { client, _ ->
        val failure = IllegalArgumentException("custom parser failure")
        ArgParser.parsers[Int::class.javaObjectType] = object : me.devoxin.flight.internal.parsers.Parser<Int> {
            override fun parse(ctx: MessageContext, param: String): Int = throw failure
        }
        val error = assertFailsWith<BadArgument> { parseChoice(client, "plain_int", "one") }
        assertIs<IllegalArgumentException>(error.cause)
        assertEquals(failure, error.cause)
    }

    private fun parseChoice(client: CommandClient, name: String, choice: String): Any? {
        val command = client.commands.findMessageCommand(name)!!
        return ArgParser.parseArguments(command, choiceContext(client, command), listOf(choice), ' ').values.single()
    }

    private fun withCommands(block: (CommandClient, NumericChoiceRegressionCog) -> Unit) {
        val cog = NumericChoiceRegressionCog()
        val client = CommandClient.builder()
            .configureDefaultHelpCommand { enabled = false }
            .registerDefaultParsers()
            .register(cog)
            .build()
        try {
            block(client, cog)
        } finally {
            client.shutdown()
        }
    }
}

class NumericChoiceRegressionCog : Cog {
    var received: Any? = null

    @Command
    fun int_choice(ctx: MessageContext, @Choices(long = [
        LongChoice("one", 1), LongChoice("maximum", 2147483647), LongChoice("minimum", -2147483648),
        LongChoice("overflow", 2147483648), LongChoice("underflow", -2147483649)
    ]) value: Int) { received = value }

    @Command
    fun long_choice(ctx: MessageContext, @Choices(long = [LongChoice("large", Long.MAX_VALUE)]) value: Long) {
        received = value
    }

    @Command
    fun float_choice(ctx: MessageContext, @Choices(double = [
        DoubleChoice("fraction", 1.5), DoubleChoice("overflow", Double.MAX_VALUE),
        DoubleChoice("rounded_overflow", 3.4028235e38), DoubleChoice("underflow", Double.MIN_VALUE)
    ]) value: Float) { received = value }

    @Command
    fun double_choice(ctx: MessageContext, @Choices(double = [
        DoubleChoice("fraction", 1.5), DoubleChoice("non_finite", Double.POSITIVE_INFINITY)
    ]) value: Double) { received = value }

    @Command
    fun bounded_int(ctx: MessageContext,
        @Choices(long = [LongChoice("large", 3)]) @Range(long = [0, 2]) value: Int) = Unit

    @Command
    fun bounded_float(ctx: MessageContext,
        @Choices(double = [DoubleChoice("fraction", 1.5)]) @Range(double = [0.0, 1.0]) value: Float) = Unit

    @Command
    fun long_bounded_float(ctx: MessageContext,
        @Choices(double = [DoubleChoice("fraction", 1.5)])
        @Range(long = [0, 1]) value: Float) = Unit

    @Command
    fun long_limit_float(ctx: MessageContext,
        @Choices(double = [DoubleChoice("too_large_for_long", 9223372036854775808.0)])
        @Range(long = [0, Long.MAX_VALUE]) value: Float) = Unit

    @Command
    fun plain_int(ctx: MessageContext, value: Int) = Unit
}

private fun choiceContext(client: CommandClient, command: CommandFunction): MessageContext {
    val jda = choiceProxy<JDA>()
    val author = choiceProxy<User>()
    val channel = choiceProxy<MessageChannelUnion> { if (it == "getType") ChannelType.PRIVATE else null }
    val message = choiceProxy<Message> { method ->
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

private inline fun <reified T> choiceProxy(crossinline result: (String) -> Any? = { null }): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        result(method.name) ?: when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Long.TYPE -> 0L
            java.lang.Integer.TYPE -> 0
            else -> null
        }
    } as T
