package me.devoxin.flight.api.context

import me.devoxin.flight.api.CommandClient
import me.devoxin.flight.internal.entities.Executable
import net.dv8tion.jda.api.events.interaction.command.GenericContextInteractionEvent

abstract class ContextMenuContext<T>(
    commandClient: CommandClient,
    baseEvent: GenericContextInteractionEvent<T>,
    invokedCommand: Executable
) : InteractionContext(commandClient, baseEvent, invokedCommand) {
    override val event: GenericContextInteractionEvent<T> = baseEvent
    val target: T = baseEvent.target
}
