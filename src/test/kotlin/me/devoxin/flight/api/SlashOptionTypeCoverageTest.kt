package me.devoxin.flight.api

import gnu.trove.map.hash.TLongObjectHashMap
import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.annotations.SubCommand
import me.devoxin.flight.api.annotations.SubCommandGroup
import me.devoxin.flight.api.arguments.types.Invite
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.command.CommandRegistry
import me.devoxin.flight.api.context.SlashContext
import net.dv8tion.jda.api.entities.IMentionable
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
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
import net.dv8tion.jda.api.entities.channel.unions.ChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.DefaultGuildChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.IPermissionContainerUnion
import net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.entities.emoji.EmojiUnion
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.utils.data.DataObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SlashOptionTypeCoverageTest {
    @Test
    fun `registry exports modern JDA 6 slash option bindings for root direct and grouped commands`() {
        val registry = CommandRegistry().apply {
            register(RootModernSlashCoverageCog())
            register(NestedModernSlashCoverageCog())
        }

        val root = registry.toCommandData(registry.findSlashCommand("modern")!!) as SlashCommandData
        assertEquals(OptionType.MENTIONABLE, root.option("mentionable").type)
        assertChannelTypes(root.option("guild_channel"), ChannelType.guildTypes().toSet())
        assertChannelTypes(
            root.option("guild_message_channel"),
            setOf(
                ChannelType.TEXT,
                ChannelType.NEWS,
                ChannelType.VOICE,
                ChannelType.STAGE,
                ChannelType.GUILD_PUBLIC_THREAD,
                ChannelType.GUILD_PRIVATE_THREAD,
                ChannelType.GUILD_NEWS_THREAD
            )
        )
        assertChannelTypes(
            root.option("standard_guild_channel"),
            setOf(
                ChannelType.TEXT,
                ChannelType.NEWS,
                ChannelType.VOICE,
                ChannelType.STAGE,
                ChannelType.FORUM,
                ChannelType.MEDIA
            )
        )
        assertChannelTypes(
            root.option("standard_guild_message_channel"),
            setOf(ChannelType.TEXT, ChannelType.NEWS)
        )
        assertChannelTypes(root.option("audio_channel"), setOf(ChannelType.VOICE, ChannelType.STAGE))
        assertChannelTypes(
            root.option("permission_container"),
            setOf(
                ChannelType.TEXT,
                ChannelType.NEWS,
                ChannelType.VOICE,
                ChannelType.STAGE,
                ChannelType.FORUM,
                ChannelType.CATEGORY
            )
        )
        assertChannelTypes(
            root.option("thread_container"),
            setOf(ChannelType.TEXT, ChannelType.NEWS, ChannelType.FORUM, ChannelType.MEDIA)
        )
        assertChannelTypes(root.option("default_channel"), setOf(ChannelType.TEXT, ChannelType.NEWS))
        assertChannelTypes(root.option("news_channel"), setOf(ChannelType.NEWS))
        assertChannelTypes(root.option("stage_channel"), setOf(ChannelType.STAGE))
        assertChannelTypes(
            root.option("thread_channel"),
            setOf(
                ChannelType.GUILD_PUBLIC_THREAD,
                ChannelType.GUILD_PRIVATE_THREAD,
                ChannelType.GUILD_NEWS_THREAD
            )
        )
        assertChannelTypes(root.option("forum_channel"), setOf(ChannelType.FORUM))
        assertChannelTypes(root.option("media_channel"), setOf(ChannelType.MEDIA))
        assertChannelTypes(root.option("category"), setOf(ChannelType.CATEGORY))
        assertEquals(OptionType.ATTACHMENT, root.option("attachment").type)

        val nested = registry.toCommandData(registry.findSlashCommand("nested")!!) as SlashCommandData
        val direct = nested.subcommands.single { it.name == "direct" }
        assertEquals(OptionType.MENTIONABLE, direct.option("mentionable").type)
        assertChannelTypes(direct.option("audio_channel"), setOf(ChannelType.VOICE, ChannelType.STAGE))

        val grouped = nested.subcommandGroups.single { it.name == "group" }
            .subcommands.single { it.name == "inside" }
        assertChannelTypes(
            grouped.option("thread_container"),
            setOf(ChannelType.TEXT, ChannelType.NEWS, ChannelType.FORUM, ChannelType.MEDIA)
        )
        assertEquals(OptionType.ATTACHMENT, grouped.option("attachment").type)
    }

    @Test
    fun `registration preflight fails early for unsafe or parser only slash types with suggestions`() {
        val messageChannelError = assertFailsWith<IllegalStateException> {
            CommandRegistry().register(UnsafeMessageChannelCog())
        }
        assertMessageContains(
            messageChannelError,
            "unsafe_message",
            "parameter 'channel'",
            MessageChannelUnion::class.java.name,
            "GuildChannelUnion",
            "GuildMessageChannelUnion"
        )

        val broadChannelError = assertFailsWith<IllegalStateException> {
            CommandRegistry().register(UnsafeChannelUnionCog())
        }
        assertMessageContains(
            broadChannelError,
            "unsafe_channel",
            ChannelUnion::class.java.name,
            "GuildChannelUnion"
        )

        val emojiError = assertFailsWith<IllegalStateException> {
            CommandRegistry().register(UnsafeEmojiCog())
        }
        assertMessageContains(
            emojiError,
            "unsafe_emoji",
            EmojiUnion::class.java.name,
            "String plus explicit emoji parsing",
            "message-only"
        )

        val inviteError = assertFailsWith<IllegalStateException> {
            CommandRegistry().register(UnsafeInviteCog())
        }
        assertMessageContains(
            inviteError,
            "unsafe_invite",
            Invite::class.java.name,
            "String plus explicit invite parsing",
            "message-only"
        )
    }

    @Test
    fun `IMentionable runtime resolution supports member user and role`() {
        val registry = CommandRegistry().apply { register(MentionableRuntimeCog()) }
        val command = registry.findSlashCommand("mentionable_runtime")!!
        val argument = command.arguments.single()

        val member = mentionableProxy<Member>(1L)
        val user = mentionableProxy<User>(2L)
        val role = mentionableProxy<Role>(3L)

        listOf(member, user, role).forEach { mentionable ->
            val resolved = command.resolveArguments(
                listOf(entityOption(argument.slashFriendlyName, OptionType.MENTIONABLE, mentionable))
            )[argument.parameter]

            assertSame(mentionable, resolved)
        }
    }

    @Test
    fun `runtime slash option resolution handles modern channel abstractions integer conversion and attachment`() {
        val registry = CommandRegistry().apply { register(RuntimeResolutionCog()) }
        val command = registry.findSlashCommand("runtime_resolution")!!

        val role = mentionableProxy<Role>(10L)
        val threadChannel = guildChannelProxy<GuildChannelUnion>(
            11L,
            "ops-thread",
            ChannelType.GUILD_PUBLIC_THREAD,
            GuildMessageChannel::class.java,
            ThreadChannel::class.java
        )
        val stageChannel = guildChannelProxy<GuildChannelUnion>(
            12L,
            "ops-stage",
            ChannelType.STAGE,
            AudioChannel::class.java,
            GuildMessageChannel::class.java,
            StageChannel::class.java,
            StandardGuildChannel::class.java
        )
        val forumChannel = guildChannelProxy<GuildChannelUnion>(
            13L,
            "ops-forum",
            ChannelType.FORUM,
            IThreadContainer::class.java,
            ForumChannel::class.java,
            StandardGuildChannel::class.java
        )
        val category = guildChannelProxy<GuildChannelUnion>(
            14L,
            "ops-category",
            ChannelType.CATEGORY,
            IPermissionContainer::class.java,
            Category::class.java
        )
        val attachment = Message.Attachment(
            15L,
            "https://cdn.example/test.txt",
            "https://proxy.example/test.txt",
            "test.txt",
            "txt",
            "text/plain",
            4,
            0,
            0,
            false,
            null,
            0.0,
            null
        )

        val resolved = command.resolveArguments(
            listOf(
                scalarOption(argument(command, "count").slashFriendlyName, OptionType.INTEGER, 7L),
                entityOption(argument(command, "mentionable").slashFriendlyName, OptionType.MENTIONABLE, role),
                entityOption(argument(command, "guildMessageChannel").slashFriendlyName, OptionType.CHANNEL, threadChannel),
                entityOption(argument(command, "audioChannel").slashFriendlyName, OptionType.CHANNEL, stageChannel),
                entityOption(argument(command, "threadContainer").slashFriendlyName, OptionType.CHANNEL, forumChannel),
                entityOption(argument(command, "permissionContainer").slashFriendlyName, OptionType.CHANNEL, category),
                entityOption(argument(command, "forumChannel").slashFriendlyName, OptionType.CHANNEL, forumChannel),
                entityOption(argument(command, "attachment").slashFriendlyName, OptionType.ATTACHMENT, attachment)
            )
        )

        assertEquals(7, resolved[argument(command, "count").parameter])
        assertSame(role, resolved[argument(command, "mentionable").parameter])
        val resolvedGuildMessageChannel = resolved[argument(command, "guildMessageChannel").parameter] as GuildMessageChannelUnion
        assertNotSame(threadChannel as Any, resolvedGuildMessageChannel as Any)
        assertEquals(threadChannel.idLong, resolvedGuildMessageChannel.idLong)
        assertSame(threadChannel as Any, resolvedGuildMessageChannel.asThreadChannel() as Any)

        val resolvedAudioChannel = resolved[argument(command, "audioChannel").parameter] as AudioChannelUnion
        assertNotSame(stageChannel as Any, resolvedAudioChannel as Any)
        assertEquals(stageChannel.idLong, resolvedAudioChannel.idLong)
        assertSame(stageChannel as Any, resolvedAudioChannel.asStageChannel() as Any)
        assertSame(stageChannel as Any, resolvedAudioChannel.asGuildMessageChannel() as Any)

        val resolvedThreadContainer = resolved[argument(command, "threadContainer").parameter] as IThreadContainerUnion
        assertNotSame(forumChannel as Any, resolvedThreadContainer as Any)
        assertEquals(forumChannel.idLong, resolvedThreadContainer.idLong)
        assertSame(forumChannel as Any, resolvedThreadContainer.asForumChannel() as Any)

        val resolvedPermissionContainer = resolved[argument(command, "permissionContainer").parameter] as IPermissionContainerUnion
        assertNotSame(category as Any, resolvedPermissionContainer as Any)
        assertEquals(category.idLong, resolvedPermissionContainer.idLong)
        assertSame(category as Any, resolvedPermissionContainer.asCategory() as Any)

        assertSame(forumChannel, resolved[argument(command, "forumChannel").parameter])
        assertSame(attachment, resolved[argument(command, "attachment").parameter])
    }
}

