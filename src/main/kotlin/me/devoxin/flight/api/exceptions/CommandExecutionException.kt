package me.devoxin.flight.api.exceptions

open class CommandExecutionException(
    message: String,
    cause: Throwable? = null
) : Throwable(message, cause)

class CommandTimeoutException(
    commandName: String,
    timeoutMillis: Long,
    cause: Throwable? = null
) : CommandExecutionException(
    "Command '$commandName' exceeded its execution timeout of ${timeoutMillis}ms.",
    cause
)

class CommandCancelledException(
    commandName: String,
    cause: Throwable? = null
) : CommandExecutionException(
    "Command '$commandName' execution was cancelled.",
    cause
)

class CommandInvocationException(
    commandName: String,
    cause: Throwable
) : CommandExecutionException(
    "Command '$commandName' failed during execution.",
    cause
)
