package me.devoxin.flight.internal.arguments

import me.devoxin.flight.api.arguments.types.Invite
import me.devoxin.flight.api.arguments.types.Snowflake
import me.devoxin.flight.internal.utils.EnumUtils
import me.devoxin.flight.internal.utils.GuildChannelAdapters
import net.dv8tion.jda.api.entities.IMentionable
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer
import net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer
import net.dv8tion.jda.api.entities.channel.concrete.Category
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.MediaChannel
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
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
import net.dv8tion.jda.api.entities.emoji.CustomEmoji
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.EmojiUnion
import net.dv8tion.jda.api.entities.emoji.UnicodeEmoji
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType

internal object SlashOptionSupport {
    internal data class Binding(
        val optionType: OptionType,
        val channelTypes: Set<ChannelType> = emptySet(),
        val resolve: (OptionMapping) -> Any?
    )

    private val allGuildChannelTypes = ChannelType.guildTypes().toSet()
    private val guildMessageChannelTypes = linkedSetOf(
        ChannelType.TEXT,
        ChannelType.NEWS,
        ChannelType.VOICE,
        ChannelType.STAGE,
        ChannelType.GUILD_PUBLIC_THREAD,
        ChannelType.GUILD_PRIVATE_THREAD,
        ChannelType.GUILD_NEWS_THREAD
    )
    private val standardGuildChannelTypes = linkedSetOf(
        ChannelType.TEXT,
        ChannelType.NEWS,
        ChannelType.VOICE,
        ChannelType.STAGE,
        ChannelType.FORUM,
        ChannelType.MEDIA
    )
    private val defaultGuildChannelTypes = linkedSetOf(
        ChannelType.TEXT,
        ChannelType.NEWS
    )
    private val audioChannelTypes = linkedSetOf(
        ChannelType.VOICE,
        ChannelType.STAGE
    )
    private val permissionContainerTypes = linkedSetOf(
        ChannelType.TEXT,
        ChannelType.NEWS,
        ChannelType.VOICE,
        ChannelType.STAGE,
        ChannelType.FORUM,
        ChannelType.CATEGORY
    )
    private val threadContainerTypes = linkedSetOf(
        ChannelType.TEXT,
        ChannelType.NEWS,
        ChannelType.FORUM,
        ChannelType.MEDIA
    )
    private val threadChannelTypes = linkedSetOf(
        ChannelType.GUILD_PUBLIC_THREAD,
        ChannelType.GUILD_PRIVATE_THREAD,
        ChannelType.GUILD_NEWS_THREAD
    )

    private val enumBinding = Binding(OptionType.STRING) { it.asString }

    private val bindings = mapOf(
        String::class.java to scalarBinding(OptionType.STRING) { it.asString },
        Int::class.javaObjectType to scalarBinding(OptionType.INTEGER) { it.asInt },
        Long::class.javaObjectType to scalarBinding(OptionType.INTEGER) { it.asLong },
        Double::class.javaObjectType to scalarBinding(OptionType.NUMBER) { it.asDouble },
        Float::class.javaObjectType to scalarBinding(OptionType.NUMBER) { it.asDouble.toFloat() },
        Boolean::class.javaObjectType to scalarBinding(OptionType.BOOLEAN) { it.asBoolean },
        Member::class.java to scalarBinding(OptionType.USER) { it.asMember },
        User::class.java to scalarBinding(OptionType.USER) { it.asUser },
        IMentionable::class.java to scalarBinding(OptionType.MENTIONABLE) { it.asMentionable },
        Role::class.java to scalarBinding(OptionType.ROLE) { it.asRole },
        GuildChannel::class.java to channelBinding(allGuildChannelTypes, GuildChannelAdapters::toGuildChannel),
        GuildChannelUnion::class.java to channelBinding(allGuildChannelTypes, GuildChannelAdapters::toGuildChannelUnion),
        GuildMessageChannel::class.java to channelBinding(
            guildMessageChannelTypes,
            GuildChannelAdapters::toGuildMessageChannel
        ),
        GuildMessageChannelUnion::class.java to channelBinding(
            guildMessageChannelTypes,
            GuildChannelAdapters::toGuildMessageChannelUnion
        ),
        StandardGuildChannel::class.java to channelBinding(
            standardGuildChannelTypes,
            GuildChannelAdapters::toStandardGuildChannel
        ),
        StandardGuildMessageChannel::class.java to channelBinding(
            defaultGuildChannelTypes,
            GuildChannelAdapters::toStandardGuildMessageChannel
        ),
        AudioChannel::class.java to channelBinding(audioChannelTypes, GuildChannelAdapters::toAudioChannel),
        AudioChannelUnion::class.java to channelBinding(audioChannelTypes, GuildChannelAdapters::toAudioChannelUnion),
        IPermissionContainer::class.java to channelBinding(
            permissionContainerTypes,
            GuildChannelAdapters::toPermissionContainer
        ),
        IPermissionContainerUnion::class.java to channelBinding(
            permissionContainerTypes,
            GuildChannelAdapters::toPermissionContainerUnion
        ),
        IThreadContainer::class.java to channelBinding(threadContainerTypes, GuildChannelAdapters::toThreadContainer),
        IThreadContainerUnion::class.java to channelBinding(
            threadContainerTypes,
            GuildChannelAdapters::toThreadContainerUnion
        ),
        DefaultGuildChannelUnion::class.java to channelBinding(
            defaultGuildChannelTypes,
            GuildChannelAdapters::toDefaultGuildChannelUnion
        ),
        TextChannel::class.java to channelBinding(setOf(ChannelType.TEXT)) { it as? TextChannel },
        NewsChannel::class.java to channelBinding(setOf(ChannelType.NEWS)) { it as? NewsChannel },
        VoiceChannel::class.java to channelBinding(setOf(ChannelType.VOICE)) { it as? VoiceChannel },
        StageChannel::class.java to channelBinding(setOf(ChannelType.STAGE)) { it as? StageChannel },
        ThreadChannel::class.java to channelBinding(threadChannelTypes) { it as? ThreadChannel },
        ForumChannel::class.java to channelBinding(setOf(ChannelType.FORUM)) { it as? ForumChannel },
        MediaChannel::class.java to channelBinding(setOf(ChannelType.MEDIA)) { it as? MediaChannel },
        Category::class.java to channelBinding(setOf(ChannelType.CATEGORY)) { it as? Category },
        Message.Attachment::class.java to scalarBinding(OptionType.ATTACHMENT) { it.asAttachment }
    )

