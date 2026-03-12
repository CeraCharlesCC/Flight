package me.devoxin.flight.api.execution

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Coroutine-native execution options for Flight command and autocomplete handling.
 */
data class CommandExecutionOptions(
    val parentScope: CoroutineScope? = null,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val blockingExecutionPolicy: BlockingExecutionPolicy = BlockingExecutionPolicy.DISPATCH,
    val defaultTimeoutMillis: Long? = null
) {
    init {
        require(defaultTimeoutMillis == null || defaultTimeoutMillis > 0) {
            "defaultTimeoutMillis must be greater than 0 when provided."
        }
    }

    fun toBuilder(): Builder {
        return Builder().also {
            it.parentScope = parentScope
            it.dispatcher = dispatcher
            it.blockingExecutionPolicy = blockingExecutionPolicy
            it.defaultTimeoutMillis = defaultTimeoutMillis
        }
    }

    class Builder {
        var parentScope: CoroutineScope? = null
        var dispatcher: CoroutineDispatcher = Dispatchers.Default
        var blockingExecutionPolicy: BlockingExecutionPolicy = BlockingExecutionPolicy.DISPATCH
        var defaultTimeoutMillis: Long? = null

        fun build(): CommandExecutionOptions {
            return CommandExecutionOptions(
                parentScope = parentScope,
                dispatcher = dispatcher,
                blockingExecutionPolicy = blockingExecutionPolicy,
                defaultTimeoutMillis = defaultTimeoutMillis
            )
        }
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
