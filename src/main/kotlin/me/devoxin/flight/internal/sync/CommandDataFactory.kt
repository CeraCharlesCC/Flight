package me.devoxin.flight.internal.sync

import me.devoxin.flight.api.CommandFunction
import me.devoxin.flight.api.SubCommandFunction
import me.devoxin.flight.api.localization.CommandLocalizationField
import me.devoxin.flight.api.localization.CommandLocalizationProvider
import me.devoxin.flight.api.localization.CommandLocalizationRequest
import me.devoxin.flight.internal.arguments.Argument
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.IntegrationType
import net.dv8tion.jda.api.interactions.InteractionContextType
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData

internal object CommandDataFactory {
    fun createCommandData(
        command: CommandFunction,
        localizationProvider: CommandLocalizationProvider? = null
    ): CommandData {
        return when (command.applicationCommandType) {
            JdaCommand.Type.SLASH -> createSlashCommandData(command, localizationProvider)
            JdaCommand.Type.USER -> createUserCommandData(command, localizationProvider)
            JdaCommand.Type.MESSAGE -> createMessageCommandData(command, localizationProvider)
            null -> throw IllegalArgumentException("${command.name} is not an application command and cannot be exported.")
            else -> throw IllegalArgumentException("Unsupported application command type: ${command.applicationCommandType}")
        }
    }

    fun createSlashCommandData(
        command: CommandFunction,
        localizationProvider: CommandLocalizationProvider? = null
    ): SlashCommandData {
        val description = command.properties.description
            ?: throw IllegalArgumentException("Slash command ${command.name} is missing a description.")

        val data = Commands.slash(command.name, description)

        applySharedCommandSettings(data, command)
        applySlashCommandLocalizations(data, command, localizationProvider)

        if (command.directSubcommands.isNotEmpty()) {
            data.addSubcommands(
                command.directSubcommands.map { createSubcommandData(command, it, null, localizationProvider) }
            )
        }

        if (command.subcommandGroups.isNotEmpty()) {
            data.addSubcommandGroups(
                command.subcommandGroups.map { createSubcommandGroupData(command, it, localizationProvider) }
            )
        } else if (command.arguments.isNotEmpty()) {
            data.addOptions(command.arguments.map { createOptionData(command, null, null, it, localizationProvider) })
        }

        return data
    }

    fun createUserCommandData(
        command: CommandFunction,
        localizationProvider: CommandLocalizationProvider? = null
    ): CommandData {
        return createContextCommandData(Commands.user(command.name), command, localizationProvider)
    }

    fun createMessageCommandData(
        command: CommandFunction,
        localizationProvider: CommandLocalizationProvider? = null
    ): CommandData {
        return createContextCommandData(Commands.message(command.name), command, localizationProvider)
    }

    private fun createSubcommandData(
        command: CommandFunction,
        subcommand: SubCommandFunction,
        subcommandGroupName: String?,
        localizationProvider: CommandLocalizationProvider?
    ): SubcommandData {
        val data = SubcommandData(subcommand.name, subcommand.properties.description)

        applyLocalizations(
            nameField = CommandLocalizationField.SUBCOMMAND_NAME,
            descriptionField = CommandLocalizationField.SUBCOMMAND_DESCRIPTION,
            commandName = command.name,
            subcommandGroupName = subcommandGroupName,
            subcommandName = subcommand.name,
            optionName = null,
            defaultName = subcommand.name,
            defaultDescription = subcommand.properties.description,
            commandType = command.applicationCommandType!!,
            setNameLocalizations = data::setNameLocalizations,
            setDescriptionLocalizations = data::setDescriptionLocalizations,
            localizationProvider = localizationProvider
        )

        if (subcommand.arguments.isNotEmpty()) {
            data.addOptions(
                subcommand.arguments.map {
                    createOptionData(command, subcommandGroupName, subcommand, it, localizationProvider)
                }
            )
        }

        return data
    }

    private fun createSubcommandGroupData(
        command: CommandFunction,
        group: me.devoxin.flight.api.SubcommandGroupDefinition,
        localizationProvider: CommandLocalizationProvider?
    ): SubcommandGroupData {
        val data = SubcommandGroupData(group.name, group.description)

        applyLocalizations(
            nameField = CommandLocalizationField.SUBCOMMAND_GROUP_NAME,
            descriptionField = CommandLocalizationField.SUBCOMMAND_GROUP_DESCRIPTION,
            commandName = command.name,
            subcommandGroupName = group.name,
            subcommandName = null,
            optionName = null,
            defaultName = group.name,
            defaultDescription = group.description,
            commandType = command.applicationCommandType!!,
            setNameLocalizations = data::setNameLocalizations,
            setDescriptionLocalizations = data::setDescriptionLocalizations,
            localizationProvider = localizationProvider
        )

        data.addSubcommands(
            group.subcommands.map { createSubcommandData(command, it, group.name, localizationProvider) }
        )

        return data
    }

