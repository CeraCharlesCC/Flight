package me.devoxin.flight.api.error

import me.devoxin.flight.api.context.Context
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class StandardCommandErrorHandler(
    private val config: StandardCommandErrorHandlerConfig = StandardCommandErrorHandlerConfig()
) : CommandErrorHandler {
    override fun handle(failure: CommandFailure) {
        when (failure) {
            is CommandFailure.BadArgumentFailure -> respond(failure.context, config.badArgumentMessage(failure))
            is CommandFailure.CheckFailure -> config.checkFailureMessage(failure)?.let { respond(failure.context, it) }
            is CommandFailure.CooldownFailure -> respond(failure.context, config.cooldownMessage(failure))
            is CommandFailure.MissingUserPermissionsFailure -> {
                respond(failure.context, config.missingUserPermissionsMessage(failure))
            }

            is CommandFailure.MissingBotPermissionsFailure -> {
                respond(failure.context, config.missingBotPermissionsMessage(failure))
            }

            is CommandFailure.UnknownCommandFailure -> {
                if (config.enableUnknownCommandResponses) {
                    dispatch(
                        failure.event.channel.sendMessage(config.unknownCommandMessage(failure)).submit(),
                        "send unknown-command response"
                    )
                }
            }

            is CommandFailure.ParseFailure -> {
                log.error("Command parse failure in {}", failure.command.name, failure.error)
                respond(failure.context, config.parseFailureMessage(failure))
            }

            is CommandFailure.CommandExecutionFailure -> {
                log.error("Command execution failure in {}", failure.command.name, failure.error)
                respond(failure.context, config.commandExecutionMessage(failure))
            }

            is CommandFailure.FrameworkFailure -> {
                log.error("Framework failure while processing an event", failure.error)
            }

            is CommandFailure.AutocompleteFailure -> {
                log.error("Autocomplete failure in {}", failure.event.name, failure.error)
            }
        }
    }

    private fun respond(context: Context, message: String) {
        dispatch(context.respond(message), "respond to command failure")
    }

    private fun dispatch(future: CompletableFuture<*>, action: String) {
        future.whenComplete { _, throwable ->
            if (throwable != null) {
                log.warn("Failed to {}", action, throwable)
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(StandardCommandErrorHandler::class.java)
    }
}
