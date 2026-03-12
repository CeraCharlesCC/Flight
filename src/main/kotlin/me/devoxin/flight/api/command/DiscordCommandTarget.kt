package me.devoxin.flight.api.command

import me.devoxin.flight.api.sync.CommandSyncScope
import net.dv8tion.jda.api.interactions.commands.build.CommandData

/**
 * A scope-explicit export target for Discord application commands.
 */
data class DiscordCommandTarget(
    val scope: CommandSyncScope,
    val commands: List<CommandData>
)