private fun SlashCommandData.option(name: String): OptionData = options.first { it.name == name }

private fun SubcommandData.option(name: String): OptionData = options.first { it.name == name }

private fun CommandData.option(name: String): OptionData {
    return (this as SlashCommandData).option(name)
}

private fun assertChannelTypes(option: OptionData, expected: Set<ChannelType>) {
    assertEquals(expected, option.channelTypes.toSet())
    assertEquals(OptionType.CHANNEL, option.type)
}

private fun assertMessageContains(error: Throwable, vararg fragments: String) {
    val message = error.message ?: ""
    fragments.forEach { fragment -> assertContains(message, fragment) }
}

private fun argument(command: CommandFunction, name: String) = command.arguments.first { it.name == name }

private fun scalarOption(name: String, type: OptionType, value: Any): OptionMapping {
    return optionMapping(name, type, value, null)
}

private fun entityOption(name: String, type: OptionType, entity: Any): OptionMapping {
    val id = when (entity) {
        is net.dv8tion.jda.api.entities.ISnowflake -> entity.idLong
        else -> error("Unsupported resolved entity for test option mapping: ${entity::class.java.name}")
    }

    return optionMapping(name, type, id, entity)
}

private fun optionMapping(name: String, type: OptionType, value: Any, resolvedEntity: Any?): OptionMapping {
    val data = DataObject.empty()
        .put("type", type.key)
        .put("name", name)
        .put("value", value)
    val resolved = TLongObjectHashMap<Any>()

    if (resolvedEntity != null) {
        resolved.put((value as Number).toLong(), resolvedEntity)
    }

    return OptionMapping(data, resolved, null, null)
}

