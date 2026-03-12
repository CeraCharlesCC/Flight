package me.devoxin.flight.api.annotations

import net.dv8tion.jda.api.Permission

/**
 * Marks a function as a Discord message context-menu command.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class MessageCommand(
    /**
     * The display name for the command. If empty, the method name is used.
     */
    val name: String = "",
    /**
     * Whether this command can only be invoked by developers (IDs listed in CommandClient.ownerIds).
     */
    val developerOnly: Boolean = false,
    /**
     * Any permissions the user needs to execute this command.
     */
    val userPermissions: Array<Permission> = [],
    /**
     * Any permissions the bot needs to execute this command.
     */
    val botPermissions: Array<Permission> = [],
    /**
     * Whether this command is NSFW or not.
     */
    val nsfw: Boolean = false,
    /**
     * Whether this command should only be executed within guilds.
     */
    val guildOnly: Boolean = false,
    /**
     * Whether this command should be hidden from user-facing listings.
     */
    val hidden: Boolean = false
)
