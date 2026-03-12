package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Choices
import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.annotations.Describe
import me.devoxin.flight.api.annotations.GuildIds
import me.devoxin.flight.api.annotations.MessageCommand
import me.devoxin.flight.api.annotations.Name
import me.devoxin.flight.api.annotations.SubCommand
import me.devoxin.flight.api.annotations.UserCommand
import me.devoxin.flight.api.annotations.choice.StringChoice
import me.devoxin.flight.api.check.CheckType
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.command.CommandRegistry
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.api.context.MessageCommandContext
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.context.SlashContext
import me.devoxin.flight.api.context.UserCommandContext
import me.devoxin.flight.api.help.DefaultHelpCommand
import me.devoxin.flight.api.hooks.DefaultCommandEventAdapter
import me.devoxin.flight.api.localization.CommandLocalizationField
import me.devoxin.flight.api.localization.CommandLocalizationProvider
import me.devoxin.flight.api.sync.CommandSyncCommand
import me.devoxin.flight.api.sync.CommandSyncOptions
import me.devoxin.flight.api.sync.CommandSyncScope
import me.devoxin.flight.api.sync.CommandSyncSkipReason
import me.devoxin.flight.api.sync.CommandSyncTargetState
import me.devoxin.flight.internal.entities.Executable
import me.devoxin.flight.internal.sync.CommandSyncBackend
import me.devoxin.flight.internal.sync.CommandSyncExecutor
import me.devoxin.flight.internal.sync.PlannedCommandSync
import me.devoxin.flight.internal.sync.PlannedCommandSyncTarget
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.IntegrationType
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.context.MessageContextInteraction
import net.dv8tion.jda.api.interactions.commands.context.UserContextInteraction
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommandSyncAndLocalizationTest {
    @Test
    fun `registry exports deterministic localized application commands`() {
        val registry = CommandRegistry().apply {
            register(AirportApplicationCog())
            register(FlightApplicationCog())
            register(ContextMenuLocalizationCog())
            register(GuildOnlyContextMenuCog())
        }

        val provider = CommandLocalizationProvider { request ->
            when (request.field) {
                CommandLocalizationField.COMMAND_DESCRIPTION -> if (
                    request.commandType == JdaCommand.Type.SLASH && request.commandName == "airport"
                ) {
                    mapOf(DiscordLocale.FRENCH to "recherche aeroport")
                } else {
                    emptyMap()
                }

                CommandLocalizationField.OPTION_NAME -> if (
                    request.commandType == JdaCommand.Type.SLASH &&
                    request.commandName == "airport" &&
                    request.optionName == "departure_code"
                ) {
                    mapOf(DiscordLocale.FRENCH to "code_depart")
                } else {
                    emptyMap()
                }

                CommandLocalizationField.CHOICE_NAME -> when {
                    request.commandType == JdaCommand.Type.SLASH &&
                        request.commandName == "airport" &&
                        request.optionName == "departure_code" &&
                        request.choiceValue == "LHR" -> {
                        mapOf(DiscordLocale.FRENCH to "heathrow_fr")
                    }

                    request.commandType == JdaCommand.Type.SLASH &&
                        request.commandName == "airport" &&
                        request.optionName == "cabin_class" &&
                        request.choiceValue == "BUSINESS" -> {
                        mapOf(DiscordLocale.FRENCH to "affaires")
                    }

                    else -> emptyMap()
                }

                CommandLocalizationField.SUBCOMMAND_NAME -> if (
                    request.commandType == JdaCommand.Type.SLASH &&
                    request.commandName == "flight" &&
                    request.subcommandName == "create"
                ) {
                    mapOf(DiscordLocale.FRENCH to "creer")
                } else {
                    emptyMap()
                }

                CommandLocalizationField.COMMAND_NAME -> when {
                    request.commandType == JdaCommand.Type.USER && request.commandName == "Inspect User" -> {
                        mapOf(DiscordLocale.FRENCH to "Inspecter utilisateur")
                    }

                    request.commandType == JdaCommand.Type.MESSAGE && request.commandName == "Inspect Message" -> {
                        mapOf(DiscordLocale.FRENCH to "Inspecter message")
                    }

                    else -> emptyMap()
                }

                CommandLocalizationField.SUBCOMMAND_GROUP_NAME,
                CommandLocalizationField.SUBCOMMAND_GROUP_DESCRIPTION,
                CommandLocalizationField.SUBCOMMAND_DESCRIPTION,
                CommandLocalizationField.OPTION_DESCRIPTION -> emptyMap()
            }
        }

        val commands = registry.toDiscordCommands(localizationProvider = provider)

        assertContentEquals(
            listOf("airport", "flight", "Inspect Message", "Inspect User"),
            commands.map { it.name }
        )
        assertFalse(commands.any { it.name == "Guild Only Review" })

        val airport = commands.first { it.name == "airport" } as SlashCommandData
        assertEquals("recherche aeroport", airport.descriptionLocalizations.get(DiscordLocale.FRENCH))

        val departureCode = airport.options.first { it.name == "departure_code" }
        assertEquals("code_depart", departureCode.nameLocalizations.get(DiscordLocale.FRENCH))
        assertEquals(
            "heathrow_fr",
            departureCode.choices.first { it.asString == "LHR" }.nameLocalizations.get(DiscordLocale.FRENCH)
        )

        val cabinClass = airport.options.first { it.name == "cabin_class" }
        assertEquals(
            "affaires",
            cabinClass.choices.first { it.asString == "BUSINESS" }.nameLocalizations.get(DiscordLocale.FRENCH)
        )

        val flight = commands.first { it.name == "flight" } as SlashCommandData
        assertContentEquals(listOf("cancel", "create"), flight.subcommands.map { it.name })
        assertEquals("creer", flight.subcommands.first { it.name == "create" }.nameLocalizations.get(DiscordLocale.FRENCH))

        val userCommand = commands.first { it.type == JdaCommand.Type.USER }
        assertEquals("Inspecter utilisateur", userCommand.nameLocalizations.get(DiscordLocale.FRENCH))

        val messageCommand = commands.first { it.type == JdaCommand.Type.MESSAGE }
        assertEquals("Inspecter message", messageCommand.nameLocalizations.get(DiscordLocale.FRENCH))
    }

    @Test
    fun `registry exposes explicit deployment targets for guild scoped commands`() {
        val registry = CommandRegistry().apply {
            register(SyncPlanningCog())
        }

        val provider = CommandLocalizationProvider { request ->
            when {
                request.field == CommandLocalizationField.COMMAND_NAME &&
                    request.commandType == JdaCommand.Type.MESSAGE &&
                    request.commandName == "Review Message" -> {
                    mapOf(DiscordLocale.FRENCH to "Examiner message")
                }

                else -> emptyMap()
            }
        }

        val targets = registry.toDiscordCommandTargets(localizationProvider = provider)

        assertContentEquals(
            listOf(
                CommandSyncScope.Global,
                CommandSyncScope.Guild(1L),
                CommandSyncScope.Guild(2L)
            ),
            targets.map { it.scope }
        )

        val global = targets.first { it.scope == CommandSyncScope.Global }
        assertContentEquals(listOf("globalroute", "Inspect User"), global.commands.map { it.name })

        val guildOne = targets.first { it.scope == CommandSyncScope.Guild(1L) }
        assertContentEquals(listOf("Review Message"), guildOne.commands.map { it.name })
        assertEquals("Examiner message", guildOne.commands.single().nameLocalizations.get(DiscordLocale.FRENCH))

        val guildTwo = targets.first { it.scope == CommandSyncScope.Guild(2L) }
        assertContentEquals(listOf("Review Message"), guildTwo.commands.map { it.name })
    }

    @Test
    fun `registry supports same command name across application command types`() {
        val registry = CommandRegistry().apply {
            register(DuplicateNameApplicationCog())
        }

        assertNotNull(registry.findSlashCommand("ping"))
        assertNotNull(registry.findUserCommand("Ping"))
        assertNotNull(registry.findMessageContextCommand("Ping"))
        assertEquals(3, registry.toDiscordCommands().count { it.name.equals("ping", ignoreCase = true) })
    }

    @Test
    fun `permission bearing application commands export effective guild contexts and default member permissions`() {
        val registry = CommandRegistry().apply {
            register(PermissionExportCog())
        }

        val commands = registry.toDiscordCommands()
        val slash = commands.first { it.name == "moderate" }
        val userCommand = commands.first { it.name == "Inspect Permitted User" }

        assertEquals(
            permissionsRaw(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE)),
            permissionsRaw(defaultPermissionsOf(slash))
        )
        assertContentEquals(listOf(InteractionContextType.GUILD), contextsOf(slash).toList())
        assertContentEquals(listOf(IntegrationType.GUILD_INSTALL), integrationTypesOf(slash).toList())

        assertContentEquals(listOf(InteractionContextType.GUILD), contextsOf(userCommand).toList())
        assertContentEquals(listOf(IntegrationType.GUILD_INSTALL), integrationTypesOf(userCommand).toList())
    }

    @Test
    fun `indexer loads typed target injection metadata for context menu commands`() {
        val registry = CommandRegistry().apply {
            register(TargetInjectionCog())
        }

        val userCommand = registry.findUserCommand("Who Is")!!
        assertTrue(userCommand.arguments.isEmpty())
        assertNotNull(userCommand.selectedTargetParameter)
        assertContentEquals(listOf(ContextType.USER_COMMAND), userCommand.supportedContextTypes.toList())

        val messageCommand = registry.findMessageContextCommand("Quote")!!
        assertTrue(messageCommand.arguments.isEmpty())
        assertNotNull(messageCommand.selectedTargetParameter)
        assertContentEquals(listOf(ContextType.MESSAGE_COMMAND), messageCommand.supportedContextTypes.toList())

        val userNoTarget = registry.findUserCommand("Who Is Simple")!!
        assertNull(userNoTarget.selectedTargetParameter)
    }

    @Test
    fun `planCommandSync includes context menus and skips non application commands`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .build()

        client.commands.register(SyncPlanningCog())

        val plan = client.planCommandSync(CommandSyncOptions(dryRun = true))

        assertTrue(plan.dryRun)
        assertContentEquals(
            listOf(
                CommandSyncScope.Global,
                CommandSyncScope.Guild(1L),
                CommandSyncScope.Guild(2L)
            ),
            plan.targets.map { it.scope }
        )

        val global = plan.targets.first { it.scope == CommandSyncScope.Global }
        assertContentEquals(listOf("globalroute", "Inspect User"), global.emitted.map { it.name })
        assertContentEquals(
            listOf(JdaCommand.Type.SLASH, JdaCommand.Type.USER),
            global.emitted.map { it.commandType }
        )

        val guildOne = plan.targets.first { it.scope == CommandSyncScope.Guild(1L) }
        assertContentEquals(listOf("Review Message"), guildOne.emitted.map { it.name })
        assertContentEquals(listOf(CommandSyncSkipReason.NOT_APPLICATION_COMMAND), guildOne.skipped.map { it.reason })
        assertEquals("guildmessage", guildOne.skipped.single().command.name)

        val guildTwo = plan.targets.first { it.scope == CommandSyncScope.Guild(2L) }
        assertContentEquals(listOf("Review Message"), guildTwo.emitted.map { it.name })
        assertContentEquals(listOf(CommandSyncSkipReason.NOT_APPLICATION_COMMAND), guildTwo.skipped.map { it.reason })
    }

    @Test
    fun `planCommandSync keeps filtered guild targets explicit when guildIds overlay is applied`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .build()

        client.commands.register(SyncPlanningCog())

        val plan = client.planCommandSync(CommandSyncOptions(dryRun = true, guildIds = setOf(1L)))

        assertContentEquals(
            listOf(
                CommandSyncScope.Global,
                CommandSyncScope.Guild(1L),
                CommandSyncScope.Guild(2L)
            ),
            plan.targets.map { it.scope }
        )

        val guildOne = plan.targets.first { it.scope == CommandSyncScope.Guild(1L) }
        assertContentEquals(listOf("Review Message"), guildOne.emitted.map { it.name })

        val guildTwo = plan.targets.first { it.scope == CommandSyncScope.Guild(2L) }
        assertTrue(guildTwo.emitted.isEmpty())
        assertContentEquals(
            listOf(CommandSyncSkipReason.FILTERED_OUT, CommandSyncSkipReason.FILTERED_OUT),
            guildTwo.skipped.map { it.reason }
        )
    }

    @Test
    fun `planCommandSync does not create natural guild targets for non application commands only`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .build()

        client.commands.register(MessageOnlyGuildSyncCog())

        val plan = client.planCommandSync(CommandSyncOptions(dryRun = true, includeGlobal = false))

        assertTrue(plan.targets.none { it.scope == CommandSyncScope.Guild(55L) })
    }

    @Test
    fun `command sync executor continues after one target fails`() {
        val alpha = CommandSyncCommand("alpha", JdaCommand.Type.SLASH, "Alpha command")
        val beta = CommandSyncCommand("beta", JdaCommand.Type.SLASH, "Beta command")
        val gamma = CommandSyncCommand("gamma", JdaCommand.Type.USER, null)

        val plan = PlannedCommandSync(
            dryRun = false,
            targets = listOf(
                PlannedCommandSyncTarget(
                    scope = CommandSyncScope.Global,
                    payload = listOf(Commands.slash("alpha", "Alpha command")),
                    considered = listOf(alpha),
                    emitted = listOf(alpha),
                    skipped = emptyList(),
                    failed = emptyList()
                ),
                PlannedCommandSyncTarget(
                    scope = CommandSyncScope.Guild(1L),
                    payload = listOf(Commands.slash("beta", "Beta command")),
                    considered = listOf(beta),
                    emitted = listOf(beta),
                    skipped = emptyList(),
                    failed = emptyList()
                ),
                PlannedCommandSyncTarget(
                    scope = CommandSyncScope.Guild(2L),
                    payload = listOf(Commands.user("gamma")),
                    considered = listOf(gamma),
                    emitted = listOf(gamma),
                    skipped = emptyList(),
                    failed = emptyList()
                )
            )
        )

        val backend = object : CommandSyncBackend {
            override fun updateGlobal(commands: List<net.dv8tion.jda.api.interactions.commands.build.CommandData>): CompletableFuture<*> {
                return CompletableFuture.completedFuture(Unit)
            }

            override fun updateGuild(guildId: Long, commands: List<net.dv8tion.jda.api.interactions.commands.build.CommandData>): CompletableFuture<*> {
                return if (guildId == 1L) {
                    CompletableFuture.failedFuture<Any>(RuntimeException("boom"))
                } else {
                    CompletableFuture.completedFuture(Unit)
                }
            }
        }

        val result = CommandSyncExecutor(backend).execute(plan).join()

        assertContentEquals(
            listOf(
                CommandSyncTargetState.SYNCED,
                CommandSyncTargetState.FAILED,
                CommandSyncTargetState.SYNCED
            ),
            result.targets.map { it.state }
        )
        assertEquals("boom", result.targets[1].executionError?.message)
    }

    @Test
    fun `builder stores localization provider and runtime checks apply to context menu commands`() {
        val recordingAdapter = RecordingAdapter()
        val provider = CommandLocalizationProvider { emptyMap() }
        val client = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .setCommandLocalizationProvider(provider)
            .addEventListeners(recordingAdapter)
            .build()

        assertSame(provider, client.commandLocalizationProvider)

        client.commands.register(RestrictedUserCommandCog())
        val command = client.commands.findUserCommand("Restricted User")!!

        val wrongContext = FakeContext(
            commandClient = client,
            invokedCommand = command,
            contextType = ContextType.MESSAGE,
            authorId = 42L,
            guildId = 123L
        )
        assertFalse(shouldExecute(client, wrongContext, command))
        assertEquals(CheckType.EXECUTION_CONTEXT, recordingAdapter.lastFailedCheck)

        val wrongGuild = FakeContext(
            commandClient = client,
            invokedCommand = command,
            contextType = ContextType.USER_COMMAND,
            authorId = 42L,
            guildId = 999L
        )
        assertFalse(shouldExecute(client, wrongGuild, command))
        assertEquals(CheckType.GUILD_ID_CHECK, recordingAdapter.lastFailedCheck)

        recordingAdapter.lastFailedCheck = null

        val guildless = FakeContext(
            commandClient = client,
            invokedCommand = command,
            contextType = ContextType.USER_COMMAND,
            authorId = 42L,
            guildId = null
        )
        assertFalse(shouldExecute(client, guildless, command))
        assertEquals(CheckType.GUILD_ID_CHECK, recordingAdapter.lastFailedCheck)

        recordingAdapter.lastFailedCheck = null

        val allowedGuild = FakeContext(
            commandClient = client,
            invokedCommand = command,
            contextType = ContextType.USER_COMMAND,
            authorId = 42L,
            guildId = 123L
        )
        assertTrue(shouldExecute(client, allowedGuild, command))
        assertNull(recordingAdapter.lastFailedCheck)
    }

    @Test
    fun `permission bearing commands are rejected outside guilds via guild check`() {
        val recordingAdapter = RecordingAdapter()
        val client = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .addEventListeners(recordingAdapter)
            .build()

        client.commands.register(PermissionRestrictedUserCommandCog())
        val command = client.commands.findUserCommand("Permitted User")!!

        val guildless = FakeContext(
            commandClient = client,
            invokedCommand = command,
            contextType = ContextType.USER_COMMAND,
            authorId = 42L,
            guildId = null
        )

        assertFalse(shouldExecute(client, guildless, command))
        assertEquals(CheckType.GUILD_CHECK, recordingAdapter.lastFailedCheck)
    }

    @Test
    fun `registry rejects invalid guildIds declarations early`() {
        val empty = assertFailsWith<IllegalStateException> {
            CommandRegistry().register(EmptyGuildIdsCog())
        }
        assertTrue(empty.message!!.contains("Failed to register cog"))
        assertTrue(empty.cause!!.message!!.contains("must declare at least one guild id"))

        val nonPositive = assertFailsWith<IllegalStateException> {
            CommandRegistry().register(NonPositiveGuildIdsCog())
        }
        assertTrue(nonPositive.message!!.contains("Failed to register cog"))
        assertTrue(nonPositive.cause!!.message!!.contains("may only contain positive guild ids"))

        val subcommand = assertFailsWith<IllegalStateException> {
            CommandRegistry().register(SubcommandGuildIdsCog())
        }
        assertTrue(subcommand.message!!.contains("Failed to register cog"))
        assertTrue(subcommand.cause!!.message!!.contains("only supported on top-level"))
    }

    @Test
    fun `command client dispatches user context interactions with selected target injection`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .build()
        val cog = DispatchRecordingCog()
        client.commands.register(cog)

        val targetUser = userProxy(777L)

        client.onEvent(userContextEvent("Inspect User", targetUser))
        assertTrue(cog.userInvocationLatch.await(3, TimeUnit.SECONDS))

        assertEquals(1, cog.userInvocationCount)
        assertSame(targetUser, cog.observedUserTarget)
        assertSame(targetUser, cog.observedUserContext?.target)
        assertEquals(ContextType.USER_COMMAND, cog.observedUserContext?.contextType)

        client.shutdown()
    }

    @Test
    fun `command client dispatches message context interactions with selected target injection`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .build()
        val cog = DispatchRecordingCog()
        client.commands.register(cog)

        val targetMessage = messageProxy(888L)

        client.onEvent(messageContextEvent("Inspect Message", targetMessage))
        assertTrue(cog.messageInvocationLatch.await(3, TimeUnit.SECONDS))

        assertEquals(1, cog.messageInvocationCount)
        assertSame(targetMessage, cog.observedMessageTarget)
        assertSame(targetMessage, cog.observedMessageContext?.target)
        assertEquals(ContextType.MESSAGE_COMMAND, cog.observedMessageContext?.contextType)

        client.shutdown()
    }

    @Test
    fun `default help excludes context menu only commands from message help output`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .build()
        client.commands.register(HelpFilteringCog())

        val invoked = client.commands.findMessageCommand("ping")!!
        val ctx = messageContext(client, invoked)
        val pages = DefaultHelpCommand(showParameterTypes = false).buildCommandList(ctx)
        val output = pages.joinToString("\n")

        assertTrue(output.contains("ping"))
        assertFalse(output.contains("Inspect User"))
        assertFalse(output.contains("Inspect Message"))
    }
}