    private fun createOptionData(
        command: CommandFunction,
        subcommandGroupName: String?,
        subcommand: SubCommandFunction?,
        argument: Argument,
        localizationProvider: CommandLocalizationProvider?
    ): OptionData {
        try {
            val option = argument.asSlashCommandType()

            applyLocalizations(
                nameField = CommandLocalizationField.OPTION_NAME,
                descriptionField = CommandLocalizationField.OPTION_DESCRIPTION,
                commandName = command.name,
                subcommandGroupName = subcommandGroupName,
                subcommandName = subcommand?.name,
                optionName = argument.slashFriendlyName,
                defaultName = argument.slashFriendlyName,
                defaultDescription = argument.description,
                commandType = command.applicationCommandType!!,
                setNameLocalizations = option::setNameLocalizations,
                setDescriptionLocalizations = option::setDescriptionLocalizations,
                localizationProvider = localizationProvider
            )

            option.choices.forEach { choice ->
                val request = CommandLocalizationRequest(
                    field = CommandLocalizationField.CHOICE_NAME,
                    commandType = command.applicationCommandType,
                    commandName = command.name,
                    subcommandGroupName = subcommandGroupName,
                    subcommandName = subcommand?.name,
                    optionName = argument.slashFriendlyName,
                    choiceValue = getChoiceValue(choice),
                    defaultValue = choice.name
                )

                localizationsFor(localizationProvider, request)?.let(choice::setNameLocalizations)
            }

            return option
        } catch (t: Throwable) {
            throw IllegalStateException(
                "Invalid slash option mapping for command path '${commandPath(command.name, subcommandGroupName, subcommand?.name)}', " +
                    "parameter '${argument.name}' (${argument.type.name}): ${t.message}",
                t
            )
        }
    }

    private fun createContextCommandData(
        data: CommandData,
        command: CommandFunction,
        localizationProvider: CommandLocalizationProvider?
    ): CommandData {
        applySharedCommandSettings(data, command)
        applyCommandNameLocalization(data, command, localizationProvider)
        return data
    }

    private fun applySharedCommandSettings(
        data: CommandData,
        command: CommandFunction
    ) {
        data.isNSFW = command.properties.nsfw

        if (command.properties.userPermissions.isNotEmpty()) {
            data.setDefaultPermissions(
                DefaultMemberPermissions.enabledFor(*command.properties.userPermissions.toTypedArray())
            )
        }

        if (command.properties.requiresGuildContext) {
            data.setContexts(InteractionContextType.GUILD)
            data.setIntegrationTypes(IntegrationType.GUILD_INSTALL)
        }
    }

    private fun applySlashCommandLocalizations(
        data: SlashCommandData,
        command: CommandFunction,
        localizationProvider: CommandLocalizationProvider?
    ) {
        applyLocalizations(
            nameField = CommandLocalizationField.COMMAND_NAME,
            descriptionField = CommandLocalizationField.COMMAND_DESCRIPTION,
            commandName = command.name,
            subcommandGroupName = null,
            subcommandName = null,
            optionName = null,
            defaultName = command.name,
            defaultDescription = command.properties.description!!,
            commandType = command.applicationCommandType!!,
            setNameLocalizations = data::setNameLocalizations,
            setDescriptionLocalizations = data::setDescriptionLocalizations,
            localizationProvider = localizationProvider
        )
    }

    private fun applyLocalizations(
        nameField: CommandLocalizationField,
        descriptionField: CommandLocalizationField,
        commandName: String,
        subcommandGroupName: String?,
        subcommandName: String?,
        optionName: String?,
        defaultName: String,
        defaultDescription: String,
        commandType: JdaCommand.Type,
        setNameLocalizations: (Map<DiscordLocale, String>) -> Unit,
        setDescriptionLocalizations: (Map<DiscordLocale, String>) -> Unit,
        localizationProvider: CommandLocalizationProvider?
    ) {
        localizationsFor(
            localizationProvider,
            CommandLocalizationRequest(
                field = nameField,
                commandType = commandType,
                commandName = commandName,
                subcommandGroupName = subcommandGroupName,
                subcommandName = subcommandName,
                optionName = optionName,
                defaultValue = defaultName
            )
        )?.let(setNameLocalizations)

        localizationsFor(
            localizationProvider,
            CommandLocalizationRequest(
                field = descriptionField,
                commandType = commandType,
                commandName = commandName,
                subcommandGroupName = subcommandGroupName,
                subcommandName = subcommandName,
                optionName = optionName,
                defaultValue = defaultDescription
            )
        )?.let(setDescriptionLocalizations)
    }

    private fun applyCommandNameLocalization(
        data: CommandData,
        command: CommandFunction,
        localizationProvider: CommandLocalizationProvider?
    ) {
        localizationsFor(
            localizationProvider,
            CommandLocalizationRequest(
                field = CommandLocalizationField.COMMAND_NAME,
                commandType = command.applicationCommandType!!,
                commandName = command.name,
                subcommandGroupName = null,
                defaultValue = command.name
            )
        )?.let(data::setNameLocalizations)
    }

    private fun localizationsFor(
        localizationProvider: CommandLocalizationProvider?,
        request: CommandLocalizationRequest
    ) = localizationProvider
        ?.getLocalizations(request)
        ?.takeUnless { it.isEmpty() }

    private fun getChoiceValue(choice: Command.Choice): String {
        return when (choice.type) {
            OptionType.STRING -> choice.asString
            OptionType.INTEGER -> choice.asLong.toString()
            OptionType.NUMBER -> choice.asDouble.toString()
            else -> choice.name
        }
    }

    private fun commandPath(
        commandName: String,
        subcommandGroupName: String?,
        subcommandName: String?
    ): String {
        return buildString {
            append(commandName)

            if (!subcommandGroupName.isNullOrBlank()) {
                append(' ')
                append(subcommandGroupName)
            }

            if (!subcommandName.isNullOrBlank()) {
                append(' ')
                append(subcommandName)
            }
        }
    }

}
