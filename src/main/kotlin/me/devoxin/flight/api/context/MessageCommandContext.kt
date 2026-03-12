package me.devoxin.flight.api.context

import me.devoxin.flight.api.CommandClient
import me.devoxin.flight.internal.entities.Executable
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent

class MessageCommandContext(
    commandClient: CommandClient,
    override val event: MessageContextInteractionEvent,
    invokedCommand: Executable
) : ContextMenuContext<Message>(commandClient, event, invokedCommand) {
    override val contextType = ContextType.MESSAGE_COMMAND
}