private enum class CabinClass {
    ECONOMY,
    BUSINESS
}

private class AirportApplicationCog : Cog {
    @Command(description = "Airport lookup")
    fun airport(
        ctx: SlashContext,
        @Name("departureCode")
        @Describe("Departure airport")
        @Choices(string = [StringChoice("Heathrow", "LHR")])
        departureCode: String,
        @Describe("Cabin class")
        cabinClass: CabinClass
    ) = Unit
}

private class FlightApplicationCog : Cog {
    @Command(description = "Flight operations")
    fun flight(ctx: SlashContext) = Unit

    @SubCommand(description = "Create flight")
    fun create(ctx: SlashContext, @Describe("Aircraft type") aircraftType: String) = Unit

    @SubCommand(description = "Cancel flight")
    fun cancel(ctx: SlashContext, @Describe("Booking id") bookingId: String) = Unit
}

private class ContextMenuLocalizationCog : Cog {
    @UserCommand(name = "Inspect User")
    fun inspectUser(ctx: UserCommandContext) = Unit

    @MessageCommand(name = "Inspect Message")
    fun inspectMessage(ctx: MessageCommandContext) = Unit
}

private class GuildOnlyContextMenuCog : Cog {
    @GuildIds([1L])
    @MessageCommand(name = "Guild Only Review")
    fun guildReview(ctx: MessageCommandContext) = Unit
}

