package me.devoxin.flight.internal.utils

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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal object GuildChannelAdapters {
    fun toGuildChannel(channel: GuildChannel): GuildChannel = channel

    fun toGuildChannelUnion(channel: GuildChannel): GuildChannelUnion {
        return channel as? GuildChannelUnion ?: createProxy(channel, channel, GuildChannelUnion::class.java) { method ->
            when (method.name) {
                "asTextChannel" -> requireType(channel as? TextChannel, channel, TextChannel::class.java.simpleName)
                "asNewsChannel" -> requireType(channel as? NewsChannel, channel, NewsChannel::class.java.simpleName)
                "asThreadChannel" -> requireType(channel as? ThreadChannel, channel, ThreadChannel::class.java.simpleName)
                "asVoiceChannel" -> requireType(channel as? VoiceChannel, channel, VoiceChannel::class.java.simpleName)
                "asStageChannel" -> requireType(channel as? StageChannel, channel, StageChannel::class.java.simpleName)
                "asCategory" -> requireType(channel as? Category, channel, Category::class.java.simpleName)
                "asForumChannel" -> requireType(channel as? ForumChannel, channel, ForumChannel::class.java.simpleName)
                "asMediaChannel" -> requireType(channel as? MediaChannel, channel, MediaChannel::class.java.simpleName)
                "asGuildMessageChannel" -> requireType(
                    channel as? GuildMessageChannel,
                    channel,
                    GuildMessageChannel::class.java.simpleName
                )
                "asAudioChannel" -> requireType(channel as? AudioChannel, channel, AudioChannel::class.java.simpleName)
                "asThreadContainer" -> requireType(
                    channel as? IThreadContainer,
                    channel,
                    IThreadContainer::class.java.simpleName
                )
                "asStandardGuildChannel" -> requireType(
                    channel as? StandardGuildChannel,
                    channel,
                    StandardGuildChannel::class.java.simpleName
                )
                "asStandardGuildMessageChannel" -> requireType(
                    channel as? StandardGuildMessageChannel,
                    channel,
                    StandardGuildMessageChannel::class.java.simpleName
                )
                else -> null
            }
        }
    }

    fun toGuildMessageChannel(channel: GuildChannel): GuildMessageChannel? = channel as? GuildMessageChannel

    fun toGuildMessageChannelUnion(channel: GuildChannel): GuildMessageChannelUnion? {
        val delegate = channel as? GuildMessageChannel ?: return null
        return delegate as? GuildMessageChannelUnion ?: createProxy(channel, delegate, GuildMessageChannelUnion::class.java) { method ->
            when (method.name) {
                "asTextChannel" -> requireType(delegate as? TextChannel, channel, TextChannel::class.java.simpleName)
                "asNewsChannel" -> requireType(delegate as? NewsChannel, channel, NewsChannel::class.java.simpleName)
                "asThreadChannel" -> requireType(delegate as? ThreadChannel, channel, ThreadChannel::class.java.simpleName)
                "asVoiceChannel" -> requireType(delegate as? VoiceChannel, channel, VoiceChannel::class.java.simpleName)
                "asStageChannel" -> requireType(delegate as? StageChannel, channel, StageChannel::class.java.simpleName)
                "asThreadContainer" -> requireType(
                    delegate as? IThreadContainer,
                    channel,
                    IThreadContainer::class.java.simpleName
                )
                "asStandardGuildChannel" -> requireType(
                    delegate as? StandardGuildChannel,
                    channel,
                    StandardGuildChannel::class.java.simpleName
                )
                "asStandardGuildMessageChannel" -> requireType(
                    delegate as? StandardGuildMessageChannel,
                    channel,
                    StandardGuildMessageChannel::class.java.simpleName
                )
                "asAudioChannel" -> requireType(delegate as? AudioChannel, channel, AudioChannel::class.java.simpleName)
                else -> null
            }
        }
    }

    fun toStandardGuildChannel(channel: GuildChannel): StandardGuildChannel? = channel as? StandardGuildChannel

    fun toStandardGuildMessageChannel(channel: GuildChannel): StandardGuildMessageChannel? {
        return channel as? StandardGuildMessageChannel
    }

    fun toAudioChannel(channel: GuildChannel): AudioChannel? = channel as? AudioChannel

    fun toAudioChannelUnion(channel: GuildChannel): AudioChannelUnion? {
        val delegate = channel as? AudioChannel ?: return null
        return delegate as? AudioChannelUnion ?: createProxy(channel, delegate, AudioChannelUnion::class.java) { method ->
            when (method.name) {
                "asVoiceChannel" -> requireType(delegate as? VoiceChannel, channel, VoiceChannel::class.java.simpleName)
                "asStageChannel" -> requireType(delegate as? StageChannel, channel, StageChannel::class.java.simpleName)
                "asGuildMessageChannel" -> requireType(
                    delegate as? GuildMessageChannel,
                    channel,
                    GuildMessageChannel::class.java.simpleName
                )
                else -> null
            }
        }
    }

    fun toPermissionContainer(channel: GuildChannel): IPermissionContainer? = channel as? IPermissionContainer

    fun toPermissionContainerUnion(channel: GuildChannel): IPermissionContainerUnion? {
        val delegate = channel as? IPermissionContainer ?: return null
        return delegate as? IPermissionContainerUnion ?: createProxy(channel, delegate, IPermissionContainerUnion::class.java) { method ->
            when (method.name) {
                "asTextChannel" -> requireType(channel as? TextChannel, channel, TextChannel::class.java.simpleName)
                "asNewsChannel" -> requireType(channel as? NewsChannel, channel, NewsChannel::class.java.simpleName)
                "asVoiceChannel" -> requireType(channel as? VoiceChannel, channel, VoiceChannel::class.java.simpleName)
                "asStageChannel" -> requireType(channel as? StageChannel, channel, StageChannel::class.java.simpleName)
                "asCategory" -> requireType(channel as? Category, channel, Category::class.java.simpleName)
                "asForumChannel" -> requireType(channel as? ForumChannel, channel, ForumChannel::class.java.simpleName)
                "asGuildMessageChannel" -> requireType(
                    channel as? GuildMessageChannel,
                    channel,
                    GuildMessageChannel::class.java.simpleName
                )
                "asAudioChannel" -> requireType(channel as? AudioChannel, channel, AudioChannel::class.java.simpleName)
                "asThreadContainer" -> requireType(
                    channel as? IThreadContainer,
                    channel,
                    IThreadContainer::class.java.simpleName
                )
                "asStandardGuildChannel" -> requireType(
                    channel as? StandardGuildChannel,
                    channel,
                    StandardGuildChannel::class.java.simpleName
                )
                "asStandardGuildMessageChannel" -> requireType(
                    channel as? StandardGuildMessageChannel,
                    channel,
                    StandardGuildMessageChannel::class.java.simpleName
                )
                else -> null
            }
        }
    }

    fun toThreadContainer(channel: GuildChannel): IThreadContainer? = channel as? IThreadContainer

    fun toThreadContainerUnion(channel: GuildChannel): IThreadContainerUnion? {
        val delegate = channel as? IThreadContainer ?: return null
        return delegate as? IThreadContainerUnion ?: createProxy(channel, delegate, IThreadContainerUnion::class.java) { method ->
            when (method.name) {
                "asTextChannel" -> requireType(channel as? TextChannel, channel, TextChannel::class.java.simpleName)
                "asNewsChannel" -> requireType(channel as? NewsChannel, channel, NewsChannel::class.java.simpleName)
                "asForumChannel" -> requireType(channel as? ForumChannel, channel, ForumChannel::class.java.simpleName)
                "asMediaChannel" -> requireType(channel as? MediaChannel, channel, MediaChannel::class.java.simpleName)
                "asGuildMessageChannel" -> requireType(
                    channel as? GuildMessageChannel,
                    channel,
                    GuildMessageChannel::class.java.simpleName
                )
                "asStandardGuildChannel" -> requireType(
                    channel as? StandardGuildChannel,
                    channel,
                    StandardGuildChannel::class.java.simpleName
                )
                "asStandardGuildMessageChannel" -> requireType(
                    channel as? StandardGuildMessageChannel,
                    channel,
                    StandardGuildMessageChannel::class.java.simpleName
                )
                else -> null
            }
        }
    }

    fun toDefaultGuildChannelUnion(channel: GuildChannel): DefaultGuildChannelUnion? {
        val delegate = channel as? StandardGuildChannel ?: return null
        if (channel !is TextChannel && channel !is NewsChannel) {
            return null
        }

        return delegate as? DefaultGuildChannelUnion ?: createProxy(channel, delegate, DefaultGuildChannelUnion::class.java) { method ->
            when (method.name) {
                "asTextChannel" -> requireType(channel as? TextChannel, channel, TextChannel::class.java.simpleName)
                "asNewsChannel" -> requireType(channel as? NewsChannel, channel, NewsChannel::class.java.simpleName)
                "asThreadContainer" -> requireType(
                    channel as? IThreadContainer,
                    channel,
                    IThreadContainer::class.java.simpleName
                )
                "asStandardGuildMessageChannel" -> requireType(
                    channel as? StandardGuildMessageChannel,
                    channel,
                    StandardGuildMessageChannel::class.java.simpleName
                )
                else -> null
            }
        }
    }

    private fun <T : Any> createProxy(
        channel: GuildChannel,
        delegate: Any,
        target: Class<T>,
        specialHandler: (Method) -> Any?
    ): T {
        val handler = UnionAdapterInvocationHandler(channel, delegate, specialHandler)

        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(target.classLoader, arrayOf(target), handler) as T
    }

    private fun <T : Any> requireType(value: T?, channel: GuildChannel, targetName: String): T {
        return value ?: throw IllegalStateException(
            "Channel of type ${channel.type.name} cannot be viewed as $targetName."
        )
    }
}

private class UnionAdapterInvocationHandler(
    private val channel: GuildChannel,
    private val delegate: Any,
    private val specialHandler: (Method) -> Any?
) : InvocationHandler {
    val underlyingChannel: GuildChannel
        get() = channel

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        return when (method.name) {
            "equals" -> channel == unwrapChannel(args?.firstOrNull())
            "hashCode" -> channel.hashCode()
            "toString" -> channel.toString()
            else -> specialHandler(method) ?: invokeDelegate(method, args)
        }
    }

    private fun invokeDelegate(method: Method, args: Array<out Any?>?): Any? {
        return try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (ex: InvocationTargetException) {
            throw ex.targetException
        }
    }
}

private fun unwrapChannel(value: Any?): Any? {
    if (value == null || !Proxy.isProxyClass(value.javaClass)) {
        return value
    }

    val handler = Proxy.getInvocationHandler(value)
    return if (handler is UnionAdapterInvocationHandler) handler.underlyingChannel else value
}
