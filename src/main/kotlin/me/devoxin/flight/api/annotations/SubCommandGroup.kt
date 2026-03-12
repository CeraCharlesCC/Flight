package me.devoxin.flight.api.annotations

/**
 * Declares a slash-command subcommand group on a top-level [Command] handler.
 */
@Repeatable
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class SubCommandGroup(
    val name: String,
    val description: String = "No description available"
)
