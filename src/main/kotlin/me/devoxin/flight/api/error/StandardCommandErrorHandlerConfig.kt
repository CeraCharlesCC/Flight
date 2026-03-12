package me.devoxin.flight.api.error

import me.devoxin.flight.api.check.CheckType

class StandardCommandErrorHandlerConfig {
    var enableUnknownCommandResponses: Boolean = false

    var badArgumentMessage: (CommandFailure.BadArgumentFailure) -> String = { failure ->
        failure.error.message ?: "One of the provided arguments is invalid."
    }

    var checkFailureMessage: (CommandFailure.CheckFailure) -> String? = { failure ->
        when (failure.checkType) {
            CheckType.EXECUTION_CONTEXT -> "That command can't be used from this context."
            CheckType.LOCAL_CHECK -> "That command can't be used right now."
            CheckType.GUILD_CHECK -> "That command can only be used in a server."
            CheckType.GUILD_ID_CHECK -> "That command can't be used in this server."
            CheckType.NSFW_CHECK -> "That command can only be used in NSFW channels."
            CheckType.DEVELOPER_CHECK -> "That command is restricted to bot developers."
        }
    }

    var cooldownMessage: (CommandFailure.CooldownFailure) -> String = { failure ->
        val seconds = kotlin.math.ceil(failure.cooldownMillis / 1000.0).toLong().coerceAtLeast(1L)
        "That command is on cooldown. Try again in ${seconds}s."
    }

    var missingUserPermissionsMessage: (CommandFailure.MissingUserPermissionsFailure) -> String = { failure ->
        "You are missing the required permissions: ${formatPermissions(failure.permissions)}."
    }

    var missingBotPermissionsMessage: (CommandFailure.MissingBotPermissionsFailure) -> String = { failure ->
        "I am missing the required permissions: ${formatPermissions(failure.permissions)}."
    }

    var unknownCommandMessage: (CommandFailure.UnknownCommandFailure) -> String = { failure ->
        "Unknown command: ${failure.command}"
    }

    var parseFailureMessage: (CommandFailure.ParseFailure) -> String = {
        "Something went wrong while parsing that command."
    }

    var commandExecutionMessage: (CommandFailure.CommandExecutionFailure) -> String = {
        "Something went wrong while executing that command."
    }

    internal companion object {
        internal fun formatPermissions(permissions: List<net.dv8tion.jda.api.Permission>): String {
            return permissions.joinToString(", ") { it.name.lowercase().replace('_', ' ') }
        }
    }
}
