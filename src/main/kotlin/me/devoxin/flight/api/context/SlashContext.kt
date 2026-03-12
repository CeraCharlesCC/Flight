package me.devoxin.flight.api.context

import me.devoxin.flight.api.CommandClient
import me.devoxin.flight.internal.entities.Executable
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent

class SlashContext(
    commandClient: CommandClient,
    override val event: SlashCommandInteractionEvent,
    override val invokedCommand: Executable
) : InteractionContext(commandClient, event, invokedCommand) {
    override val contextType = ContextType.SLASH
}
