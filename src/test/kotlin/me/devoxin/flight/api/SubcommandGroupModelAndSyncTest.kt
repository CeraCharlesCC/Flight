package me.devoxin.flight.api

import me.devoxin.flight.api.annotations.Choices
import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.annotations.Describe
import me.devoxin.flight.api.annotations.SubCommand
import me.devoxin.flight.api.annotations.SubCommandGroup
import me.devoxin.flight.api.annotations.choice.StringChoice
import me.devoxin.flight.api.command.Cog
import me.devoxin.flight.api.command.CommandRegistry
import me.devoxin.flight.api.context.ContextType
import me.devoxin.flight.api.context.MessageContext
import me.devoxin.flight.api.context.SlashContext
import me.devoxin.flight.api.localization.CommandLocalizationField
import me.devoxin.flight.api.localization.CommandLocalizationProvider
import me.devoxin.flight.api.sync.CommandSyncOptions
import me.devoxin.flight.api.sync.CommandSyncScope
import me.devoxin.flight.api.sync.CommandSyncSubcommandGroup
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SubcommandGroupModelAndSyncTest {
    @Test
    fun `registry loads flat and grouped subcommands into a slash tree`() {
        val registry = CommandRegistry().apply {
            register(MixedSubcommandGroupCog())
        }

        val flight = registry.findSlashCommand("flight")!!

        assertContentEquals(listOf("cancel"), flight.directSubcommands.map { it.name })
        assertContentEquals(listOf("booking", "crew"), flight.subcommandGroups.map { it.name })
        assertContentEquals(
            listOf("create", "update"),
            flight.subcommandGroups.first { it.name == "booking" }.subcommands.map { it.name }
        )
        assertContentEquals(
            listOf("cancel", "create", "update", "create"),
            flight.allSubcommands.map { it.name }
        )

        assertNotNull(flight.findSubcommand("cancel", ContextType.SLASH))
        assertNotNull(flight.findSubcommand("booking", "create", ContextType.SLASH))
        assertNotNull(flight.findSubcommand("crew", "create", ContextType.SLASH))
        assertNull(flight.findSubcommand("create", ContextType.SLASH))
    }

    @Test
    fun `registry rejects grouped subcommands that reference undeclared groups`() {
        assertFailsWith<IllegalStateException> {
            CommandRegistry().register(MissingGroupDeclarationCog())
        }
    }

    @Test
    fun `registry rejects blank parent resolution when multiple slash owners exist`() {
        assertFailsWith<IllegalStateException> {
            CommandRegistry().register(AmbiguousGroupedParentCog())
        }
    }

    @Test
    fun `registry rejects subcommands attached to non slash parents`() {
        assertFailsWith<IllegalStateException> {
            CommandRegistry().register(NonSlashParentCog())
        }
    }

    @Test
    fun `registry rejects slash commands that mix root options with grouped children`() {
        assertFailsWith<IllegalStateException> {
            CommandRegistry().register(MixedRootOptionsAndGroupsCog())
        }
    }

    @Test
    fun `registry rejects direct subcommand names that collide with group names`() {
        assertFailsWith<IllegalStateException> {
            CommandRegistry().register(DirectSubcommandGroupNameCollisionCog())
        }
    }

    @Test
    fun `registry rejects direct subcommand aliases that collide with group names`() {
        assertFailsWith<IllegalStateException> {
            CommandRegistry().register(DirectAliasGroupNameCollisionCog())
        }
    }

    @Test
    fun `registry rejects invalid subcommand group placement on non slash commands`() {
        val error = assertFailsWith<IllegalStateException> {
            CommandRegistry().register(InvalidSubcommandGroupPlacementCog())
        }

        assertEquals(true, error.message!!.contains("Failed to register cog"))
        assertEquals(true, error.cause!!.message!!.contains("slash-capable @Command handlers"))
    }

    @Test
    fun `registry rejects empty declared subcommand groups`() {
        assertFailsWith<IllegalStateException> {
            CommandRegistry().register(EmptyDeclaredSubcommandGroupCog())
        }
    }

    @Test
    fun `registry exports mixed grouped slash trees with group-aware localization`() {
        val registry = CommandRegistry().apply {
            register(MixedSubcommandGroupCog())
        }

        val provider = CommandLocalizationProvider { request ->
            when (request.field) {
                CommandLocalizationField.SUBCOMMAND_GROUP_NAME -> when (request.subcommandGroupName) {
                    "booking" -> mapOf(DiscordLocale.FRENCH to "reservation")
                    else -> emptyMap()
                }

                CommandLocalizationField.SUBCOMMAND_GROUP_DESCRIPTION -> when (request.subcommandGroupName) {
                    "booking" -> mapOf(DiscordLocale.FRENCH to "operations reservation")
                    else -> emptyMap()
                }

                CommandLocalizationField.SUBCOMMAND_NAME -> when {
                    request.subcommandGroupName == "booking" && request.subcommandName == "create" -> {
                        mapOf(DiscordLocale.FRENCH to "creer_reservation")
                    }

                    request.subcommandGroupName == "crew" && request.subcommandName == "create" -> {
                        mapOf(DiscordLocale.FRENCH to "creer_equipage")
                    }

                    else -> emptyMap()
                }

                CommandLocalizationField.OPTION_NAME -> when {
                    request.subcommandGroupName == "crew" &&
                        request.subcommandName == "create" &&
                        request.optionName == "mode" -> {
                        mapOf(DiscordLocale.FRENCH to "mode_equipage")
                    }

                    else -> emptyMap()
                }

                CommandLocalizationField.CHOICE_NAME -> when {
                    request.subcommandGroupName == "crew" &&
                        request.subcommandName == "create" &&
                        request.optionName == "mode" &&
                        request.choiceValue == "STD" -> {
                        mapOf(DiscordLocale.FRENCH to "standard_equipage")
                    }

                    else -> emptyMap()
                }

                CommandLocalizationField.COMMAND_NAME,
                CommandLocalizationField.COMMAND_DESCRIPTION,
                CommandLocalizationField.SUBCOMMAND_DESCRIPTION,
                CommandLocalizationField.OPTION_DESCRIPTION -> emptyMap()
            }
        }

        val commands = registry.toDiscordCommands(localizationProvider = provider)
        val flight = commands.single { it.name == "flight" } as SlashCommandData

        assertContentEquals(listOf("cancel"), flight.subcommands.map { it.name })
        assertContentEquals(listOf("booking", "crew"), flight.subcommandGroups.map { it.name })

        val bookingGroup = flight.subcommandGroups.first { it.name == "booking" }
        assertEquals("reservation", bookingGroup.nameLocalizations.get(DiscordLocale.FRENCH))
        assertEquals("operations reservation", bookingGroup.descriptionLocalizations.get(DiscordLocale.FRENCH))
        assertContentEquals(listOf("create", "update"), bookingGroup.subcommands.map { it.name })
        assertEquals(
            "creer_reservation",
            bookingGroup.subcommands.first { it.name == "create" }.nameLocalizations.get(DiscordLocale.FRENCH)
        )

        val crewGroup = flight.subcommandGroups.first { it.name == "crew" }
        val crewMode = crewGroup.subcommands.first { it.name == "create" }.options.first { it.name == "mode" }
        assertEquals("mode_equipage", crewMode.nameLocalizations.get(DiscordLocale.FRENCH))
        assertEquals(
            "standard_equipage",
            crewMode.choices.first { it.asString == "STD" }.nameLocalizations.get(DiscordLocale.FRENCH)
        )
    }

    @Test
    fun `sync planning exposes direct and grouped subcommand summaries`() {
        val client = CommandClient.builder()
            .setPrefixes("!")
            .configureDefaultHelpCommand { enabled = false }
            .build()

        client.commands.register(MixedSubcommandGroupCog())

        val plan = client.planCommandSync(CommandSyncOptions(dryRun = true))
        val global = plan.targets.single { it.scope == CommandSyncScope.Global }
        val flight = global.emitted.single { it.name == "flight" }

        assertContentEquals(listOf("cancel"), flight.directSubcommands)
        assertContentEquals(
            listOf(
                CommandSyncSubcommandGroup("booking", listOf("create", "update")),
                CommandSyncSubcommandGroup("crew", listOf("create"))
            ),
            flight.subcommandGroups
        )
    }
}