private class DuplicateNameApplicationCog : Cog {
    @Command(description = "Slash ping")
    fun ping(ctx: SlashContext) = Unit

    @UserCommand(name = "Ping")
    fun pingUser(ctx: UserCommandContext) = Unit

    @MessageCommand(name = "Ping")
    fun pingMessage(ctx: MessageCommandContext) = Unit
}

private class TargetInjectionCog : Cog {
    @UserCommand(name = "Who Is")
    fun whoIs(ctx: UserCommandContext, target: User) = Unit

    @UserCommand(name = "Who Is Simple")
    fun whoIsSimple(ctx: UserCommandContext) = Unit

    @MessageCommand(name = "Quote")
    fun quote(ctx: MessageCommandContext, target: Message) = Unit
}

private class SyncPlanningCog : Cog {
    @Command(description = "Global route")
    fun globalroute(ctx: SlashContext) = Unit

    @UserCommand(name = "Inspect User")
    fun inspectUser(ctx: UserCommandContext) = Unit

    @GuildIds([2L, 1L])
    @MessageCommand(name = "Review Message")
    fun review(ctx: MessageCommandContext, target: Message) = Unit

    @GuildIds([2L, 1L])
    @Command(description = "Guild message")
    fun guildmessage(ctx: MessageContext) = Unit
}

