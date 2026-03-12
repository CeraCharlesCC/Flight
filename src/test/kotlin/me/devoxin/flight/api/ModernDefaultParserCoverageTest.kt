package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.internal.arguments.ArgParser
import me.devoxin.flight.internal.parsers.Parser
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.IMentionable
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer
import net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer
import net.dv8tion.jda.api.entities.channel.concrete.Category
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.MediaChannel
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.DefaultGuildChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.IPermissionContainerUnion
import net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ModernDefaultParserCoverageTest {
    @AfterTest
    fun clearParsers() {
        ArgParser.parsers.clear()
    }

    @Test
    fun `default mentionable parser prefers member then user then role and still supports role mention lookup`() {
        val member = mentionableProxy<Member>(111111111111111111L, "shared")
        val user = mentionableProxy<User>(222222222222222222L, "shared")
        val role = mentionableProxy<Role>(333333333333333333L, "shared")
        val ctx = parserContext(
            users = listOf(user),
            members = listOf(member),
            roles = listOf(role),
            channels = emptyList(),
            threadChannels = emptyList()
        )

        CommandClient.builder().registerDefaultParsers()

        val parser = parserFor<IMentionable>(IMentionable::class.java)
        assertSame(member, parser.parse(ctx, "shared"))
        assertSame(role, parser.parse(ctx, "<@&333333333333333333>"))
    }

    @Test
    fun `default parsers resolve modern guild channel concretes and broad unions interfaces`() {
        val news = guildChannelProxy<NewsChannel>(
            10L,
            "announcements",
            ChannelType.NEWS
        )
        val stage = guildChannelProxy<StageChannel>(
            11L,
            "townhall",
            ChannelType.STAGE
        )
        val thread = guildChannelProxy<ThreadChannel>(
            12L,
            "incident-thread",
            ChannelType.GUILD_PUBLIC_THREAD
        )
        val forum = guildChannelProxy<ForumChannel>(
            13L,
            "crew-forum",
            ChannelType.FORUM
        )
        val media = guildChannelProxy<MediaChannel>(
            14L,
            "media-hub",
            ChannelType.MEDIA
        )
        val category = guildChannelProxy<Category>(
            15L,
            "ops",
            ChannelType.CATEGORY
        )
        val ctx = parserContext(
            users = emptyList(),
            members = emptyList(),
            roles = emptyList(),
            channels = listOf(news, stage, forum, media, category),
            threadChannels = listOf(thread)
        )

        CommandClient.builder().registerDefaultParsers()

        assertSame(news as Any, parserFor<NewsChannel>(NewsChannel::class.java).parse(ctx, "announcements") as Any?)
        assertSame(stage as Any, parserFor<StageChannel>(StageChannel::class.java).parse(ctx, "townhall") as Any?)
        assertSame(thread as Any, parserFor<ThreadChannel>(ThreadChannel::class.java).parse(ctx, "incident-thread") as Any?)
        assertSame(forum as Any, parserFor<ForumChannel>(ForumChannel::class.java).parse(ctx, "crew-forum") as Any?)
        assertSame(media as Any, parserFor<MediaChannel>(MediaChannel::class.java).parse(ctx, "media-hub") as Any?)
        assertSame(category as Any, parserFor<Category>(Category::class.java).parse(ctx, "ops") as Any?)
        val guildMessageUnion = parserFor<GuildMessageChannelUnion>(GuildMessageChannelUnion::class.java)
            .parse(ctx, "incident-thread")
        assertNotNull(guildMessageUnion)
        assertNotSame(thread as Any, guildMessageUnion as Any)
        assertEquals(thread.idLong, guildMessageUnion.idLong)
        assertSame(thread as Any, guildMessageUnion.asThreadChannel() as Any)

        val audioUnion = parserFor<AudioChannelUnion>(AudioChannelUnion::class.java).parse(ctx, "townhall")
        assertNotNull(audioUnion)
        assertNotSame(stage as Any, audioUnion as Any)
        assertEquals(stage.idLong, audioUnion.idLong)
        assertSame(stage as Any, audioUnion.asStageChannel() as Any)
        assertSame(stage as Any, audioUnion.asGuildMessageChannel() as Any)

        val threadContainerUnion = parserFor<IThreadContainerUnion>(IThreadContainerUnion::class.java)
            .parse(ctx, "crew-forum")
        assertNotNull(threadContainerUnion)
        assertNotSame(forum as Any, threadContainerUnion as Any)
        assertEquals(forum.idLong, threadContainerUnion.idLong)
        assertSame(forum as Any, threadContainerUnion.asForumChannel() as Any)

        val permissionContainerUnion = parserFor<IPermissionContainerUnion>(IPermissionContainerUnion::class.java)
            .parse(ctx, "ops")
        assertNotNull(permissionContainerUnion)
        assertNotSame(category as Any, permissionContainerUnion as Any)
        assertEquals(category.idLong, permissionContainerUnion.idLong)
        assertSame(category as Any, permissionContainerUnion.asCategory() as Any)

        val guildChannelUnion = parserFor<GuildChannelUnion>(GuildChannelUnion::class.java).parse(ctx, "media-hub")
        assertNotNull(guildChannelUnion)
        assertNotSame(media as Any, guildChannelUnion as Any)
        assertEquals(media.idLong, guildChannelUnion.idLong)
        assertSame(media as Any, guildChannelUnion.asMediaChannel() as Any)

        assertSame(news as Any, parserFor<StandardGuildMessageChannel>(StandardGuildMessageChannel::class.java).parse(ctx, "announcements") as Any?)
        assertSame(forum as Any, parserFor<StandardGuildChannel>(StandardGuildChannel::class.java).parse(ctx, "crew-forum") as Any?)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> parserFor(type: Class<T>): Parser<T> {
    return ArgParser.parsers[type] as Parser<T>
}

private fun parserContext(
    users: List<User>,
    members: List<Member>,
    roles: List<Role>,
    channels: List<GuildChannel>,
    threadChannels: List<ThreadChannel>
): MessageContext {
    val client = CommandClient.builder()
        .setPrefixes("!")
        .configureDefaultHelpCommand { enabled = false }
        .build()
    client.commands.register(ParserCoverageCog())
    val invoked = client.commands.findMessageCommand("parse")!!

    val userById = users.associateBy(User::getIdLong)
    val memberById = members.associateBy(Member::getIdLong)
    val roleById = roles.associateBy(Role::getIdLong)
    val guild = proxyWithArgs<Guild> { method, args ->
        when (method.name) {
            "getMemberCache" -> cacheView(method.returnType, members)
            "getRoleCache" -> cacheView(method.returnType, roles)
            "getMemberById" -> memberById[coerceId(args?.firstOrNull())]
            "getRoleById" -> roleById[coerceId(args?.firstOrNull())]
            "getChannels" -> channels
            "getThreadChannelCache" -> cacheView(method.returnType, threadChannels)
            else -> defaultValue(method.returnType)
        }
    }
    val jda = proxyWithArgs<JDA> { method, args ->
        when (method.name) {
            "getUserCache" -> cacheView(method.returnType, users)
            "getUserById" -> userById[coerceId(args?.firstOrNull())]
            else -> defaultValue(method.returnType)
        }
    }
    val messageChannel = proxyWithArgs<GuildMessageChannel>(
        MessageChannel::class.java,
        MessageChannelUnion::class.java,
        GuildChannel::class.java,
        GuildMessageChannelUnion::class.java
    ) { method, _ ->
        when (method.name) {
            "getType", "getChannelType" -> ChannelType.TEXT
            "getGuild" -> guild
            "getJDA" -> jda
            else -> defaultValue(method.returnType)
        }
    }
    val message = proxyWithArgs<Message> { method, _ ->
        when (method.name) {
            "getContentRaw" -> "!parse"
            "getJDA" -> jda
            "getAuthor" -> users.firstOrNull() ?: mentionableProxy<User>(999L, "author")
            "getChannel" -> messageChannel
            "getMentions" -> emptyMentions(jda)
            "getGuild" -> guild
            "getMember" -> members.firstOrNull()
            "isFromGuild" -> true
            "getChannelType" -> ChannelType.TEXT
            else -> defaultValue(method.returnType)
        }
    }

    return MessageContext(client, MessageReceivedEvent(jda, 0L, message), "!", invoked)
}

private fun coerceId(value: Any?): Long? {
    return when (value) {
        is Long -> value
        is String -> value.toLongOrNull()
        else -> null
    }
}

private fun cacheView(type: Class<*>, values: List<*>): Any {
    return Proxy.newProxyInstance(
        ModernDefaultParserCoverageTest::class.java.classLoader,
        arrayOf(type)
    ) { _, method, _ ->
        when (method.name) {
            "iterator" -> values.iterator()
            "spliterator" -> values.spliterator()
            else -> defaultValue(method.returnType)
        }
    }
}

private fun emptyMentions(jda: JDA): Mentions {
    return proxyWithArgs<Mentions> { method, _ ->
        when (method.name) {
            "getJDA" -> jda
            "mentionsEveryone" -> false
            "getUsers",
            "getChannels",
            "getRoles",
            "getCustomEmojis",
            "getMembers",
            "getSlashCommands",
            "getMentions" -> emptyList<Any>()
            else -> defaultValue(method.returnType)
        }
    }
}

private fun userProxy(id: Long, name: String): User {
    return proxyWithArgs(IMentionable::class.java) { method, _ ->
        when (method.name) {
            "getIdLong" -> id
            "getId" -> id.toString()
            "getName" -> name
            "getAsMention" -> "<@$id>"
            else -> defaultValue(method.returnType)
        }
    }
}

private inline fun <reified T> mentionableProxy(id: Long, name: String): T {
    return proxyWithArgs(IMentionable::class.java) { method, _ ->
        when (method.name) {
            "getIdLong" -> id
            "getId" -> id.toString()
            "getName" -> name
            "getEffectiveName" -> name
            "getAsMention" -> "<@$id>"
            "getUser" -> if (T::class.java == Member::class.java) userProxy(id, name) else null
            else -> defaultValue(method.returnType)
        }
    }
}

private inline fun <reified T> guildChannelProxy(
    id: Long,
    name: String,
    channelType: ChannelType,
    vararg extraInterfaces: Class<*>
): T {
    lateinit var instance: Any
    val interfaces = linkedSetOf<Class<*>>(T::class.java).apply { addAll(extraInterfaces) }.toTypedArray()
    instance = Proxy.newProxyInstance(
        ModernDefaultParserCoverageTest::class.java.classLoader,
        interfaces
    ) { _, method, _ ->
        when (method.name) {
            "equals" -> false
            "hashCode" -> id.hashCode()
            "toString" -> "${T::class.java.simpleName}Proxy($name)"
            "getIdLong" -> id
            "getId" -> id.toString()
            "getName" -> name
            "getType", "getChannelType" -> channelType
            "asTextChannel",
            "asNewsChannel",
            "asThreadChannel",
            "asVoiceChannel",
            "asStageChannel",
            "asCategory",
            "asForumChannel",
            "asMediaChannel",
            "asGuildMessageChannel",
            "asAudioChannel",
            "asThreadContainer",
            "asStandardGuildChannel",
            "asStandardGuildMessageChannel" -> method.returnType.takeIf { it.isInstance(instance) }?.cast(instance)
            else -> defaultValue(method.returnType)
        }
    }

    @Suppress("UNCHECKED_CAST")
    return instance as T
}

private inline fun <reified T> proxyWithArgs(
    vararg extraInterfaces: Class<*>,
    noinline handler: (Method, Array<out Any?>?) -> Any? = { method, _ -> defaultValue(method.returnType) }
): T {
    val interfaces = linkedSetOf<Class<*>>(T::class.java).apply { addAll(extraInterfaces) }.toTypedArray()
    return Proxy.newProxyInstance(
        ModernDefaultParserCoverageTest::class.java.classLoader,
        interfaces
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

private class ParserCoverageCog : Cog {
    @Command
    fun parse(ctx: MessageContext) = Unit
}