    fun bindingFor(type: Class<*>): Binding {
        return if (type.isEnum) enumBinding else bindings[type] ?: throw unsupportedType(type)
    }

    fun resolve(type: Class<*>, mapping: OptionMapping): Any? {
        return if (type.isEnum) {
            resolveEnum(type, mapping)
        } else {
            bindingFor(type).resolve(mapping)
        }
    }

    private fun resolveEnum(type: Class<*>, mapping: OptionMapping): Any {
        requireOptionType(mapping, OptionType.STRING)

        @Suppress("UNCHECKED_CAST")
        return EnumUtils.resolveEnum(type as Class<out Enum<*>>, mapping.asString)
            ?: throw IllegalStateException(
                "Unable to resolve ${type.simpleName} from option '${mapping.name}' value '${mapping.asString}'."
            )
    }

    private fun scalarBinding(
        optionType: OptionType,
        resolve: (OptionMapping) -> Any?
    ): Binding {
        return Binding(optionType) { mapping ->
            requireOptionType(mapping, optionType)
            resolve(mapping)
        }
    }

    private fun channelBinding(channelTypes: Set<ChannelType>, resolve: (GuildChannel) -> Any?): Binding {
        return Binding(OptionType.CHANNEL, channelTypes) { mapping ->
            requireOptionType(mapping, OptionType.CHANNEL)

            val channel = mapping.asChannel
            val actualType = channel.type
            if (actualType !in channelTypes) {
                throw IllegalStateException(
                    "Expected one of ${channelTypes.joinToString { it.name }} channel types, but received ${actualType.name}."
                )
            }

            resolve(channel)
                ?: throw IllegalStateException(
                    "Resolved ${actualType.name} channel for option '${mapping.name}' is not assignable to the requested argument type."
                )
        }
    }

    private fun requireOptionType(mapping: OptionMapping, expected: OptionType) {
        if (mapping.type != expected) {
            throw IllegalStateException(
                "Expected slash option type ${expected.name} for option '${mapping.name}', but received ${mapping.type.name}."
            )
        }
    }

    private fun unsupportedType(type: Class<*>): IllegalStateException {
        val message = when {
            type == Channel::class.java ||
                type == ChannelUnion::class.java ||
                type == MessageChannel::class.java ||
                type == MessageChannelUnion::class.java -> {
                "${type.name} is not safe for slash CHANNEL options because it can represent DM, private, or group channels. " +
                    "Use GuildChannelUnion, GuildMessageChannelUnion, or a concrete guild channel type instead."
            }

            Emoji::class.java.isAssignableFrom(type) ||
                EmojiUnion::class.java.isAssignableFrom(type) ||
                UnicodeEmoji::class.java.isAssignableFrom(type) ||
                CustomEmoji::class.java.isAssignableFrom(type) -> {
                "Discord slash commands do not have a native emoji option type for ${type.name}. " +
                    "Use String plus explicit emoji parsing, or keep this command message-only."
            }

            type == Invite::class.java || type == net.dv8tion.jda.api.entities.Invite::class.java -> {
                "${type.name} is only supported by message parsing and has no native Discord slash option type. " +
                    "Use String plus explicit invite parsing, or keep this command message-only."
            }

            type == Snowflake::class.java -> {
                "${type.name} is a parser-only wrapper and has no native Discord slash option type. " +
                    "Use String or Long instead, or keep this command message-only."
            }

            Channel::class.java.isAssignableFrom(type) ||
                ChannelUnion::class.java.isAssignableFrom(type) ||
                MessageChannel::class.java.isAssignableFrom(type) ||
                MessageChannelUnion::class.java.isAssignableFrom(type) -> {
                "${type.name} is not supported as a slash CHANNEL option. " +
                    "Use a guild-safe type such as GuildChannelUnion, GuildMessageChannelUnion, or a concrete guild channel type."
            }

            else -> "No native Discord slash option type is available for ${type.name}."
        }

        return IllegalStateException(message)
    }
}
