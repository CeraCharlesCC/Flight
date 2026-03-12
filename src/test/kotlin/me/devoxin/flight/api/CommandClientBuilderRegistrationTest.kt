package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Choices
import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.annotations.GuildIds
import me.devoxin.flight.api.annotations.MessageCommand
import me.devoxin.flight.api.annotations.SubCommand
import me.devoxin.flight.api.annotations.SubCommandGroup
import me.devoxin.flight.api.annotations.UserCommand
import me.devoxin.flight.api.annotations.choice.StringChoice
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.command.CommandRegistry
import me.devoxin.flight.api.command.DiscordCommandTarget
import me.devoxin.flight.api.context.MessageCommandContext
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.context.SlashContext
import me.devoxin.flight.api.context.UserCommandContext
import me.devoxin.flight.api.localization.CommandLocalizationField
import me.devoxin.flight.api.localization.CommandLocalizationProvider
import me.devoxin.flight.api.sync.CommandSyncScope
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.localization.LocalizationMap
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommandClientBuilderRegistrationTest {
    @Test
    fun `builder registers the supplied cog instance during build`() {
        val cog = StatefulBuilderRegistrationCog()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .register(cog)
            .build()

        val command = client.commands.findMessageCommand("stateful")
        assertNotNull(command)
        assertSame(cog, command.cog)

        client.onEvent(messageReceivedEvent("!stateful", userProxy(1L)))
    assertTrue(cog.invoked.await(3, TimeUnit.SECONDS))
        assertEquals(1, cog.invocationCount)

        client.shutdown()
    }

    @Test
    fun `builder vararg and iterable registration preserve processing order`() {
        val varargClient = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .register(AlphaBuilderCog(), BetaBuilderCog())
            .build()

        assertContentEquals(listOf("alpha", "beta"), varargClient.commands.values.map(CommandFunction::name))
        varargClient.shutdown()

        val iterableClient = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .registerAll(linkedSetOf(BetaBuilderCog(), AlphaBuilderCog()))
            .build()

        assertContentEquals(listOf("beta", "alpha"), iterableClient.commands.values.map(CommandFunction::name))
        iterableClient.shutdown()
    }

    @Test
    fun `registry batch helpers register vararg and iterable cogs`() {
        val varargRegistry = CommandRegistry().apply {
            register(AlphaBuilderCog(), BetaBuilderCog())
        }
        assertContentEquals(listOf("alpha", "beta"), varargRegistry.values.map(CommandFunction::name))

        val iterableRegistry = CommandRegistry().apply {
            registerAll(linkedSetOf(BetaBuilderCog(), AlphaBuilderCog()))
        }
        assertContentEquals(listOf("beta", "alpha"), iterableRegistry.values.map(CommandFunction::name))
    }

    @Test
    fun `registry failure is attributed to the cog and does not partially register that cog`() {
        val registry = CommandRegistry().apply {
            register(HealthyBuilderCog())
        }
        val brokenCog = BrokenAtomicBuilderCog("goal-8")

        val error = assertFailsWith<IllegalStateException> {
            registry.register(brokenCog)
        }

        assertContains(error.message ?: "", "Failed to register cog")
        assertContains(error.message ?: "", brokenCog::class.qualifiedName ?: brokenCog::class.simpleName ?: "BrokenAtomicBuilderCog")
        assertContains(error.message ?: "", brokenCog.toString())
        assertNotNull(error.cause)
        assertContains(error.cause?.message ?: "", "message trigger 'dup'")

        assertNotNull(registry.findMessageCommand("healthy"))
        assertTrue(registry.findCommandsByCog(brokenCog).isEmpty())
        assertEquals(1, registry.values.size)
    }

    @Test
    fun `builder explicit registration exports the same command targets as direct registry registration`() {
        val localizationProvider = CommandLocalizationProvider { request ->
            when {
                request.field == CommandLocalizationField.COMMAND_NAME &&
                    request.commandType == JdaCommand.Type.USER &&
                    request.commandName == "Inspect manifest" -> {
                    mapOf(DiscordLocale.FRENCH to "Inspecter manifeste")
                }

                request.field == CommandLocalizationField.SUBCOMMAND_GROUP_NAME &&
                    request.commandName == "manifest" &&
                    request.subcommandGroupName == "crew" -> {
                    mapOf(DiscordLocale.FRENCH to "equipage")
                }

                request.field == CommandLocalizationField.SUBCOMMAND_NAME &&
                    request.commandName == "manifest" &&
                    request.subcommandGroupName == "crew" &&
                    request.subcommandName == "assign" -> {
                    mapOf(DiscordLocale.FRENCH to "affecter")
                }

                request.field == CommandLocalizationField.OPTION_NAME &&
                    request.commandName == "manifest" &&
                    request.subcommandName == "status" &&
                    request.optionName == "mode" -> {
                    mapOf(DiscordLocale.FRENCH to "mode")
                }

                request.field == CommandLocalizationField.CHOICE_NAME &&
                    request.commandName == "manifest" &&
                    request.subcommandName == "status" &&
                    request.optionName == "mode" &&
                    request.choiceValue == "OPEN" -> {
                    mapOf(DiscordLocale.FRENCH to "ouvert")
                }

                else -> emptyMap()
            }
        }

        val directRegistry = CommandRegistry().apply {
            register(BuilderEquivalenceCog())
        }
        val builderClient = CommandClient.builder()
            .setPrefixes("!")
            .setAllowMentionPrefix(false)
            .configureDefaultHelpCommand { enabled = false }
            .setCommandLocalizationProvider(localizationProvider)
            .register(BuilderEquivalenceCog())
            .build()

        val directTargets = normalizeTargets(directRegistry.toDiscordCommandTargets(localizationProvider))
        val builderTargets = normalizeTargets(builderClient.commands.toDiscordCommandTargets(builderClient.commandLocalizationProvider))

        assertEquals(directTargets, builderTargets)

        builderClient.shutdown()
    }
}

