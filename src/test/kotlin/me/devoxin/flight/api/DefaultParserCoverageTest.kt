package me.devoxin.flight.api

import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.internal.arguments.ArgParser
import me.devoxin.flight.internal.parsers.EmojiParser
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.entities.emoji.CustomEmoji
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.EmojiUnion
import net.dv8tion.jda.api.entities.emoji.UnicodeEmoji
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultParserCoverageTest {
    @AfterTest
    fun clearParsers() {
        ArgParser.parsers.clear()
    }

    @Test
    fun `default parser registration uses JDA emoji types`() {
        CommandClient.builder()
            .registerDefaultParsers()

        assertEmojiParserRegistered<Emoji>(Emoji::class.java)
        assertEmojiParserRegistered<EmojiUnion>(EmojiUnion::class.java)
        assertEmojiParserRegistered<UnicodeEmoji>(UnicodeEmoji::class.java)
        assertEmojiParserRegistered<CustomEmoji>(CustomEmoji::class.java)
    }

    @Test
    fun `emoji parsers resolve unicode and custom emoji with subtype filtering`() {
        val ctx = messageContext()
        val emojiParser = EmojiParser.forEmoji()
        val unionParser = EmojiParser.forEmojiUnion()
        val unicodeParser = EmojiParser.forUnicodeEmoji()
        val customParser = EmojiParser.forCustomEmoji()

        val unicode = emojiParser.parse(ctx, "🔥")
        assertNotNull(unicode)
        assertEquals(Emoji.Type.UNICODE, unicode.type)
        assertEquals("🔥", unicode.formatted)
        assertEquals("🔥", unionParser.parse(ctx, "🔥")?.formatted)
        assertEquals("🔥", unicodeParser.parse(ctx, "🔥")?.formatted)
        assertNull(customParser.parse(ctx, "🔥"))

        val custom = emojiParser.parse(ctx, "<a:dance:123456789123456789>")
        assertNotNull(custom)
        assertEquals(Emoji.Type.CUSTOM, custom.type)
        assertEquals("dance", custom.name)
        assertEquals("<a:dance:123456789123456789>", custom.formatted)
        val customOnly = customParser.parse(ctx, "<a:dance:123456789123456789>")
        assertNotNull(customOnly)
        assertEquals("dance", customOnly.name)
        assertTrue(customOnly.isAnimated)
        assertNull(unicodeParser.parse(ctx, "<a:dance:123456789123456789>"))
    }

    @Test
    fun `emoji parser rejects plain words while still accepting JDA codepoint notation`() {
        val ctx = messageContext()
        val parser = EmojiParser.forEmojiUnion()

        assertFalse(EmojiParser.looksLikeEmojiToken("plane"))
        assertNull(parser.parse(ctx, "plane"))

        assertTrue(EmojiParser.looksLikeEmojiToken("U+1F680"))
        assertEquals("🚀", parser.parse(ctx, "U+1F680")?.formatted)
    }

    private fun <T : Any> assertEmojiParserRegistered(type: Class<T>) {
        val parser = ArgParser.parsers[type]
        assertNotNull(parser, "Expected a parser to be registered for ${type.name}")
        assertTrue(parser is EmojiParser<*>, "Expected ${type.name} to be backed by EmojiParser, got ${parser::class.java.name}")
    }
}

private fun messageContext(): MessageContext {
    val commandClient = CommandClient.builder()
        .setPrefixes("!")
        .configureDefaultHelpCommand { enabled = false }
        .build()
    val registry = commandClient.commands
    registry.register(EmojiParserTestCog())
    val invokedCommand = registry.findMessageCommand("emoji")!!

    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val message = proxy<Message> { method ->
        when (method.name) {
            "getContentRaw" -> "!emoji"
            "getJDA" -> jda
            "getAuthor" -> userProxy(1L)
            "getChannel" -> channel
            "getMember" -> null
            "isFromGuild" -> false
            "getChannelType" -> ChannelType.PRIVATE
            else -> defaultValue(method.returnType)
        }
    }
    val event = MessageReceivedEvent(jda, 0L, message)

    return MessageContext(commandClient, event, "!", invokedCommand)
}

private class EmojiParserTestCog : me.devoxin.flight.api.command.Cog {
    @me.devoxin.flight.api.annotations.Command
    fun emoji(ctx: MessageContext) = Unit
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
            "getAsMention" -> "<@$id>"
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
        handler(method, args) ?: defaultValue(method.returnType)
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