package me.devoxin.flight.api.command

import net.dv8tion.jda.api.interactions.commands.Command as JdaCommand

/**
 * A normalized application-command lookup key.
 */
data class ApplicationCommandKey(
    val name: String,
    val type: JdaCommand.Type
) {
    companion object {
        fun of(name: String, type: JdaCommand.Type): ApplicationCommandKey {
            return ApplicationCommandKey(name.lowercase(), type)
        }
    }
}
