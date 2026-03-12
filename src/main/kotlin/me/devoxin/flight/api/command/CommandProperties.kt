package me.devoxin.flight.api.command

import me.devoxin.flight.api.annotations.Command
import me.devoxin.flight.api.annotations.MessageCommand
import me.devoxin.flight.api.annotations.UserCommand
import net.dv8tion.jda.api.Permission

/**
 * Normalized command metadata independent of the source annotation.
 */
data class CommandProperties(
    val argDelimiter: Char = ' ',
    val aliases: Set<String> = emptySet(),
    val description: String? = null,
    val developerOnly: Boolean = false,
    val userPermissions: Set<Permission> = emptySet(),
    val botPermissions: Set<Permission> = emptySet(),
    val nsfw: Boolean = false,
    val guildOnly: Boolean = false,
    val hidden: Boolean = false
) {
    val requiresGuildContext: Boolean
        get() = guildOnly || userPermissions.isNotEmpty() || botPermissions.isNotEmpty()

    companion object {
        fun from(annotation: Command): CommandProperties {
            return CommandProperties(
                argDelimiter = annotation.argDelimiter,
                aliases = annotation.aliases.map(String::lowercase).toSet(),
                description = annotation.description,
                developerOnly = annotation.developerOnly,
                userPermissions = annotation.userPermissions.toSet(),
                botPermissions = annotation.botPermissions.toSet(),
                nsfw = annotation.nsfw,
                guildOnly = annotation.guildOnly,
                hidden = annotation.hidden
            )
        }

        fun from(annotation: UserCommand): CommandProperties {
            return CommandProperties(
                developerOnly = annotation.developerOnly,
                userPermissions = annotation.userPermissions.toSet(),
                botPermissions = annotation.botPermissions.toSet(),
                nsfw = annotation.nsfw,
                guildOnly = annotation.guildOnly,
                hidden = annotation.hidden
            )
        }

        fun from(annotation: MessageCommand): CommandProperties {
            return CommandProperties(
                developerOnly = annotation.developerOnly,
                userPermissions = annotation.userPermissions.toSet(),
                botPermissions = annotation.botPermissions.toSet(),
                nsfw = annotation.nsfw,
                guildOnly = annotation.guildOnly,
                hidden = annotation.hidden
            )
        }
    }
}
