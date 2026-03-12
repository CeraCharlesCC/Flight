package me.devoxin.flight.api.localization

import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand

/**
 * Resolves localizations for application-command fields produced by Flight.
 */
fun interface CommandLocalizationProvider {
    /**
     * Returns zero or more localized values for the requested command field.
     */
    fun getLocalizations(request: CommandLocalizationRequest): Map<DiscordLocale, String>
}

/**
 * The application-command field currently being localized.
 */
enum class CommandLocalizationField {
    COMMAND_NAME,
    COMMAND_DESCRIPTION,
    SUBCOMMAND_GROUP_NAME,
    SUBCOMMAND_GROUP_DESCRIPTION,
    SUBCOMMAND_NAME,
    SUBCOMMAND_DESCRIPTION,
    OPTION_NAME,
    OPTION_DESCRIPTION,
    CHOICE_NAME
}

/**
 * Describes a command field that Flight is about to export or sync.
 */
data class CommandLocalizationRequest(
    val field: CommandLocalizationField,
    val commandType: JdaCommand.Type,
    val commandName: String,
    val subcommandGroupName: String? = null,
    val subcommandName: String? = null,
    val optionName: String? = null,
    val choiceValue: String? = null,
    val defaultValue: String
)
