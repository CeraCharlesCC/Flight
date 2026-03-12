package me.devoxin.flight.internal.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import me.devoxin.flight.api.context.Context
import me.devoxin.flight.api.execution.BlockingExecutionPolicy
import me.devoxin.flight.api.execution.CommandExecutionOptions
import me.devoxin.flight.api.exceptions.AutocompleteCancelledException
import me.devoxin.flight.api.exceptions.AutocompleteInvocationException
import me.devoxin.flight.api.exceptions.AutocompleteTimeoutException
import me.devoxin.flight.api.exceptions.CommandCancelledException
import me.devoxin.flight.api.exceptions.CommandInvocationException
import me.devoxin.flight.api.exceptions.CommandTimeoutException
import me.devoxin.flight.internal.arguments.Argument
import me.devoxin.flight.internal.entities.Executable
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KParameter

internal class CommandExecutionCoordinator(
    private val options: CommandExecutionOptions
) {
    private val parentContext: CoroutineContext = options.parentScope?.coroutineContext ?: EmptyCoroutineContext
    private val parentJob: Job? = parentContext[Job]
    private val executionJob = SupervisorJob(parentJob)
    private val executionScope = CoroutineScope(parentContext.minusKey(Job) + executionJob + options.dispatcher)

    fun executeCommand(
        executable: Executable,
        ctx: Context,
        args: HashMap<KParameter, Any?>,
        completion: (Boolean, Throwable?) -> Unit
    ) {
        val boundArgs = executable.bindArguments(ctx, args)
        val timeoutMillis = executable.timeout?.let { it.timeUnit.toMillis(it.duration) } ?: options.defaultTimeoutMillis

        if (!executionScope.isActive) {
            completion(false, CommandCancelledException(executable.name, inactiveScopeCancellation()))
            return
        }

        if (!executable.isSuspendHandler && options.blockingExecutionPolicy == BlockingExecutionPolicy.CALLER_THREAD) {
            executeInlineBlockingCommand(executable, boundArgs, completion)
            return
        }

        executeManaged(
            timeoutMillis = timeoutMillis,
            mapFailure = { throwable -> mapCommandFailure(executable, throwable, timeoutMillis) },
            completion = completion
        ) {
            if (executable.isSuspendHandler) {
                executable.invokeSuspend(boundArgs)
            } else {
                runInterruptible(options.dispatcher) {
                    executable.invokeBlocking(boundArgs)
                }
            }
        }
    }

    fun executeAutocomplete(
        argument: Argument,
        event: CommandAutoCompleteInteractionEvent,
        completion: (Throwable?) -> Unit
    ) {
        val timeoutMillis = options.defaultTimeoutMillis

        if (!executionScope.isActive) {
            completion(AutocompleteCancelledException(argument.name, inactiveScopeCancellation()))
            return
        }

        if (!argument.isSuspendAutocompleteHandler && options.blockingExecutionPolicy == BlockingExecutionPolicy.CALLER_THREAD) {
            executeInlineBlockingAutocomplete(argument, event, completion)
            return
        }

        executeManaged(
            timeoutMillis = timeoutMillis,
            mapFailure = { throwable -> mapAutocompleteFailure(argument, throwable, timeoutMillis) },
            completion = { _, throwable -> completion(throwable) }
        ) {
            if (argument.isSuspendAutocompleteHandler) {
                argument.invokeAutocompleteSuspend(event)
            } else {
                runInterruptible(options.dispatcher) {
                    argument.invokeAutocomplete(event)
                }
            }
        }
    }

    fun shutdown() {
        executionScope.cancel()
    }

    private fun executeInlineBlockingCommand(
        executable: Executable,
        boundArgs: Map<KParameter, Any?>,
        completion: (Boolean, Throwable?) -> Unit
    ) {
        try {
            executable.invokeBlocking(boundArgs)
            completion(true, null)
        } catch (throwable: Throwable) {
            completion(false, mapCommandFailure(executable, throwable, null))
        }
    }

    private fun executeInlineBlockingAutocomplete(
        argument: Argument,
        event: CommandAutoCompleteInteractionEvent,
        completion: (Throwable?) -> Unit
    ) {
        try {
            argument.invokeAutocomplete(event)
            completion(null)
        } catch (throwable: Throwable) {
            completion(mapAutocompleteFailure(argument, throwable, null))
        }
    }

    private fun executeManaged(
        timeoutMillis: Long?,
        mapFailure: (Throwable) -> Throwable,
        completion: (Boolean, Throwable?) -> Unit,
        block: suspend () -> Unit
    ) {
        val completed = AtomicBoolean(false)
        fun complete(success: Boolean, throwable: Throwable?) {
            if (completed.compareAndSet(false, true)) {
                completion(success, throwable)
            }
        }

        val job = executionScope.launch {
            try {
                if (timeoutMillis != null) {
                    withTimeout(timeoutMillis) {
                        block()
                    }
                } else {
                    block()
                }

                complete(true, null)
            } catch (throwable: Throwable) {
                complete(false, mapFailure(throwable))
            }
        }

        job.invokeOnCompletion { throwable ->
            if (throwable != null) {
                complete(false, mapFailure(throwable))
            }
        }
    }

    private fun mapCommandFailure(
        executable: Executable,
        throwable: Throwable,
        timeoutMillis: Long?
    ): Throwable {
        return when (throwable) {
            is CommandTimeoutException,
            is CommandCancelledException,
            is CommandInvocationException -> throwable

            is TimeoutCancellationException -> CommandTimeoutException(
                executable.name,
                timeoutMillis ?: 0L,
                throwable
            )

            is CancellationException -> CommandCancelledException(executable.name, throwable)
            else -> CommandInvocationException(executable.name, executable.unwrapInvocationFailure(throwable))
        }
    }

    private fun mapAutocompleteFailure(
        argument: Argument,
        throwable: Throwable,
        timeoutMillis: Long?
    ): Throwable {
        return when (throwable) {
            is AutocompleteTimeoutException,
            is AutocompleteCancelledException,
            is AutocompleteInvocationException -> throwable

            is TimeoutCancellationException -> AutocompleteTimeoutException(
                argument.name,
                timeoutMillis ?: 0L,
                throwable
            )

            is CancellationException -> AutocompleteCancelledException(argument.name, throwable)
            else -> AutocompleteInvocationException(argument.name, argument.unwrapInvocationFailure(throwable))
        }
    }

    private fun inactiveScopeCancellation(): CancellationException {
        return CancellationException("Flight execution scope is no longer active.")
    }
}