class StatefulBuilderRegistrationCog : Cog {
    val invoked = CountDownLatch(1)
    var invocationCount: Int = 0

    @Command
    fun stateful(ctx: MessageContext) {
        invocationCount += 1
        invoked.countDown()
    }
}

class AlphaBuilderCog : Cog {
    @Command
    fun alpha(ctx: MessageContext) = Unit
}

class BetaBuilderCog : Cog {
    @Command
    fun beta(ctx: MessageContext) = Unit
}

class HealthyBuilderCog : Cog {
    @Command
    fun healthy(ctx: MessageContext) = Unit
}

class BrokenAtomicBuilderCog(
    private val label: String
) : Cog {
    @Command(aliases = ["dup"])
    fun first(ctx: MessageContext) = Unit

    @Command(aliases = ["dup"])
    fun second(ctx: MessageContext) = Unit

    override fun toString(): String {
        return "BrokenAtomicBuilderCog(label=$label)"
    }
}

class BuilderEquivalenceCog : Cog {
    @Command(description = "Manifest operations")
    @SubCommandGroup(name = "crew", description = "Crew operations")
    fun manifest(ctx: SlashContext) = Unit

    @SubCommand(parent = "manifest", description = "Show manifest status")
    fun status(
        ctx: SlashContext,
        @Choices(string = [StringChoice("Open", "OPEN"), StringChoice("Closed", "CLOSED")])
        mode: String
    ) = Unit

    @SubCommand(parent = "manifest", group = "crew", description = "Assign crew")
    fun assign(ctx: SlashContext, userId: String) = Unit

    @UserCommand(name = "Inspect manifest")
    fun inspectManifest(ctx: UserCommandContext) = Unit

    @GuildIds([1001L, 1002L])
    @MessageCommand(name = "Review manifest")
    fun reviewManifest(ctx: MessageCommandContext, target: Message) = Unit
}

private fun normalizeTargets(targets: List<DiscordCommandTarget>): List<String> {
    return targets.map { target ->
        buildString {
            append(scopeLabel(target.scope))
            append(':')
            append(target.commands.joinToString("|") { normalizeCommand(it) })
        }
    }
}

private fun scopeLabel(scope: CommandSyncScope): String {
    return when (scope) {
        CommandSyncScope.Global -> "global"
        is CommandSyncScope.Guild -> "guild:${scope.guildId}"
    }
}

private fun normalizeCommand(command: CommandData): String {
    val prefix = listOf(
        command.type.name,
        command.name,
        normalizeLocalizationMap(command.nameLocalizations)
    ).joinToString("~")

    return when (command) {
        is SlashCommandData -> listOf(
            prefix,
            command.description,
            normalizeLocalizationMap(command.descriptionLocalizations),
            command.options.joinToString(",") { normalizeOption(it) },
            command.subcommands.joinToString(",") { normalizeSubcommand(it) },
            command.subcommandGroups.joinToString(",") { normalizeSubcommandGroup(it) }
        ).joinToString("#")

        else -> prefix
    }
}

private fun normalizeSubcommand(subcommand: SubcommandData): String {
    return listOf(
        subcommand.name,
        subcommand.description,
        normalizeLocalizationMap(subcommand.nameLocalizations),
        normalizeLocalizationMap(subcommand.descriptionLocalizations),
        subcommand.options.joinToString(",") { normalizeOption(it) }
    ).joinToString("~")
}

private fun normalizeSubcommandGroup(group: SubcommandGroupData): String {
    return listOf(
        group.name,
        group.description,
        normalizeLocalizationMap(group.nameLocalizations),
        normalizeLocalizationMap(group.descriptionLocalizations),
        group.subcommands.joinToString(",") { normalizeSubcommand(it) }
    ).joinToString("~")
}

private fun normalizeOption(option: OptionData): String {
    return listOf(
        option.name,
        option.type.name,
        option.description,
        option.isRequired.toString(),
        option.isAutoComplete.toString(),
        option.channelTypes.joinToString(",") { it.name },
        normalizeLocalizationMap(option.nameLocalizations),
        normalizeLocalizationMap(option.descriptionLocalizations),
        option.choices.joinToString(",") { normalizeChoice(it) }
    ).joinToString("~")
}

private fun normalizeChoice(choice: Choice): String {
    val value = when (choice.type) {
        net.dv8tion.jda.api.interactions.commands.OptionType.STRING -> choice.asString
        net.dv8tion.jda.api.interactions.commands.OptionType.INTEGER -> choice.asLong.toString()
        net.dv8tion.jda.api.interactions.commands.OptionType.NUMBER -> choice.asDouble.toString()
        else -> choice.name
    }

    return listOf(choice.name, choice.type.name, value, normalizeLocalizationMap(choice.nameLocalizations)).joinToString("~")
}

private fun normalizeLocalizationMap(localizations: LocalizationMap): String {
    return localizations.toMap().entries
        .sortedBy { it.key.name }
        .joinToString(",") { "${it.key.name}=${it.value}" }
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
