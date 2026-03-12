package me.devoxin.flight.api.annotations

/**
 * Marks a function as subcommand.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class SubCommand(
    /**
     * The exported subcommand name. If blank, the method name is used.
     */
    val name: String = "",
    /**
     * The declared subcommand group name. Leave blank for a direct child of the top-level command.
     */
    val group: String = "",
    /**
     * The owning top-level slash command name. Leave blank when the cog has exactly one slash-capable owner.
     */
    val parent: String = "",
    val aliases: Array<String> = [],
    val description: String = "No description available",
    val guildOnly: Boolean = false
)
