package me.devoxin.flight.api.error

import me.devoxin.flight.api.CommandFunction
import me.devoxin.flight.api.check.CheckType
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.exceptions.BadArgument
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent

sealed interface CommandFailure {
    sealed interface UserFacing : CommandFailure

    sealed interface Internal : CommandFailure

    data class BadArgumentFailure(
        val context: Context,
        val command: CommandFunction,
        val error: BadArgument
    ) : UserFacing

    data class CheckFailure(
        val context: Context,
        val command: CommandFunction,
        val checkType: CheckType
    ) : UserFacing

    data class CooldownFailure(
        val context: Context,
        val command: CommandFunction,
        val cooldownMillis: Long
    ) : UserFacing

    data class MissingUserPermissionsFailure(
        val context: Context,
        val command: CommandFunction,
        val permissions: List<Permission>
    ) : UserFacing

    data class MissingBotPermissionsFailure(
        val context: Context,
        val command: CommandFunction,
        val permissions: List<Permission>
    ) : UserFacing

    data class UnknownCommandFailure(
        val event: MessageReceivedEvent,
        val command: String,
        val args: List<String>
    ) : UserFacing

    data class ParseFailure(
        val context: Context,
        val command: CommandFunction,
        val error: Throwable
    ) : Internal

    data class CommandExecutionFailure(
        val context: Context,
        val command: CommandFunction,
        val error: Throwable
    ) : Internal

    data class FrameworkFailure(
        val error: Throwable
    ) : Internal

    data class AutocompleteFailure(
        val event: CommandAutoCompleteInteractionEvent,
        val error: Throwable
    ) : Internal
}