private inline fun <reified T> mentionableProxy(id: Long): T {
    return proxyWithArgs(IMentionable::class.java) { method, _ ->
        when (method.name) {
            "getIdLong" -> id
            "getId" -> id.toString()
            "getAsMention" -> "<@$id>"
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
    instance = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java, *extraInterfaces)
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

private class RootModernSlashCoverageCog : Cog {
    @Command(description = "Modern slash root coverage")
    fun modern(
        ctx: SlashContext,
        mentionable: IMentionable,
        guildChannel: GuildChannel,
        guildMessageChannel: GuildMessageChannelUnion,
        standardGuildChannel: StandardGuildChannel,
        standardGuildMessageChannel: StandardGuildMessageChannel,
        audioChannel: AudioChannelUnion,
        permissionContainer: IPermissionContainerUnion,
        threadContainer: IThreadContainerUnion,
        defaultChannel: DefaultGuildChannelUnion,
        newsChannel: NewsChannel,
        stageChannel: StageChannel,
        threadChannel: ThreadChannel,
        forumChannel: ForumChannel,
        mediaChannel: MediaChannel,
        category: Category,
        attachment: Message.Attachment
    ) = Unit
}

private class NestedModernSlashCoverageCog : Cog {
    @Command(description = "Nested modern slash coverage")
    @SubCommandGroup(name = "group", description = "Grouped options")
    fun nested(ctx: SlashContext) = Unit

    @SubCommand(parent = "nested", description = "Direct modern options")
    fun direct(ctx: SlashContext, mentionable: IMentionable, audioChannel: AudioChannelUnion) = Unit

    @SubCommand(parent = "nested", group = "group", name = "inside", description = "Grouped modern options")
    fun inside(ctx: SlashContext, threadContainer: IThreadContainerUnion, attachment: Message.Attachment) = Unit
}

private class UnsafeMessageChannelCog : Cog {
    @Command(description = "Unsafe message channel")
    fun unsafe_message(ctx: SlashContext, channel: MessageChannelUnion) = Unit
}

private class UnsafeChannelUnionCog : Cog {
    @Command(description = "Unsafe broad channel")
    fun unsafe_channel(ctx: SlashContext, channel: ChannelUnion) = Unit
}

private class UnsafeEmojiCog : Cog {
    @Command(description = "Unsafe emoji")
    fun unsafe_emoji(ctx: SlashContext, emoji: EmojiUnion) = Unit
}

private class UnsafeInviteCog : Cog {
    @Command(description = "Unsafe invite")
    fun unsafe_invite(ctx: SlashContext, invite: Invite) = Unit
}

private class MentionableRuntimeCog : Cog {
    @Command(description = "Mentionable runtime")
    fun mentionable_runtime(ctx: SlashContext, mentionable: IMentionable) = Unit
}

private class RuntimeResolutionCog : Cog {
    @Command(description = "Runtime resolution")
    fun runtime_resolution(
        ctx: SlashContext,
        count: Int,
        mentionable: IMentionable,
        guildMessageChannel: GuildMessageChannelUnion,
        audioChannel: AudioChannelUnion,
        threadContainer: IThreadContainerUnion,
        permissionContainer: IPermissionContainerUnion,
        forumChannel: ForumChannel,
        attachment: Message.Attachment
    ) = Unit
}