private class MessageOnlyGuildSyncCog : Cog {
    @GuildIds([55L])
    @Command(description = "Message only")
    fun localMessage(ctx: MessageContext) = Unit
}

private class RestrictedUserCommandCog : Cog {
    @GuildIds([123L])
    @UserCommand(name = "Restricted User")
    fun restricted(ctx: UserCommandContext) = Unit
}

private class PermissionExportCog : Cog {
    @Command(description = "Moderate", userPermissions = [Permission.MESSAGE_MANAGE])
    fun moderate(ctx: SlashContext) = Unit

    @UserCommand(name = "Inspect Permitted User", botPermissions = [Permission.KICK_MEMBERS])
    fun inspect(ctx: UserCommandContext) = Unit
}

private class PermissionRestrictedUserCommandCog : Cog {
    @UserCommand(name = "Permitted User", userPermissions = [Permission.BAN_MEMBERS])
    fun permitted(ctx: UserCommandContext) = Unit
}

private class EmptyGuildIdsCog : Cog {
    @GuildIds([])
    @Command(description = "Empty guild ids")
    fun empty(ctx: SlashContext) = Unit
}

private class NonPositiveGuildIdsCog : Cog {
    @GuildIds([0L])
    @UserCommand(name = "Invalid User")
    fun invalid(ctx: UserCommandContext) = Unit
}

