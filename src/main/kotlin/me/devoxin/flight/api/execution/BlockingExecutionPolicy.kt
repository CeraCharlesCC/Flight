package me.devoxin.flight.api.execution

/**
 * Controls how non-suspend command and autocomplete handlers are executed.
 */
enum class BlockingExecutionPolicy {
    /**
     * Dispatch blocking handlers onto the configured execution dispatcher.
     */
    DISPATCH,

    /**
     * Invoke blocking handlers on the caller thread.
     *
     * In this mode, timeout and cancellation guarantees are only reliable for suspend handlers.
     */
    CALLER_THREAD
}
