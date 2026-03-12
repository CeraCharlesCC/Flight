package me.devoxin.flight.api.annotations

import net.dv8tion.jda.api.Permission

/**
 * Marks a function as a standard Flight command.
 *
 * Standard commands may be message-only, slash-only, or message+slash depending on the
 * declared context parameter type.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Command(
    // The character to use to delimit arguments.
    val argDelimiter: Char = ' ',
    // Any alternative triggers for the command. The name of the command need not be listed here.
    val aliases: Array<String> = [],
    // The command description. This will be shown in the help command.
    val description: String = "No description available",
    // Whether this command can only be invoked by developers (IDs listed in CommandClient.ownerIds)
    val developerOnly: Boolean = false,
    // Any permissions the user needs to execute this command.
    val userPermissions: Array<Permission> = [],
    // Any permissions the bot needs to execute this command.
    val botPermissions: Array<Permission> = [],
    // Whether this command is NSFW or not.
    val nsfw: Boolean = false,
    // Whether this command should only be executed within guilds.
    val guildOnly: Boolean = false,
    // Whether to show this command in the help menu.
    val hidden: Boolean = false
)
