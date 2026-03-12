package me.devoxin.flight.api.exceptions

open class AutocompleteExecutionException(
    message: String,
    cause: Throwable? = null
) : Throwable(message, cause)

class AutocompleteTimeoutException(
    argumentName: String,
    timeoutMillis: Long,
    cause: Throwable? = null
) : AutocompleteExecutionException(
    "Autocomplete handler for '$argumentName' exceeded its execution timeout of ${timeoutMillis}ms.",
    cause
)

class AutocompleteCancelledException(
    argumentName: String,
    cause: Throwable? = null
) : AutocompleteExecutionException(
    "Autocomplete handler for '$argumentName' was cancelled.",
    cause
)

class AutocompleteInvocationException(
    argumentName: String,
    cause: Throwable
) : AutocompleteExecutionException(
    "Autocomplete handler for '$argumentName' failed during execution.",
    cause
)
