package me.devoxin.flight.internal.parsers

import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.internal.utils.GuildChannelAdapters
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.IMentionable
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
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
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildChannel
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.DefaultGuildChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.IPermissionContainerUnion
import net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion

internal object GuildChannelParsers {
    fun registerDefaults(target: MutableMap<Class<*>, Parser<*>>) {
        target[IMentionable::class.java] = MentionableParser()

        register(target, GuildChannel::class.java, matches = { true }, convert = GuildChannelAdapters::toGuildChannel)
        register(target, GuildChannelUnion::class.java, matches = { true }, convert = GuildChannelAdapters::toGuildChannelUnion)
        register(
            target,
            GuildMessageChannel::class.java,
            matches = ::isGuildMessageChannel,
            convert = GuildChannelAdapters::toGuildMessageChannel
        )
        register(
            target,
            GuildMessageChannelUnion::class.java,
            matches = ::isGuildMessageChannel,
            convert = GuildChannelAdapters::toGuildMessageChannelUnion
        )
        register(
            target,
            StandardGuildChannel::class.java,
            matches = ::isStandardGuildChannel,
            convert = GuildChannelAdapters::toStandardGuildChannel
        )
        register(
            target,
            StandardGuildMessageChannel::class.java,
            matches = ::isStandardGuildMessageChannel,
            convert = GuildChannelAdapters::toStandardGuildMessageChannel
        )
        register(
            target,
            AudioChannel::class.java,
            matches = ::isAudioChannel,
            convert = GuildChannelAdapters::toAudioChannel
        )
        register(
            target,
            AudioChannelUnion::class.java,
            matches = ::isAudioChannel,
            convert = GuildChannelAdapters::toAudioChannelUnion
        )
        register(
            target,
            IPermissionContainer::class.java,
            matches = ::isPermissionContainer,
            convert = GuildChannelAdapters::toPermissionContainer
        )
        register(
            target,
            IPermissionContainerUnion::class.java,
            matches = ::isPermissionContainer,
            convert = GuildChannelAdapters::toPermissionContainerUnion
        )
        register(
            target,
            IThreadContainer::class.java,
            matches = ::isThreadContainer,
            convert = GuildChannelAdapters::toThreadContainer
        )
        register(
            target,
            IThreadContainerUnion::class.java,
            matches = ::isThreadContainer,
            convert = GuildChannelAdapters::toThreadContainerUnion
        )
        register(
            target,
            DefaultGuildChannelUnion::class.java,
            matches = ::isDefaultGuildChannel,
            convert = GuildChannelAdapters::toDefaultGuildChannelUnion
        )
        register(target, TextChannel::class.java, matches = { it is TextChannel }) { it as? TextChannel }
        register(target, NewsChannel::class.java, matches = { it is NewsChannel }) { it as? NewsChannel }
        register(target, VoiceChannel::class.java, matches = { it is VoiceChannel }) { it as? VoiceChannel }
        register(target, StageChannel::class.java, matches = { it is StageChannel }) { it as? StageChannel }
        register(target, ThreadChannel::class.java, matches = { it is ThreadChannel }) { it as? ThreadChannel }
        register(target, ForumChannel::class.java, matches = { it is ForumChannel }) { it as? ForumChannel }
        register(target, MediaChannel::class.java, matches = { it is MediaChannel }) { it as? MediaChannel }
        register(target, Category::class.java, matches = { it is Category }) { it as? Category }
    }

    private fun <T : Any> register(
        target: MutableMap<Class<*>, Parser<*>>,
        type: Class<T>,
        matches: (GuildChannel) -> Boolean,
        convert: (GuildChannel) -> T?
    ) {
        target[type] = GuildChannelParser(matches, convert)
    }

    private fun isGuildMessageChannel(channel: GuildChannel) = GuildChannelAdapters.toGuildMessageChannel(channel) != null

    private fun isStandardGuildChannel(channel: GuildChannel) = GuildChannelAdapters.toStandardGuildChannel(channel) != null

    private fun isStandardGuildMessageChannel(channel: GuildChannel) =
        GuildChannelAdapters.toStandardGuildMessageChannel(channel) != null

    private fun isAudioChannel(channel: GuildChannel) = GuildChannelAdapters.toAudioChannel(channel) != null

    private fun isPermissionContainer(channel: GuildChannel) = GuildChannelAdapters.toPermissionContainer(channel) != null

    private fun isThreadContainer(channel: GuildChannel) = GuildChannelAdapters.toThreadContainer(channel) != null

    private fun isDefaultGuildChannel(channel: GuildChannel) = GuildChannelAdapters.toDefaultGuildChannelUnion(channel) != null
}

private class MentionableParser : Parser<IMentionable> {
    private val memberParser = MemberParser()
    private val userParser = UserParser()
    private val roleParser = RoleParser()

    override fun parse(ctx: MessageContext, param: String): IMentionable? {
        return memberParser.parse(ctx, param)
            ?: userParser.parse(ctx, param)
            ?: roleParser.parse(ctx, param)
    }
}

private class GuildChannelParser<T : Any>(
    private val matches: (GuildChannel) -> Boolean,
    private val convert: (GuildChannel) -> T?
) : Parser<T> {
    override fun parse(ctx: MessageContext, param: String): T? {
        val guild = ctx.guild ?: return null
        val snowflake = SnowflakeParser.INSTANCE.parse(ctx, param)?.resolved
        val channel = allGuildChannels(guild).firstOrNull { channel ->
            matches(channel) && if (snowflake != null) {
                channel.idLong == snowflake
            } else {
                channel.name == param
            }
        } ?: return null

        return convert(channel)
    }

    private fun allGuildChannels(guild: Guild): Sequence<GuildChannel> {
        return sequence {
            yieldAll(guild.channels)
            yieldAll(guild.threadChannelCache)
        }.distinctBy(GuildChannel::getIdLong)
    }
}
