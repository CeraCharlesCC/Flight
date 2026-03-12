package me.devoxin.flight.api.annotations

/**
 * The GuildIds that this command may be run within.
 * For application commands, this will scope export and sync to the listed guilds.
 * For message commands, this will be enforced via a check before a command is executed.
 * This is only supported on top-level command handlers.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class GuildIds(
    val value: LongArray
)
