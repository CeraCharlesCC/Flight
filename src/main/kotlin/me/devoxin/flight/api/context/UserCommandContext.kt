package me.devoxin.flight.api.context

import me.devoxin.flight.api.CommandClient
import me.devoxin.flight.internal.entities.Executable
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent

class UserCommandContext(
    commandClient: CommandClient,
    override val event: UserContextInteractionEvent,
    invokedCommand: Executable
) : ContextMenuContext<User>(commandClient, event, invokedCommand) {
    override val contextType = ContextType.USER_COMMAND

    val targetMember: Member?
        get() = event.targetMember
}