private class SubcommandGuildIdsCog : Cog {
    @Command(description = "Parent command")
    fun parent(ctx: SlashContext) = Unit

    @GuildIds([1L])
    @SubCommand(parent = "parent", description = "Child command")
    fun child(ctx: SlashContext) = Unit
}

class DispatchRecordingCog : Cog {
    val userInvocationLatch: CountDownLatch = CountDownLatch(1)
    val messageInvocationLatch: CountDownLatch = CountDownLatch(1)
    var userInvocationCount = 0
    var messageInvocationCount = 0
    var observedUserContext: UserCommandContext? = null
    var observedMessageContext: MessageCommandContext? = null
    var observedUserTarget: User? = null
    var observedMessageTarget: Message? = null

    @UserCommand(name = "Inspect User")
    fun inspectUser(ctx: UserCommandContext, target: User) {
        userInvocationCount += 1
        observedUserContext = ctx
        observedUserTarget = target
        userInvocationLatch.countDown()
    }

    @MessageCommand(name = "Inspect Message")
    fun inspectMessage(ctx: MessageCommandContext, target: Message) {
        messageInvocationCount += 1
        observedMessageContext = ctx
        observedMessageTarget = target
        messageInvocationLatch.countDown()
    }
}

private class HelpFilteringCog : Cog {
    @Command(description = "Ping command")
    fun ping(ctx: MessageContext) = Unit