private class MixedSubcommandGroupCog : Cog {
    @Command(description = "Flight operations")
    @SubCommandGroup(name = "crew", description = "Crew operations")
    @SubCommandGroup(name = "booking", description = "Booking operations")
    fun flight(ctx: SlashContext) = Unit

    @SubCommand(parent = "flight", description = "Cancel flight")
    fun cancel(ctx: SlashContext) = Unit

    @SubCommand(parent = "flight", group = "booking", name = "update", description = "Update booking")
    fun updateBooking(ctx: SlashContext) = Unit

    @SubCommand(parent = "flight", group = "booking", name = "create", description = "Create booking")
    fun createBooking(
        ctx: SlashContext,
        @Describe("Booking mode")
        @Choices(string = [StringChoice("Standard", "STD")])
        mode: String
    ) = Unit

    @SubCommand(parent = "flight", group = "crew", name = "create", description = "Create crew")
    fun createCrew(
        ctx: SlashContext,
        @Describe("Crew mode")
        @Choices(string = [StringChoice("Standard", "STD")])
        mode: String
    ) = Unit
}

private class MissingGroupDeclarationCog : Cog {
    @Command(description = "Flight operations")
    fun flight(ctx: SlashContext) = Unit

    @SubCommand(parent = "flight", group = "booking", description = "Create booking")
    fun create(ctx: SlashContext) = Unit
}