    @UserCommand(name = "Inspect User")
    fun inspectUser(ctx: UserCommandContext) = Unit

    @MessageCommand(name = "Inspect Message")
    fun inspectMessage(ctx: MessageCommandContext) = Unit
}

private class RecordingAdapter : DefaultCommandEventAdapter() {
    var lastFailedCheck: CheckType? = null

    override fun onCheckFailed(ctx: Context, command: CommandFunction, checkType: CheckType) {
        lastFailedCheck = checkType
    }
}

private class FakeContext(
    override val commandClient: CommandClient,
    override val invokedCommand: Executable,
    override val contextType: ContextType,
    authorId: Long,
    guildId: Long?
) : Context {
    override val jda: JDA = proxy()
    override val author: User = userProxy(authorId)
    override val guild: Guild? = guildId?.let(::guildProxy)
    override val member: Member? = null
    override val messageChannel: MessageChannel = proxy()
    override val guildChannel: GuildMessageChannel? = null
    override val isFromGuild: Boolean = guild != null
}

private fun shouldExecute(client: CommandClient, context: Context, command: CommandFunction): Boolean {
    return CommandClient::class.java
        .getDeclaredMethod("shouldExecuteCommand", Context::class.java, CommandFunction::class.java)
        .apply { isAccessible = true }
        .invoke(client, context, command) as Boolean
}