private class AmbiguousGroupedParentCog : Cog {
    @Command(description = "Flight operations")
    fun flight(ctx: SlashContext) = Unit

    @Command(description = "Crew operations")
    fun crew(ctx: SlashContext) = Unit

    @SubCommand(group = "booking", description = "Create booking")
    fun create(ctx: SlashContext) = Unit
}

private class NonSlashParentCog : Cog {
    @Command(description = "Message only flight operations")
    fun flight(ctx: MessageContext) = Unit

    @SubCommand(parent = "flight", description = "Create booking")
    fun create(ctx: SlashContext) = Unit
}

private class MixedRootOptionsAndGroupsCog : Cog {
    @Command(description = "Flight operations")
    @SubCommandGroup(name = "booking", description = "Booking operations")
    fun flight(ctx: SlashContext, @Describe("Route code") route: String) = Unit

    @SubCommand(parent = "flight", group = "booking", description = "Create booking")
    fun create(ctx: SlashContext) = Unit
}

private class DirectSubcommandGroupNameCollisionCog : Cog {
    @Command(description = "Flight operations")
    @SubCommandGroup(name = "booking", description = "Booking operations")
    fun flight(ctx: SlashContext) = Unit

    @SubCommand(parent = "flight", name = "booking", description = "Conflicting direct subcommand")
    fun booking(ctx: SlashContext) = Unit
}

private class DirectAliasGroupNameCollisionCog : Cog {
    @Command(description = "Flight operations")
    @SubCommandGroup(name = "booking", description = "Booking operations")
    fun flight(ctx: SlashContext) = Unit

    @SubCommand(parent = "flight", aliases = ["booking"], description = "Alias collision")
    fun cancel(ctx: SlashContext) = Unit
}

private class InvalidSubcommandGroupPlacementCog : Cog {
    @Command(description = "Message only flight operations")
    @SubCommandGroup(name = "booking", description = "Booking operations")
    fun flight(ctx: MessageContext) = Unit
}

private class EmptyDeclaredSubcommandGroupCog : Cog {
    @Command(description = "Flight operations")
    @SubCommandGroup(name = "booking", description = "Booking operations")
    fun flight(ctx: SlashContext) = Unit
}