private fun defaultPermissionsOf(command: net.dv8tion.jda.api.interactions.commands.build.CommandData): DefaultMemberPermissions? {
    return command.javaClass.getMethod("getDefaultPermissions").invoke(command) as? DefaultMemberPermissions
}

private fun permissionsRaw(permissions: DefaultMemberPermissions?): Long? {
    return permissions
        ?.javaClass
        ?.getMethod("getPermissionsRaw")
        ?.invoke(permissions) as? Long
}

@Suppress("UNCHECKED_CAST")
private fun contextsOf(command: net.dv8tion.jda.api.interactions.commands.build.CommandData): Set<InteractionContextType> {
    return command.javaClass.getMethod("getContexts").invoke(command) as Set<InteractionContextType>
}

@Suppress("UNCHECKED_CAST")
private fun integrationTypesOf(command: net.dv8tion.jda.api.interactions.commands.build.CommandData): Set<IntegrationType> {
    return command.javaClass.getMethod("getIntegrationTypes").invoke(command) as Set<IntegrationType>
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

private fun userProxy(id: Long): User {
    return proxy { method ->
        when (method.name) {
            "getIdLong" -> id
            "getId" -> id.toString()
            else -> defaultValue(method.returnType)
        }
    }
}

private fun messageProxy(id: Long): Message {
    return proxy { method ->
        when (method.name) {
            "getIdLong" -> id
            "getId" -> id.toString()
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

private fun userContextEvent(
    name: String,
    target: User,
    invokingUser: User = userProxy(42L)
): UserContextInteractionEvent {
    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val interaction = proxyWithArgs<UserContextInteraction> { method, args ->
        when (method.name) {
            "getName" -> name
            "getUser" -> invokingUser
            "getTarget" -> target
            "getTargetMember" -> null
            "getChannel" -> channel
            "getGuild" -> null
            "getMember" -> null
            "isFromGuild" -> false
            "isAcknowledged" -> false
            "getJDA" -> jda
            "getCommandType" -> JdaCommand.Type.USER
            else -> defaultValue(method.returnType)
        }
    }

    return UserContextInteractionEvent(jda, 0L, interaction)
}

private fun messageContextEvent(
    name: String,
    target: Message,
    invokingUser: User = userProxy(42L)
): MessageContextInteractionEvent {
    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val interaction = proxyWithArgs<MessageContextInteraction> { method, args ->
        when (method.name) {
            "getName" -> name
            "getUser" -> invokingUser
            "getTarget" -> target
            "getChannel" -> channel
            "getGuild" -> null
            "getMember" -> null
            "isFromGuild" -> false
            "isAcknowledged" -> false
            "getJDA" -> jda
            "getCommandType" -> JdaCommand.Type.MESSAGE
            else -> defaultValue(method.returnType)
        }
    }

    return MessageContextInteractionEvent(jda, 0L, interaction)
}

private fun interactionChannel(): MessageChannelUnion {
    return proxyWithArgs<MessageChannelUnion>(MessageChannel::class.java) { method, _ ->
        when (method.name) {
            "getType", "getChannelType" -> ChannelType.PRIVATE
            else -> defaultValue(method.returnType)
        }
    }
}

private fun messageContext(client: CommandClient, invokedCommand: Executable): MessageContext {
    val jda = proxy<JDA>()
    val channel = interactionChannel()
    val message = proxy<Message> { method ->
        when (method.name) {
            "getJDA" -> jda
            "getAuthor" -> userProxy(99L)
            "getChannel" -> channel
            "getMember" -> null
            "isFromGuild" -> false
            "getChannelType" -> ChannelType.PRIVATE
            else -> defaultValue(method.returnType)
        }
    }

    return MessageContext(
        commandClient = client,
        event = MessageReceivedEvent(jda, 0L, message),
        trigger = "!",
        invokedCommand = invokedCommand
    )
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
